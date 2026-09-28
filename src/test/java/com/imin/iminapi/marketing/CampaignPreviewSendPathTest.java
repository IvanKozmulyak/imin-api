package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceAssignment;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.OrderFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/** The composer preview subtracts the same send-path skips RecipientMaterializer applies after SendGate. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignPreviewSendPathTest {

    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired AudienceExperimentRepository experiments;
    @Autowired AudienceAssignmentRepository assignments;
    @Autowired AudiencePlanProperties props;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @MockitoBean SendGateService sendGate;
    @MockitoBean SegmentService segmentService;

    @AfterEach
    void resetFlag() {
        props.setConsentGateAllCampaigns(false);
    }

    private static AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    // Explicit basis on the membership but no consent record: SendGate-sendable, never ConsentGate-mailable.
    private Membership member(UUID orgId) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail("pv-" + UUID.randomUUID() + "@example.com");
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m);
    }

    private Campaign campaign(UUID orgId, UUID eventId, String origin, String status) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Preview");
        c.setStatus(status);
        c.setOrigin(origin);
        c.setEventId(eventId);
        c.setSegmentId(UUID.randomUUID());
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    /** SendGate passes every member and additionally excludes one extra id as no_email. */
    private void segmentOf(UUID orgId, List<Membership> members) {
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        when(segmentService.requireSegmentForOrg(any(), any())).thenReturn(seg);
        when(segmentService.resolveMembers(any(), any())).thenReturn(members);
        when(sendGate.evaluate(any(), anyCollection())).thenReturn(new SendGateService.GateResult(
                members.stream().map(Membership::getMembershipId).toList(),
                List.of(new ExclusionReason(UUID.randomUUID(), "no_email"))));
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

    private void sentBy(UUID orgId, UUID eventId, UUID membershipId, int times) {
        for (int i = 0; i < times; i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(campaign(orgId, eventId, "manual", "sent").getId());
            r.setMembershipId(membershipId);
            r.setEmail("prior@example.com");
            r.setStatus("sent");
            r.setLastEventAt(Instant.now().minus(1, ChronoUnit.DAYS));
            recipients.save(r);
        }
    }

    private static void assertSendGateCountsKept(PreviewAudienceResponse r) {
        assertThat(r.excluded().noEmail()).isEqualTo(1);
        assertThat(r.excluded().noBasis()).isZero();
        assertThat(r.excluded().unsubscribed()).isZero();
        assertThat(r.excluded().marketingSuppressed()).isZero();
        assertThat(r.excluded().deliverabilitySuppressed()).isZero();
        assertThat(r.excluded().noPhone()).isZero();
    }

    @Test
    void eventCampaign_countsHoldoutEventCapAndMonthlyCap_andSendableDropsByThem() {
        Event event = OrderFixtures.event(orgRepo, userRepo, eventRepo, "Pv", Instant.now().plus(7, ChronoUnit.DAYS));
        UUID orgId = event.getOrgId();
        Membership clean = member(orgId);
        Membership held = member(orgId);
        Membership eventCapped = member(orgId);
        Membership monthCapped = member(orgId);
        holdOut(orgId, event.getId(), held.getMembershipId());
        sentBy(orgId, event.getId(), eventCapped.getMembershipId(), 2);
        sentBy(orgId, null, monthCapped.getMembershipId(), 4);
        segmentOf(orgId, List.of(clean, held, eventCapped, monthCapped));
        Campaign c = campaign(orgId, event.getId(), "manual", "draft");

        PreviewAudienceResponse r = service.previewAudience(owner(orgId), c.getId());

        assertThat(r.sendable()).isEqualTo(1);
        assertThat(r.excluded().experimentHoldout()).isEqualTo(1);
        assertThat(r.excluded().eventCap()).isEqualTo(1);
        assertThat(r.excluded().monthlyCap()).isEqualTo(1);
        assertThat(r.excluded().consentGate()).isZero();
        assertSendGateCountsKept(r);
    }

    @Test
    void manualCampaignWithoutEvent_flagOff_isTheSendGateCount() {
        UUID orgId = UUID.randomUUID();
        List<Membership> members = new ArrayList<>(List.of(member(orgId), member(orgId)));
        segmentOf(orgId, members);
        Campaign c = campaign(orgId, null, "manual", "draft");

        PreviewAudienceResponse r = service.previewAudience(owner(orgId), c.getId());

        assertThat(r.sendable()).isEqualTo(2);
        assertThat(r.excluded().experimentHoldout()).isZero();
        assertThat(r.excluded().eventCap()).isZero();
        assertThat(r.excluded().monthlyCap()).isZero();
        assertThat(r.excluded().consentGate()).isZero();
        assertSendGateCountsKept(r);
    }

    @Test
    void manualCampaign_consentGateAllCampaignsOn_countsConsentGateSkips() {
        props.setConsentGateAllCampaigns(true);
        UUID orgId = UUID.randomUUID();
        segmentOf(orgId, List.of(member(orgId), member(orgId)));
        Campaign c = campaign(orgId, null, "manual", "draft");

        PreviewAudienceResponse r = service.previewAudience(owner(orgId), c.getId());

        assertThat(r.sendable()).isZero();
        assertThat(r.excluded().consentGate()).isEqualTo(2);
        assertSendGateCountsKept(r);
    }

    @Test
    void audiencePlanCampaign_flagOff_stillCountsConsentGateSkips() {
        UUID orgId = UUID.randomUUID();
        segmentOf(orgId, List.of(member(orgId)));
        Campaign c = campaign(orgId, null, "audience_plan", "draft");

        PreviewAudienceResponse r = service.previewAudience(owner(orgId), c.getId());

        assertThat(r.sendable()).isZero();
        assertThat(r.excluded().consentGate()).isEqualTo(1);
    }

    @Test
    void noSegment_isAllZero() {
        UUID orgId = UUID.randomUUID();
        Campaign c = campaign(orgId, null, "manual", "draft");
        c.setSegmentId(null);
        campaigns.save(c);

        PreviewAudienceResponse r = service.previewAudience(owner(orgId), c.getId());

        assertThat(r).isEqualTo(new PreviewAudienceResponse(0,
                new PreviewAudienceResponse.Excluded(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
    }
}
