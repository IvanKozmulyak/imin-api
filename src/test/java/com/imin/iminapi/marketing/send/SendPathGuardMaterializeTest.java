package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceAssignment;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.audienceplan.service.MomentumPlanTarget;
import com.imin.iminapi.audienceplan.service.SendPathGuard;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Materialisation over a real static segment and the real SendGate: sendable members become pending rows,
 * excluded and guarded ones skipped rows with their reason, the guard before the frequency floor.
 */
@IminIntegrationTest
class SendPathGuardMaterializeTest {

    @Autowired RecipientMaterializer materializer;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired SegmentRepository segments;
    @Autowired AudienceExperimentRepository experiments;
    @Autowired AudienceAssignmentRepository assignments;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired AudiencePlanProperties props;
    @Autowired MomentumPlanTarget planTarget;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

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

    private Campaign campaign(UUID orgId, UUID eventId, String origin, UUID segmentId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Materialize");
        c.setStatus("sending");
        c.setOrigin(origin);
        c.setEventId(eventId);
        c.setSegmentId(segmentId);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    // Explicit basis on the membership but no consent record: SendGate-sendable, never plan-mailable.
    private Membership member(UUID orgId) {
        return member(orgId, "explicit");
    }

    private Membership member(UUID orgId, String consentBasis) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(fx.email("mat"));
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis(consentBasis);
        return memberships.save(m);
    }

    /** A static segment of exactly these members, resolved by the real SegmentService. */
    private UUID segmentOf(UUID orgId, List<Membership> members) {
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        seg.setName("Materialize " + UUID.randomUUID());
        seg.setKind("static");
        seg.setSnapshotIds(members.stream().map(m -> "\"" + m.getMembershipId() + "\"").toList().toString());
        return segments.save(seg).getId();
    }

    private CampaignRecipient rowOf(Campaign c, Membership m) {
        return recipients.findByCampaignId(c.getId(), PageRequest.of(0, 50)).stream()
                .filter(r -> m.getMembershipId().equals(r.getMembershipId()))
                .findFirst().orElseThrow();
    }

    private Campaign reload(Campaign c) {
        return campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();
    }

    @Test
    void gateExcludedMemberIsASkippedRowCarryingTheReason_andASecondRunIsANoOp() {
        UUID orgId = newOrgId();
        Membership sendable = member(orgId);
        Membership excluded = member(orgId, null);   // the real gate's no_lawful_basis clause
        Campaign c = campaign(orgId, null, "manual", segmentOf(orgId, List.of(sendable, excluded)));

        materializer.materialize(c);

        assertThat(recipients.countByCampaignId(c.getId())).isEqualTo(2L);
        assertThat(rowOf(c, sendable).getStatus()).isEqualTo("pending");
        CampaignRecipient skipped = rowOf(c, excluded);
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo("no_lawful_basis");
        Campaign reloaded = reload(c);
        assertThat(reloaded.getExclusionSummary()).isEqualTo("{\"no_lawful_basis\":1}");
        assertThat(reloaded.getExcludedCount()).isEqualTo(1);
        assertThat(reloaded.getRecipientCount()).isEqualTo(1);

        // Idempotent: a second materialize (crash-resume) must not duplicate.
        materializer.materialize(c);
        assertThat(recipients.countByCampaignId(c.getId())).isEqualTo(2L);
    }

    @Test
    void frequencyCappedSendableMemberIsMaterializedAsSkipped() {
        UUID orgId = newOrgId();
        Membership member = member(orgId);
        // A recent send to this member on a prior campaign trips the frequency floor.
        Campaign prior = campaign(orgId, null, "manual", null);
        CampaignRecipient recent = new CampaignRecipient();
        recent.setId(UUID.randomUUID());
        recent.setCampaignId(prior.getId());
        recent.setMembershipId(member.getMembershipId());
        recent.setEmail(fx.email("freq"));
        recent.setStatus("sent");
        recent.setLastEventAt(Instant.now().minus(1, ChronoUnit.HOURS));
        recipients.save(recent);
        Campaign c = campaign(orgId, null, "manual", segmentOf(orgId, List.of(member)));

        materializer.materialize(c);

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isZero();
        assertThat(rowOf(c, member).getSkipReason()).isEqualTo("frequency_capped");
        Campaign reloaded = reload(c);
        assertThat(reloaded.getRecipientCount()).isZero();
        assertThat(reloaded.getExcludedCount()).isEqualTo(1);
        assertThat(reloaded.getExclusionSummary()).isEqualTo("{\"frequency_capped\":1}");
    }

