package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceAssignment;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.audienceplan.service.MomentumPlanTarget;
import com.imin.iminapi.audienceplan.service.SendPathGuard;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/** Materialisation writes guarded members as skipped rows with the guard's reason, before the frequency floor. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class SendPathGuardMaterializeTest {

    @Autowired RecipientMaterializer materializer;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired AudienceExperimentRepository experiments;
    @Autowired AudienceAssignmentRepository assignments;
    @MockitoBean SendGateService sendGate;
    @MockitoBean SegmentService segmentService;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired AudiencePlanProperties props;
    @Autowired MomentumPlanTarget planTarget;

    @AfterEach
    void resetFlag() {
        props.setConsentGateAllCampaigns(false);
    }

    // Latest subscribing email consent from a checkout; the version decides proven vs legacy_unproven.
    private Membership checkoutConsent(Membership m, String textVersion) {
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(m.getMembershipId());
        r.setStatus("subscribed");
        r.setLawfulBasis("explicit");
        r.setSource("checkout");
        r.setProofText("proof");
        r.setTextVersion(textVersion);
        r.setOccurredAt(Instant.now().minus(10, ChronoUnit.DAYS));
        consentRecords.save(r);
        return m;
    }

    private Campaign campaign(UUID orgId, UUID eventId, String origin) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Materialize");
        c.setStatus("sending");
        c.setOrigin(origin);
        c.setEventId(eventId);
        c.setSegmentId(UUID.randomUUID());
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    // Explicit basis on the membership but no consent record: SendGate-style data, never plan-mailable.
    private Membership member(UUID orgId) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail("mat-" + UUID.randomUUID() + "@example.com");
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m);
    }

    private void segmentOf(UUID orgId, List<Membership> members) {
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        when(segmentService.requireSegmentForOrg(any(), any())).thenReturn(seg);
        when(segmentService.resolveMembers(any(), any())).thenReturn(members);
        when(sendGate.evaluate(any(), anyCollection())).thenReturn(new SendGateService.GateResult(
                members.stream().map(Membership::getMembershipId).toList(), List.of()));
    }

    private CampaignRecipient rowOf(Campaign c, Membership m) {
        return recipients.findByCampaignId(c.getId(), org.springframework.data.domain.PageRequest.of(0, 50)).stream()
                .filter(r -> m.getMembershipId().equals(r.getMembershipId()))
                .findFirst().orElseThrow();
    }

    @Test
    void holdoutMemberOfTheEventIsMaterializedAsSkippedForAManualCampaign() {
        UUID orgId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Membership keep = member(orgId);
        Membership held = member(orgId);
        AudienceExperiment e = new AudienceExperiment();
        e.setOrgId(orgId);
        e.setEventId(eventId);
        e.setArm("holdout");
        e.setSeed(1L);
        e.setMembers(1);
        e = experiments.save(e);
        AudienceAssignment a = new AudienceAssignment();
        a.setExperimentId(e.getId());
        a.setMembershipId(held.getMembershipId());
        a.setArm("holdout");
        a.setAssignedAt(Instant.now());
        assignments.save(a);
        segmentOf(orgId, List.of(keep, held));
        Campaign c = campaign(orgId, eventId, "manual");

        materializer.materialize(c);

        assertThat(rowOf(c, keep).getStatus()).isEqualTo("pending");
        CampaignRecipient skipped = rowOf(c, held);
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.EXPERIMENT_HOLDOUT);
        Campaign reloaded = campaigns.findByIdAndOrgId(c.getId(), orgId).orElseThrow();
        assertThat(reloaded.getRecipientCount()).isEqualTo(1);
        assertThat(reloaded.getExcludedCount()).isEqualTo(1);
        assertThat(reloaded.getExclusionSummary()).isEqualTo("{\"experiment_holdout\":1}");
    }

    @Test
    void realConsentGateSkipsAnUnprovenMemberForAPlanCampaignOnly() {
        UUID orgId = UUID.randomUUID();
        Membership unproven = member(orgId);
        segmentOf(orgId, List.of(unproven));
        Campaign plan = campaign(orgId, null, "audience_plan");
        Campaign manual = campaign(orgId, null, "manual");

        materializer.materialize(plan);
        materializer.materialize(manual);

        CampaignRecipient planRow = rowOf(plan, unproven);
        assertThat(planRow.getStatus()).isEqualTo("skipped");
        assertThat(planRow.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        assertThat(campaigns.findByIdAndOrgId(plan.getId(), orgId).orElseThrow().getExclusionSummary())
                .isEqualTo("{\"consent_gate\":1}");
        assertThat(rowOf(manual, unproven).getStatus()).isEqualTo("pending");
    }

    @Test
    void flagOffManualCampaignReachesALegacyUnprovenMember() {
        UUID orgId = UUID.randomUUID();
        Membership legacy = checkoutConsent(member(orgId), null);
        segmentOf(orgId, List.of(legacy));
        Campaign c = campaign(orgId, null, "manual");

        materializer.materialize(c);

        assertThat(rowOf(c, legacy).getStatus()).isEqualTo("pending");
        Campaign reloaded = campaigns.findByIdAndOrgId(c.getId(), orgId).orElseThrow();
        assertThat(reloaded.getRecipientCount()).isEqualTo(1);
        assertThat(reloaded.getExcludedCount()).isZero();
    }

    @Test
    void flagOnManualCampaignSkipsALegacyUnprovenMemberAndKeepsAProvenOne() {
        props.setConsentGateAllCampaigns(true);
        UUID orgId = UUID.randomUUID();
        Membership legacy = checkoutConsent(member(orgId), null);
        Membership proven = checkoutConsent(member(orgId), "checkout-org-named-2026-09");
        segmentOf(orgId, List.of(legacy, proven));
        Campaign c = campaign(orgId, null, "manual");

        materializer.materialize(c);

        CampaignRecipient skipped = rowOf(c, legacy);
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        assertThat(rowOf(c, proven).getStatus()).isEqualTo("pending");
        Campaign reloaded = campaigns.findByIdAndOrgId(c.getId(), orgId).orElseThrow();
        assertThat(reloaded.getRecipientCount()).isEqualTo(1);
        assertThat(reloaded.getExcludedCount()).isEqualTo(1);
        assertThat(reloaded.getExclusionSummary()).isEqualTo("{\"consent_gate\":1}");
    }

    @Test
    void flagOnMomentumDraftSkipsALegacyUnprovenMember() {
        props.setConsentGateAllCampaigns(true);
        UUID orgId = UUID.randomUUID();
        Membership legacy = checkoutConsent(member(orgId), null);
        segmentOf(orgId, List.of(legacy));
        Campaign c = campaign(orgId, UUID.randomUUID(), "momentum");

        materializer.materialize(c);

        CampaignRecipient skipped = rowOf(c, legacy);
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        assertThat(campaigns.findByIdAndOrgId(c.getId(), orgId).orElseThrow().getExclusionSummary())
                .isEqualTo("{\"consent_gate\":1}");
    }

    @Test
    void momentumDraftOnAPlanSnapshot_skipsAMemberWhoObjectedToProfilingAfterTheSnapshot() {
        UUID orgId = UUID.randomUUID();
        Membership stays = checkoutConsent(member(orgId), "checkout-org-named-2026-09");
        Membership objects = checkoutConsent(member(orgId), "checkout-org-named-2026-09");
        UUID snapshotId = planTarget.snapshot(orgId, "Night Kit", new MomentumPlanTarget.Target("loyal", "same",
                List.of(stays.getMembershipId(), objects.getMembershipId())));
        objects.setObjectedProfiling(true);
        memberships.save(objects);
        segmentOf(orgId, List.of(stays, objects));
        Campaign c = campaign(orgId, UUID.randomUUID(), "momentum");
        c.setSegmentId(snapshotId);
        c = campaigns.save(c);

        materializer.materialize(c);

        CampaignRecipient skipped = rowOf(c, objects);
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        assertThat(rowOf(c, stays).getStatus()).isEqualTo("pending");
        Campaign reloaded = campaigns.findByIdAndOrgId(c.getId(), orgId).orElseThrow();
        assertThat(reloaded.getRecipientCount()).isEqualTo(1);
        assertThat(reloaded.getExcludedCount()).isEqualTo(1);
        assertThat(reloaded.getExclusionSummary()).isEqualTo("{\"consent_gate\":1}");
    }

    @Test
    void flagOffMomentumDraftOnANonSnapshotSegment_reachesAMemberWhoObjectedToProfiling() {
        UUID orgId = UUID.randomUUID();
        Membership objects = checkoutConsent(member(orgId), "checkout-org-named-2026-09");
        objects.setObjectedProfiling(true);
        memberships.save(objects);
        segmentOf(orgId, List.of(objects));
        Campaign c = campaign(orgId, UUID.randomUUID(), "momentum");

        materializer.materialize(c);

        assertThat(rowOf(c, objects).getStatus()).isEqualTo("pending");
    }
}
