package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceAssignment;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.audienceplan.service.SendPathGuard;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.OrderFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The guard runs again for every claimed batch, so a change after materialisation still stops the email. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class SendPathGuardPerBatchTest {

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired AudienceExperimentRepository experiments;
    @Autowired AudienceAssignmentRepository assignments;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @MockitoBean CampaignEmailProvider provider;
    @Autowired AudiencePlanProperties props;

    @AfterEach
    void resetFlag() {
        props.setConsentGateAllCampaigns(false);
    }

    /** An audience_plan send requires the org's legal identity (org-identity gate) before this guard is even reached. */
    private UUID orgWithLegalIdentity() {
        Organization o = new Organization();
        o.setName("Batch Org");
        o.setSlug("batch-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail("batch@test.com");
        o.setCountry("DE");
        o.setLegalName("Batch SAS");
        o.setLegalContact("legal@batch.test");
        return organizations.save(o).getId();
    }

    private Campaign campaign(UUID orgId, UUID eventId, String origin) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Batch");
        c.setStatus("sending");
        c.setOrigin(origin);
        c.setEventId(eventId);
        c.setSubject("Subject");
        c.setBodyMd("Body");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private CampaignRecipient pendingRow(Campaign c, UUID orgId) {
        Consumer cn = new Consumer();
        String email = "batch-" + UUID.randomUUID() + "@example.com";
        cn.setNormalizedEmail(email);
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setStatus("active");
        m.setConsentStatus("subscribed");
        m.setConsentBasis("consent");
        m = memberships.save(m);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(email);
        r.setStatus("pending");
        return recipients.save(r);
    }

    private void holdOut(UUID orgId, UUID eventId, UUID membershipId) {
        AudienceExperiment e = new AudienceExperiment();
        e.setOrgId(orgId);
        e.setEventId(eventId);
        e.setArm("holdout");
        e.setSeed(7L);
        e.setMembers(1);
        e = experiments.save(e);
        AudienceAssignment a = new AudienceAssignment();
        a.setExperimentId(e.getId());
        a.setMembershipId(membershipId);
        a.setArm("holdout");
        a.setAssignedAt(Instant.now());
        assignments.save(a);
    }

    private void priorSend(UUID orgId, UUID eventId, UUID membershipId) {
        Campaign prior = campaign(orgId, eventId, "manual");
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(prior.getId());
        r.setMembershipId(membershipId);
        r.setEmail("prior@example.com");
        r.setStatus("sent");
        r.setLastEventAt(Instant.now().minus(1, ChronoUnit.DAYS));
        recipients.save(r);
    }

    private List<CampaignEmailProvider.OutgoingEmail> sentBatch() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider).sendBatch(captor.capture());
        return captor.getValue();
    }

    @Test
    void memberHeldOutAfterMaterialisationIsSkippedNotSent() {
        // A real event: experiments reference events (V154 FK).
        var event = OrderFixtures.event(organizations, users, events, "Batch", Instant.now().plus(7, ChronoUnit.DAYS));
        UUID orgId = event.getOrgId();
        UUID eventId = event.getId();
        Campaign c = campaign(orgId, eventId, "manual");
        CampaignRecipient keep = pendingRow(c, orgId);
        CampaignRecipient held = pendingRow(c, orgId);
        holdOut(orgId, eventId, held.getMembershipId());
        when(provider.sendBatch(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(x -> "msg-" + UUID.randomUUID()).toList());

        sender.sendNextBatch(c);

        assertThat(recipients.findById(keep.getId()).orElseThrow().getStatus()).isEqualTo("sent");
        CampaignRecipient skipped = recipients.findById(held.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.EXPERIMENT_HOLDOUT);
        assertThat(skipped.getLastEventAt()).isNotNull();
        assertThat(sentBatch()).extracting(CampaignEmailProvider.OutgoingEmail::to).containsExactly(keep.getEmail());
    }

    @Test
    void capReachedByAnotherCampaignMidSendIsDivertedNotSent() {
        UUID orgId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Campaign c = campaign(orgId, eventId, "manual");
        CampaignRecipient row = pendingRow(c, orgId);
        // Another campaign for the same event reached the event cap for this member between
        // materialisation and this batch — the per-batch re-check must still catch it.
        priorSend(orgId, eventId, row.getMembershipId());
        priorSend(orgId, eventId, row.getMembershipId());

        sender.sendNextBatch(c);

        CampaignRecipient skipped = recipients.findById(row.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.EVENT_CAP);
        verify(provider, Mockito.never()).sendBatch(anyList());
    }

    @Test
    void planCampaignMemberWithoutProvenConsentIsSkippedAtSendTime() {
        UUID orgId = orgWithLegalIdentity();
        Campaign c = campaign(orgId, null, "audience_plan");
        // SendGate admits this member; ConsentGate does not (no consent record proves the basis).
        CampaignRecipient unproven = pendingRow(c, orgId);

        sender.sendNextBatch(c);

        CampaignRecipient skipped = recipients.findById(unproven.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        verify(provider, Mockito.never()).sendBatch(anyList());
    }

    @Test
    void flagOffManualCampaignMemberWithoutProvenConsentIsSent() {
        UUID orgId = orgWithLegalIdentity();
        Campaign c = campaign(orgId, null, "manual");
        CampaignRecipient unproven = pendingRow(c, orgId);
        when(provider.sendBatch(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(x -> "msg-" + UUID.randomUUID()).toList());

        sender.sendNextBatch(c);

        assertThat(recipients.findById(unproven.getId()).orElseThrow().getStatus()).isEqualTo("sent");
        assertThat(sentBatch()).extracting(CampaignEmailProvider.OutgoingEmail::to).containsExactly(unproven.getEmail());
    }

    @Test
    void flagOnManualCampaignMemberWithoutProvenConsentIsSkippedAtSendTime() {
        props.setConsentGateAllCampaigns(true);
        UUID orgId = orgWithLegalIdentity();
        Campaign c = campaign(orgId, null, "manual");
        CampaignRecipient unproven = pendingRow(c, orgId);

        sender.sendNextBatch(c);

        CampaignRecipient skipped = recipients.findById(unproven.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        verify(provider, Mockito.never()).sendBatch(anyList());
    }
}
