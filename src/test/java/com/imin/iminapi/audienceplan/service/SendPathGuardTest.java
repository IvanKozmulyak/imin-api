package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceAssignment;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrderFixtures;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** One test per skip reason and per no-op branch of the send-time guard, over the real ConsentGate. */
@IminIntegrationTest
// Rolled back per test: committed experiments at a fixed date would reach the global outcome collector.
@Transactional
class SendPathGuardTest {

    static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @Autowired SendPathGuard guard;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired ConsentRecordRepository consents;
    @Autowired AudienceExperimentRepository experiments;
    @Autowired AudienceAssignmentRepository assignments;
    @Autowired AudiencePlanProperties props;
    @Autowired AudiencePlanLogic logic;
    @Autowired PropertyFlips flips;
    @Autowired Clock clock;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired SegmentRepository segments;

    UUID orgId;
    UUID eventId;

    @BeforeEach
    void setUp() {
        // Experiments reference a real event (V154 FK); its org is the test org.
        var event = realEvent();
        orgId = event.getOrgId();
        eventId = event.getId();
    }

    private com.imin.iminapi.model.Event realEvent() {
        return OrderFixtures.event(orgRepo, userRepo, eventRepo, "Guard", NOW.plus(7, ChronoUnit.DAYS));
    }

    /** Subscribed with no consent record: the gate excludes it. */
    private UUID member() {
        return member(orgId);
    }