    @Test
    void holdoutMemberOfTheEventIsMaterializedAsSkippedForAManualCampaign() {
        // A real event: experiments reference events (V154 FK).
        Organization org = fx.org();
        orgIds.add(org.getId());
        Event event = fx.event(org, fx.owner(org), EventStatus.LIVE, Instant.now().plus(7, ChronoUnit.DAYS));
        UUID orgId = org.getId();
        Membership keep = member(orgId);
        Membership held = member(orgId);
        AudienceExperiment e = new AudienceExperiment();
        e.setOrgId(orgId);
        e.setEventId(event.getId());
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
        Campaign c = campaign(orgId, event.getId(), "manual", segmentOf(orgId, List.of(keep, held)));

        materializer.materialize(c);

        assertThat(rowOf(c, keep).getStatus()).isEqualTo("pending");
        CampaignRecipient skipped = rowOf(c, held);
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.EXPERIMENT_HOLDOUT);
        Campaign reloaded = reload(c);
        assertThat(reloaded.getRecipientCount()).isEqualTo(1);
        assertThat(reloaded.getExcludedCount()).isEqualTo(1);
        assertThat(reloaded.getExclusionSummary()).isEqualTo("{\"experiment_holdout\":1}");
    }

    @Test
    void realConsentGateSkipsAnUnprovenMemberForAPlanCampaignOnly() {
        UUID orgId = newOrgId();
        Membership unproven = member(orgId);
        UUID segmentId = segmentOf(orgId, List.of(unproven));
        Campaign plan = campaign(orgId, null, "audience_plan", segmentId);
        Campaign manual = campaign(orgId, null, "manual", segmentId);

        materializer.materialize(plan);
        materializer.materialize(manual);

        CampaignRecipient planRow = rowOf(plan, unproven);
        assertThat(planRow.getStatus()).isEqualTo("skipped");
        assertThat(planRow.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        assertThat(reload(plan).getExclusionSummary()).isEqualTo("{\"consent_gate\":1}");
        assertThat(rowOf(manual, unproven).getStatus()).isEqualTo("pending");
    }

    @Test
    void flagOffManualCampaignReachesALegacyUnprovenMember() {
        UUID orgId = newOrgId();
        Membership legacy = checkoutConsent(member(orgId), null);
        Campaign c = campaign(orgId, null, "manual", segmentOf(orgId, List.of(legacy)));

        materializer.materialize(c);

        assertThat(rowOf(c, legacy).getStatus()).isEqualTo("pending");
        Campaign reloaded = reload(c);
        assertThat(reloaded.getRecipientCount()).isEqualTo(1);
        assertThat(reloaded.getExcludedCount()).isZero();
    }

    @Test
    void flagOnManualCampaignSkipsALegacyUnprovenMemberAndKeepsAProvenOne() {
        flips.set(props, "consentGateAllCampaigns", true);
        UUID orgId = newOrgId();
        Membership legacy = checkoutConsent(member(orgId), null);
        Membership proven = checkoutConsent(member(orgId), "checkout-org-named-2026-09");
        Campaign c = campaign(orgId, null, "manual", segmentOf(orgId, List.of(legacy, proven)));

        materializer.materialize(c);

        CampaignRecipient skipped = rowOf(c, legacy);
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        assertThat(rowOf(c, proven).getStatus()).isEqualTo("pending");
        Campaign reloaded = reload(c);
        assertThat(reloaded.getRecipientCount()).isEqualTo(1);
        assertThat(reloaded.getExcludedCount()).isEqualTo(1);
        assertThat(reloaded.getExclusionSummary()).isEqualTo("{\"consent_gate\":1}");
    }

    @Test
    void flagOnMomentumDraftSkipsALegacyUnprovenMember() {
        flips.set(props, "consentGateAllCampaigns", true);
        UUID orgId = newOrgId();
        Membership legacy = checkoutConsent(member(orgId), null);
        Campaign c = campaign(orgId, UUID.randomUUID(), "momentum", segmentOf(orgId, List.of(legacy)));

        materializer.materialize(c);

        CampaignRecipient skipped = rowOf(c, legacy);
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        assertThat(reload(c).getExclusionSummary()).isEqualTo("{\"consent_gate\":1}");
    }

    @Test
    void momentumDraftOnAPlanSnapshot_skipsAMemberWhoObjectedToProfilingAfterTheSnapshot() {
        UUID orgId = newOrgId();
        Membership stays = checkoutConsent(member(orgId), "checkout-org-named-2026-09");
        Membership objects = checkoutConsent(member(orgId), "checkout-org-named-2026-09");
        UUID snapshotId = planTarget.snapshot(orgId, "Night Kit", new MomentumPlanTarget.Target("loyal", "same",
                List.of(stays.getMembershipId(), objects.getMembershipId())));
        objects.setObjectedProfiling(true);
        memberships.save(objects);
        Campaign c = campaign(orgId, UUID.randomUUID(), "momentum", snapshotId);

        materializer.materialize(c);

        CampaignRecipient skipped = rowOf(c, objects);
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo(SendPathGuard.CONSENT_GATE);
        assertThat(rowOf(c, stays).getStatus()).isEqualTo("pending");
        Campaign reloaded = reload(c);
        assertThat(reloaded.getRecipientCount()).isEqualTo(1);
        assertThat(reloaded.getExcludedCount()).isEqualTo(1);
        assertThat(reloaded.getExclusionSummary()).isEqualTo("{\"consent_gate\":1}");
    }

    @Test
    void flagOffMomentumDraftOnANonSnapshotSegment_reachesAMemberWhoObjectedToProfiling() {
        UUID orgId = newOrgId();
        Membership objects = checkoutConsent(member(orgId), "checkout-org-named-2026-09");
        objects.setObjectedProfiling(true);
        memberships.save(objects);
        Campaign c = campaign(orgId, UUID.randomUUID(), "momentum", segmentOf(orgId, List.of(objects)));

        materializer.materialize(c);

        assertThat(rowOf(c, objects).getStatus()).isEqualTo("pending");
    }
}
