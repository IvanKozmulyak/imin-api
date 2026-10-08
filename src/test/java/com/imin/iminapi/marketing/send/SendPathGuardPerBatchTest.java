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
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SendGate and SendPathGuard run again for every claimed batch: a large campaign drains long after
 * materialisation, so an opt-out, holdout, cap or consent change in between still stops the email.
 */
@IminIntegrationTest
class SendPathGuardPerBatchTest {

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired AudienceExperimentRepository experiments;
    @Autowired AudienceAssignmentRepository assignments;
    @Autowired OrganizationRepository organizations;
    @Autowired CampaignEmailProvider provider;
    @Autowired AudiencePlanProperties props;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private final Set<String> ownAddresses = new HashSet<>();

    @BeforeEach
    void stubProvider() {
        when(provider.sendBatch(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(x -> "msg-" + UUID.randomUUID()).toList());
    }

    /** The campaigns are left 'sending', which the global claim would reclaim once stale. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    private UUID newOrgId() {
        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);
        return orgId;
    }

    /** An audience_plan send requires the org's legal identity (org-identity gate) before this guard is even reached. */
    private UUID orgWithLegalIdentity() {
        Organization o = fx.org();
        o.setLegalName("Batch SAS");
        o.setLegalContact(fx.email("legal"));
        o = organizations.save(o);
        orgIds.add(o.getId());
        return o.getId();
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
        return pendingRow(c, orgId, "subscribed");
    }

    /** A row the materializer admitted; {@code consentStatus} is the member's state now, at the batch. */
    private CampaignRecipient pendingRow(Campaign c, UUID orgId, String consentStatus) {
        Consumer cn = new Consumer();
        String email = fx.email("batch");
        cn.setNormalizedEmail(email);
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setStatus("active");
        m.setConsentStatus(consentStatus);
        m.setConsentBasis("consent");
        m = memberships.save(m);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(email);
        r.setStatus("pending");
        ownAddresses.add(email);
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
        r.setEmail(fx.email("prior"));
        r.setStatus("sent");
        r.setLastEventAt(Instant.now().minus(1, ChronoUnit.DAYS));
        recipients.save(r);
    }

    /** Addresses this test's rows handed to the provider. */
    @SuppressWarnings("unchecked")
    private List<String> sentTo() {
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(0)).sendBatch(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream)
                .map(CampaignEmailProvider.OutgoingEmail::to).filter(ownAddresses::contains).toList();
    }

    @Test
    void recipientWhoUnsubscribedAfterMaterialisationIsSkippedNotSent() {
        UUID orgId = newOrgId();
        Campaign c = campaign(orgId, null, "manual");
        CampaignRecipient goodRow = pendingRow(c, orgId);
        // Materialisation snapshotted this member as sendable; they opted out afterwards.
        CampaignRecipient optedOutRow = pendingRow(c, orgId, "unsubscribed");

        sender.sendNextBatch(c);

        assertThat(recipients.findById(goodRow.getId()).orElseThrow().getStatus()).isEqualTo("sent");
        CampaignRecipient skipped = recipients.findById(optedOutRow.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo("marketing_unsubscribed");
        assertThat(sentTo()).containsExactly(goodRow.getEmail());
    }

    @Test
    void memberHeldOutAfterMaterialisationIsSkippedNotSent() {
        // A real event: experiments reference events (V154 FK).
        Organization org = fx.org();
        orgIds.add(org.getId());
        Event event = fx.event(org, fx.owner(org), EventStatus.LIVE, Instant.now().plus(7, ChronoUnit.DAYS));
        UUID orgId = org.getId();
        UUID eventId = event.getId();
        Campaign c = campaign(orgId, eventId, "manual");
        CampaignRecipient keep = pendingRow(c, orgId);
        CampaignRecipient held = pendingRow(c, orgId);
        holdOut(orgId, eventId, held.getMembershipId());

        sender.sendNextBatch(c);

        assertThat(recipients.findById(keep.getId()).orElseThrow().getStatus()).isEqualTo("sent");
        CampaignRecipient skipped = recipients.findById(held.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.EXPERIMENT_HOLDOUT);
        assertThat(skipped.getLastEventAt()).isNotNull();
        assertThat(sentTo()).containsExactly(keep.getEmail());
    }

    @Test
    void capReachedByAnotherCampaignMidSendIsDivertedNotSent() {
        UUID orgId = newOrgId();
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
        assertThat(sentTo()).isEmpty();
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
        assertThat(sentTo()).isEmpty();
    }

    @Test
    void flagOffManualCampaignMemberWithoutProvenConsentIsSent() {
        UUID orgId = orgWithLegalIdentity();
        Campaign c = campaign(orgId, null, "manual");
        CampaignRecipient unproven = pendingRow(c, orgId);

        sender.sendNextBatch(c);

        assertThat(recipients.findById(unproven.getId()).orElseThrow().getStatus()).isEqualTo("sent");
        assertThat(sentTo()).containsExactly(unproven.getEmail());
    }

    @Test
    void flagOnManualCampaignMemberWithoutProvenConsentIsSkippedAtSendTime() {
        flips.set(props, "consentGateAllCampaigns", true);
        UUID orgId = orgWithLegalIdentity();
        Campaign c = campaign(orgId, null, "manual");
        CampaignRecipient unproven = pendingRow(c, orgId);

        sender.sendNextBatch(c);

        CampaignRecipient skipped = recipients.findById(unproven.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        assertThat(sentTo()).isEmpty();
    }
}