    private UUID member(UUID org) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail("guard-" + UUID.randomUUID() + "@example.com");
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(org);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m).getMembershipId();
    }

    /** Checkout consent under the shipped organizer-named text, ten days before the gate's clock: mailable. */
    private UUID provenMember() {
        UUID mid = member();
        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(mid);
        r.setStatus("subscribed");
        r.setLawfulBasis("explicit");
        r.setSource("checkout");
        r.setProofText("proof");
        r.setTextVersion(logic.logic().legal().organizerNamedTextVersions().iterator().next());
        r.setOccurredAt(clock.instant().minus(10, ChronoUnit.DAYS));
        consents.save(r);
        return mid;
    }

    private UUID objectedMember() {
        UUID mid = provenMember();
        Membership m = memberships.findByIdAndOrgId(mid, orgId).orElseThrow();
        m.setObjectedProfiling(true);
        memberships.save(m);
        return mid;
    }

    /** A membership of another org: the gate returns no verdict for it. */
    private UUID unknownMember() {
        return member(realEvent().getOrgId());
    }

    enum Kind { PROVEN, UNPROVEN, OBJECTED, UNKNOWN }

    private UUID member(Kind kind) {
        return switch (kind) {
            case PROVEN -> provenMember();
            case UNPROVEN -> member();
            case OBJECTED -> objectedMember();
            case UNKNOWN -> unknownMember();
        };
    }

    private Campaign campaign(UUID event, String origin) {
        return campaign(orgId, event, origin);
    }

    private Campaign campaign(UUID org, UUID event, String origin) {
        return campaign(org, event, origin, "email");
    }

    private Campaign campaign(UUID org, UUID event, String origin, String channel) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org);
        c.setChannel(channel);
        c.setName("c");
        c.setStatus("draft");
        c.setOrigin(origin);
        c.setEventId(event);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(NOW);
        c.setUpdatedAt(NOW);
        return campaigns.save(c);
    }

    private void sent(UUID event, UUID membershipId, String status, Instant at) {
        sent(orgId, event, membershipId, status, at, "email");
    }

    private void sent(UUID event, UUID membershipId, String status, Instant at, String channel) {
        sent(orgId, event, membershipId, status, at, channel);
    }

    private void sentForOrg(UUID org, UUID event, UUID membershipId, String status, Instant at) {
        sent(org, event, membershipId, status, at, "email");
    }

    private void sent(UUID org, UUID event, UUID membershipId, String status, Instant at, String channel) {
        Campaign prior = campaign(org, event, "manual", channel);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(prior.getId());
        r.setMembershipId(membershipId);
        r.setEmail("guard-r-" + UUID.randomUUID() + "@example.com");
        r.setStatus(status);
        r.setLastEventAt(at);
        recipients.save(r);
    }

    private void assign(UUID org, UUID event, String arm, UUID membershipId) {
        AudienceExperiment e = new AudienceExperiment();
        e.setOrgId(org);
        e.setEventId(event);
        e.setArm(arm);
        e.setSeed(42L);
        e.setMembers(1);
        e = experiments.save(e);
        AudienceAssignment a = new AudienceAssignment();
        a.setExperimentId(e.getId());
        a.setMembershipId(membershipId);
        a.setArm(arm);
        a.setAssignedAt(NOW);
        assignments.save(a);
    }

    @Test
    void holdoutOfTheEventIsSkippedForAManualCampaign() {
        UUID held = member();
        assign(orgId, eventId, "holdout", held);

        Map<UUID, String> out = guard.skipReasons(campaign(eventId, "manual"), List.of(held), NOW);

        assertThat(out).containsExactly(Map.entry(held, SendPathGuard.EXPERIMENT_HOLDOUT));
    }

    @Test
    void holdoutOfAnotherEventIsNotSkipped() {
        UUID held = member();
        assign(orgId, realEvent().getId(), "holdout", held);

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(held), NOW)).isEmpty();
    }

    @Test
    void launchArmMemberIsNotSkipped() {
        UUID invited = member();
        assign(orgId, eventId, "launch", invited);

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(invited), NOW)).isEmpty();
    }

    @Test
    void anotherOrgsHoldoutForTheSameEventIdIsNotSkipped() {
        UUID held = member();
        assign(UUID.randomUUID(), eventId, "holdout", held);

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(held), NOW)).isEmpty();
    }

    @Test
    void emptyAssignmentsSkipNobody() {
        UUID a = member();
        UUID b = member();

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(a, b), NOW)).isEmpty();
    }

    @Test
    void twoSendsAboutTheEventHitTheEventCap() {
        UUID twice = member();
        sent(eventId, twice, "sent", NOW.minus(40, ChronoUnit.DAYS));
        sent(eventId, twice, "delivered", NOW.minus(35, ChronoUnit.DAYS));

        Map<UUID, String> out = guard.skipReasons(campaign(eventId, "manual"), List.of(twice), NOW);

        assertThat(out).containsExactly(Map.entry(twice, SendPathGuard.EVENT_CAP));
    }

    @Test
    void oneSendAboutTheEventIsUnderTheEventCap() {
        UUID once = member();
        sent(eventId, once, "sent", NOW.minus(1, ChronoUnit.DAYS));

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(once), NOW)).isEmpty();
    }

    @Test
    void rowsThatNeverLeftAreNotSendsForTheEventCap() {
        UUID m = member();
        sent(eventId, m, "sent", NOW.minus(1, ChronoUnit.DAYS));
        sent(eventId, m, "skipped", NOW.minus(1, ChronoUnit.DAYS));
        sent(eventId, m, "unsubscribed", NOW.minus(1, ChronoUnit.DAYS));

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(m), NOW)).isEmpty();
    }

    @Test
    void anSmsRowDoesNotCountTowardsTheEventCap() {
        UUID m = member();
        sent(eventId, m, "sent", NOW.minus(1, ChronoUnit.DAYS), "sms");
        sent(eventId, m, "sent", NOW.minus(2, ChronoUnit.DAYS), "sms");

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(m), NOW)).isEmpty();
    }

    @Test
    void anotherOrgsCampaignForTheSameEventIdDoesNotCountTowardsTheEventCap() {
        UUID m = member();
        UUID otherOrg = UUID.randomUUID();
        sentForOrg(otherOrg, eventId, m, "sent", NOW.minus(1, ChronoUnit.DAYS));
        sentForOrg(otherOrg, eventId, m, "sent", NOW.minus(2, ChronoUnit.DAYS));

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(m), NOW)).isEmpty();
    }

    @Test
    void fourSendsInThirtyDaysHitTheMonthlyCap() {
        UUID busy = member();
        for (int i = 0; i < 4; i++) sent(UUID.randomUUID(), busy, "sent", NOW.minus(i + 1, ChronoUnit.DAYS));

        Map<UUID, String> out = guard.skipReasons(campaign(eventId, "manual"), List.of(busy), NOW);

        assertThat(out).containsExactly(Map.entry(busy, SendPathGuard.MONTHLY_CAP));
    }

    @Test
    void threeSendsInThirtyDaysAreUnderTheMonthlyCap() {
        UUID m = member();
        for (int i = 0; i < 3; i++) sent(UUID.randomUUID(), m, "sent", NOW.minus(i + 1, ChronoUnit.DAYS));

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(m), NOW)).isEmpty();
    }

    @Test
    void aSendOlderThanThirtyDaysDoesNotCountTowardsTheMonthlyCap() {
        UUID m = member();
        for (int i = 0; i < 3; i++) sent(UUID.randomUUID(), m, "sent", NOW.minus(i + 1, ChronoUnit.DAYS));
        sent(UUID.randomUUID(), m, "sent", NOW.minus(31, ChronoUnit.DAYS));

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(m), NOW)).isEmpty();
    }

    @Test
    void anSmsRowDoesNotCountTowardsTheMonthlyCap() {
        UUID m = member();
        for (int i = 0; i < 4; i++) sent(UUID.randomUUID(), m, "sent", NOW.minus(i + 1, ChronoUnit.DAYS), "sms");

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(m), NOW)).isEmpty();
    }

    @Test
    void holdoutWinsOverTheCaps() {
        UUID m = member();
        assign(orgId, eventId, "holdout", m);
        sent(eventId, m, "sent", NOW.minus(1, ChronoUnit.DAYS));
        sent(eventId, m, "sent", NOW.minus(2, ChronoUnit.DAYS));

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(m), NOW))
                .containsExactly(Map.entry(m, SendPathGuard.EXPERIMENT_HOLDOUT));
    }

    @Test
    void campaignWithoutAnEventIsUnaffected() {
        UUID m = member();
        assign(orgId, eventId, "holdout", m);
        for (int i = 0; i < 4; i++) sent(eventId, m, "sent", NOW.minus(i + 1, ChronoUnit.DAYS));

        assertThat(guard.skipReasons(campaign(null, "manual"), List.of(m), NOW)).isEmpty();
    }

    @Test
    void idsAboveOneThousandAreChunkedAndStillChecked() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1500; i++) ids.add(UUID.randomUUID());
        UUID held = member();
        ids.set(1200, held); // second chunk: proves the query loop does not stop after the first 1000
        assign(orgId, eventId, "holdout", held);

        Map<UUID, String> out = guard.skipReasons(campaign(eventId, "manual"), ids, NOW);

        assertThat(out).containsExactly(Map.entry(held, SendPathGuard.EXPERIMENT_HOLDOUT));
    }

    static Stream<Arguments> consentGateRows() {
        return Stream.of(
                // origin, about the event, segment origin, consent-gate-all-campaigns, member, skipped by the gate
                Arguments.of("manual", true, null, false, Kind.UNPROVEN, false),
                Arguments.of("momentum", false, Segment.ORIGIN_MOMENTUM, false, Kind.OBJECTED, true),
                Arguments.of("momentum", false, Segment.ORIGIN_ORGANIZER, false, Kind.UNPROVEN, false),
                Arguments.of("audience_plan", true, null, false, Kind.UNPROVEN, true),
                Arguments.of("audience_plan", true, null, false, Kind.PROVEN, false),
                Arguments.of("audience_plan", true, null, false, Kind.UNKNOWN, true),
                Arguments.of("audience_plan", false, null, false, Kind.UNPROVEN, true),
                Arguments.of("manual", false, null, false, Kind.UNPROVEN, false),
                Arguments.of("manual", true, null, true, Kind.UNPROVEN, true),
                Arguments.of("manual", false, null, true, Kind.UNPROVEN, true),
                Arguments.of("manual", false, null, true, Kind.PROVEN, false),
                Arguments.of("manual", false, null, true, Kind.UNKNOWN, true),
                Arguments.of("momentum", true, null, true, Kind.UNPROVEN, true),
                Arguments.of("audience_plan", true, null, true, Kind.UNPROVEN, true));
    }

    @ParameterizedTest(name = "{0} event={1} segment={2} allCampaigns={3} {4} -> skipped={5}")
    @MethodSource("consentGateRows")
    void consentGate_appliesByOriginSegmentAndFlag(String origin, boolean aboutEvent, String segmentOrigin,
                                                   boolean allCampaigns, Kind kind, boolean skipped) {
        flips.set(props, "consentGateAllCampaigns", allCampaigns);
        UUID m = member(kind);
        Campaign c = campaign(aboutEvent ? eventId : null, origin);
        if (segmentOrigin != null) {
            c.setSegmentId(segment(segmentOrigin));
            c = campaigns.save(c);
        }

        Map<UUID, String> out = guard.skipReasons(c, List.of(m), NOW);

        if (skipped) assertThat(out).containsExactly(Map.entry(m, SendPathGuard.CONSENT_GATE));
        else assertThat(out).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"audience_plan, false", "manual, true"})
    void capsWinOverConsentGate(String origin, boolean allCampaigns) {
        flips.set(props, "consentGateAllCampaigns", allCampaigns);
        UUID m = member();
        sent(eventId, m, "sent", NOW.minus(1, ChronoUnit.DAYS));
        sent(eventId, m, "sent", NOW.minus(2, ChronoUnit.DAYS));

        assertThat(guard.skipReasons(campaign(eventId, origin), List.of(m), NOW))
                .containsExactly(Map.entry(m, SendPathGuard.EVENT_CAP));
    }

    @ParameterizedTest
    @CsvSource({"audience_plan, true, false", "manual, false, true"})
    void noIdsMeansNoSkips(String origin, boolean aboutEvent, boolean allCampaigns) {
        flips.set(props, "consentGateAllCampaigns", allCampaigns);

        assertThat(guard.skipReasons(campaign(aboutEvent ? eventId : null, origin), List.of(), NOW)).isEmpty();
    }

    private UUID segment(String origin) {
        Segment s = new Segment();
        s.setOrgId(orgId);
        s.setName("Momentum target");
        s.setKind("static");
        s.setOrigin(origin);
        s.setCreatedAt(NOW);
        s.setUpdatedAt(NOW);
        return segments.save(s).getId();
    }
}
