package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceAssignment;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The composer preview subtracts the same send-path skips RecipientMaterializer applies after SendGate. */
@IminIntegrationTest
class CampaignPreviewSendPathTest {

    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired SegmentRepository segments;
    @Autowired AudienceExperimentRepository experiments;
    @Autowired AudienceAssignmentRepository assignments;
    @Autowired AudiencePlanProperties props;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;

    private Organization org;
    private User owner;

    private AuthPrincipal owner() {
        return fx.principal(owner);
    }

    private UUID newOrg() {
        org = fx.org();
        owner = fx.owner(org);
        return org.getId();
    }

    // Explicit basis on the membership but no consent record: SendGate-sendable, never ConsentGate-mailable.
    private Membership member(UUID orgId) {
        return member(orgId, "subscribed");
    }

    private Membership member(UUID orgId, String consentStatus) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(fx.email("pv"));
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus(consentStatus);
        m.setConsentBasis("explicit");
        return memberships.save(m);
    }

    private Campaign campaign(UUID orgId, UUID eventId, String origin, String status, UUID segmentId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Preview");
        c.setStatus(status);
        c.setOrigin(origin);
        c.setEventId(eventId);
        c.setSegmentId(segmentId);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    /** A static segment of the given members plus one unsubscribed member the real SendGate excludes. */
    private UUID segmentOf(UUID orgId, List<Membership> members) {
        List<String> ids = new ArrayList<>(members.stream().map(m -> "\"" + m.getMembershipId() + "\"").toList());
        ids.add("\"" + member(orgId, "unsubscribed").getMembershipId() + "\"");
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        seg.setName("Preview " + UUID.randomUUID());
        seg.setKind("static");
        seg.setSnapshotIds("[" + String.join(",", ids) + "]");
        return segments.save(seg).getId();
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
            r.setCampaignId(campaign(orgId, eventId, "manual", "sent", null).getId());
            r.setMembershipId(membershipId);
            r.setEmail(fx.email("prior"));
            r.setStatus("sent");
            r.setLastEventAt(Instant.now().minus(1, ChronoUnit.DAYS));
            recipients.save(r);
        }
    }

    /** The real SendGate's own exclusion (the unsubscribed member) passes through unchanged. */
    private static void assertSendGateCountsKept(PreviewAudienceResponse r) {
        assertThat(r.excluded().unsubscribed()).isEqualTo(1);
        assertThat(r.excluded().noEmail()).isZero();
        assertThat(r.excluded().noBasis()).isZero();
        assertThat(r.excluded().marketingSuppressed()).isZero();
        assertThat(r.excluded().deliverabilitySuppressed()).isZero();
        assertThat(r.excluded().noPhone()).isZero();
    }

    @Test
    void eventCampaign_countsHoldoutEventCapAndMonthlyCap_andSendableDropsByThem() {
        UUID orgId = newOrg();
        Event event = fx.event(org, owner, EventStatus.LIVE, Instant.now().plus(7, ChronoUnit.DAYS));
        Membership clean = member(orgId);
        Membership held = member(orgId);
        Membership eventCapped = member(orgId);
        Membership monthCapped = member(orgId);
        holdOut(orgId, event.getId(), held.getMembershipId());
        sentBy(orgId, event.getId(), eventCapped.getMembershipId(), 2);
        sentBy(orgId, null, monthCapped.getMembershipId(), 4);
        UUID seg = segmentOf(orgId, List.of(clean, held, eventCapped, monthCapped));
        Campaign c = campaign(orgId, event.getId(), "manual", "draft", seg);

        PreviewAudienceResponse r = service.previewAudience(owner(), c.getId());

        assertThat(r.sendable()).isEqualTo(1);
        assertThat(r.excluded().experimentHoldout()).isEqualTo(1);
        assertThat(r.excluded().eventCap()).isEqualTo(1);
        assertThat(r.excluded().monthlyCap()).isEqualTo(1);
        assertThat(r.excluded().consentGate()).isZero();
        assertSendGateCountsKept(r);
    }

    @Test
    void manualCampaignWithoutEvent_flagOff_isTheSendGateCount() {
        UUID orgId = newOrg();
        UUID seg = segmentOf(orgId, List.of(member(orgId), member(orgId)));
        Campaign c = campaign(orgId, null, "manual", "draft", seg);

        PreviewAudienceResponse r = service.previewAudience(owner(), c.getId());

        assertThat(r.sendable()).isEqualTo(2);
        assertThat(r.excluded().experimentHoldout()).isZero();
        assertThat(r.excluded().eventCap()).isZero();
        assertThat(r.excluded().monthlyCap()).isZero();
        assertThat(r.excluded().consentGate()).isZero();
        assertSendGateCountsKept(r);
    }

    @Test
    void manualCampaign_consentGateAllCampaignsOn_countsConsentGateSkips() {
        flips.set(props, "consentGateAllCampaigns", true);
        UUID orgId = newOrg();
        UUID seg = segmentOf(orgId, List.of(member(orgId), member(orgId)));
        Campaign c = campaign(orgId, null, "manual", "draft", seg);

        PreviewAudienceResponse r = service.previewAudience(owner(), c.getId());

        assertThat(r.sendable()).isZero();
        assertThat(r.excluded().consentGate()).isEqualTo(2);
        assertSendGateCountsKept(r);
    }

    @Test
    void audiencePlanCampaign_flagOff_stillCountsConsentGateSkips() {
        UUID orgId = newOrg();
        UUID seg = segmentOf(orgId, List.of(member(orgId)));
        Campaign c = campaign(orgId, null, "audience_plan", "draft", seg);

        PreviewAudienceResponse r = service.previewAudience(owner(), c.getId());

        assertThat(r.sendable()).isZero();
        assertThat(r.excluded().consentGate()).isEqualTo(1);
    }

    @Test
    void noSegment_isAllZero() {
        UUID orgId = newOrg();
        Campaign c = campaign(orgId, null, "manual", "draft", null);

        PreviewAudienceResponse r = service.previewAudience(owner(), c.getId());

        assertThat(r).isEqualTo(new PreviewAudienceResponse(0,
                new PreviewAudienceResponse.Excluded(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
    }
}
