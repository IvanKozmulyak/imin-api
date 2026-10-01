package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceAssignment;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.OrderFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One test per skip reason and per no-op branch of the send-time guard. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
// Rolled back per test: committed experiments at a fixed date leak into OutcomeCollector runs.
@Transactional
class SendPathGuardTest {

    static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @Autowired SendPathGuard guard;
    @Autowired CampaignRepository campaigns;
    @MockitoSpyBean CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired AudienceExperimentRepository experiments;
    @MockitoSpyBean AudienceAssignmentRepository assignments;
    @MockitoBean ConsentGate consentGate;
    @Autowired AudiencePlanProperties props;
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

    @AfterEach
    void resetFlag() {
        props.setConsentGateAllCampaigns(false);
    }

    private UUID member() {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail("guard-" + UUID.randomUUID() + "@example.com");
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m).getMembershipId();
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
        r.setEmail("x@example.com");
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
        verify(consentGate, never()).reasons(any(), anyCollection());
    }

    @Test
    void campaignWithoutAnEventNeverQueriesAssignmentsOrRecipients() {
        UUID m = member();

        guard.skipReasons(campaign(null, "manual"), List.of(m), NOW);

        verify(assignments, never()).findHeldOut(any(), any(), any());
        verify(recipients, never()).countEventSendsByMembership(any(), any(), any());
        verify(recipients, never()).countRecentSendsByMembership(any(), any());
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

    @Test
    void manualEventCampaignNeverAsksConsentGate() {
        UUID m = member();

        guard.skipReasons(campaign(eventId, "manual"), List.of(m), NOW);

        verify(consentGate, never()).reasons(any(), anyCollection());
    }

    @Test
    void momentumCampaignOnAMomentumSegmentSkipsAMemberConsentGateExcludes() {
        UUID legacy = member();
        when(consentGate.reasons(any(), anyCollection()))
                .thenReturn(Map.of(legacy, Optional.of(ConsentGate.OBJECTED)));
        Campaign c = campaign(null, "momentum");
        c.setSegmentId(segment(Segment.ORIGIN_MOMENTUM));
        c = campaigns.save(c);

        assertThat(guard.skipReasons(c, List.of(legacy), NOW))
                .containsExactly(Map.entry(legacy, SendPathGuard.CONSENT_GATE));
        verify(consentGate).reasons(orgId, List.of(legacy));
    }

    @Test
    void momentumCampaignOnAnOrganizerSegmentNeverAsksConsentGate() {
        UUID m = member();
        Campaign c = campaign(null, "momentum");
        c.setSegmentId(segment(Segment.ORIGIN_ORGANIZER));
        c = campaigns.save(c);

        assertThat(guard.skipReasons(c, List.of(m), NOW)).isEmpty();
        verify(consentGate, never()).reasons(any(), anyCollection());
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

    @Test
    void planCampaignSkipsAMemberConsentGateExcludes() {
        UUID legacy = member();
        when(consentGate.reasons(any(), anyCollection()))
                .thenReturn(Map.of(legacy, Optional.of(ConsentGate.LEGACY_UNPROVEN)));

        Map<UUID, String> out = guard.skipReasons(campaign(eventId, "audience_plan"), List.of(legacy), NOW);

        assertThat(out).containsExactly(Map.entry(legacy, SendPathGuard.CONSENT_GATE));
        verify(consentGate).reasons(orgId, List.of(legacy));
    }

    @Test
    void planCampaignKeepsAMemberConsentGateAdmits() {
        UUID ok = member();
        when(consentGate.reasons(any(), anyCollection())).thenReturn(Map.of(ok, Optional.empty()));

        assertThat(guard.skipReasons(campaign(eventId, "audience_plan"), List.of(ok), NOW)).isEmpty();
    }

    @Test
    void planCampaignSkipsAMemberConsentGateDoesNotKnow() {
        UUID unknown = member();
        when(consentGate.reasons(any(), anyCollection())).thenReturn(Map.of());

        assertThat(guard.skipReasons(campaign(eventId, "audience_plan"), List.of(unknown), NOW))
                .containsExactly(Map.entry(unknown, SendPathGuard.CONSENT_GATE));
    }

    @Test
    void planCampaignWithoutAnEventStillAsksConsentGate() {
        UUID legacy = member();
        when(consentGate.reasons(any(), anyCollection()))
                .thenReturn(Map.of(legacy, Optional.of(ConsentGate.NO_BASIS)));

        assertThat(guard.skipReasons(campaign(null, "audience_plan"), List.of(legacy), NOW))
                .containsExactly(Map.entry(legacy, SendPathGuard.CONSENT_GATE));
    }

    @Test
    void capsWinOverConsentGate() {
        UUID m = member();
        sent(eventId, m, "sent", NOW.minus(1, ChronoUnit.DAYS));
        sent(eventId, m, "sent", NOW.minus(2, ChronoUnit.DAYS));
        when(consentGate.reasons(any(), anyCollection()))
                .thenReturn(Map.of(m, Optional.of(ConsentGate.NO_BASIS)));

        assertThat(guard.skipReasons(campaign(eventId, "audience_plan"), List.of(m), NOW))
                .containsExactly(Map.entry(m, SendPathGuard.EVENT_CAP));
    }

    @Test
    void noIdsMeansNoSkips() {
        assertThat(guard.skipReasons(campaign(eventId, "audience_plan"), List.of(), NOW)).isEmpty();
        verify(consentGate, never()).reasons(any(), anyCollection());
    }

    @Test
    void flagOffManualCampaignWithoutAnEventReachesAnUnprovenMember() {
        UUID legacy = member();
        when(consentGate.reasons(any(), anyCollection()))
                .thenReturn(Map.of(legacy, Optional.of(ConsentGate.LEGACY_UNPROVEN)));

        assertThat(guard.skipReasons(campaign(null, "manual"), List.of(legacy), NOW)).isEmpty();
        verify(consentGate, never()).reasons(any(), anyCollection());
    }

    @Test
    void flagOnManualCampaignSkipsAnUnprovenMember() {
        props.setConsentGateAllCampaigns(true);
        UUID legacy = member();
        when(consentGate.reasons(any(), anyCollection()))
                .thenReturn(Map.of(legacy, Optional.of(ConsentGate.LEGACY_UNPROVEN)));

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(legacy), NOW))
                .containsExactly(Map.entry(legacy, SendPathGuard.CONSENT_GATE));
        verify(consentGate).reasons(orgId, List.of(legacy));
    }

    @Test
    void flagOnManualCampaignWithoutAnEventStillAsksConsentGate() {
        props.setConsentGateAllCampaigns(true);
        UUID legacy = member();
        when(consentGate.reasons(any(), anyCollection()))
                .thenReturn(Map.of(legacy, Optional.of(ConsentGate.LEGACY_UNPROVEN)));

        assertThat(guard.skipReasons(campaign(null, "manual"), List.of(legacy), NOW))
                .containsExactly(Map.entry(legacy, SendPathGuard.CONSENT_GATE));
        verify(assignments, never()).findHeldOut(any(), any(), any());
    }

    @Test
    void flagOnManualCampaignKeepsAMemberConsentGateAdmits() {
        props.setConsentGateAllCampaigns(true);
        UUID ok = member();
        when(consentGate.reasons(any(), anyCollection())).thenReturn(Map.of(ok, Optional.empty()));

        assertThat(guard.skipReasons(campaign(null, "manual"), List.of(ok), NOW)).isEmpty();
    }

    @Test
    void flagOnManualCampaignSkipsAMemberConsentGateDoesNotKnow() {
        props.setConsentGateAllCampaigns(true);
        UUID unknown = member();
        when(consentGate.reasons(any(), anyCollection())).thenReturn(Map.of());

        assertThat(guard.skipReasons(campaign(null, "manual"), List.of(unknown), NOW))
                .containsExactly(Map.entry(unknown, SendPathGuard.CONSENT_GATE));
    }

    @Test
    void flagOnMomentumDraftIsGated() {
        props.setConsentGateAllCampaigns(true);
        UUID legacy = member();
        when(consentGate.reasons(any(), anyCollection()))
                .thenReturn(Map.of(legacy, Optional.of(ConsentGate.NO_BASIS)));

        assertThat(guard.skipReasons(campaign(eventId, "momentum"), List.of(legacy), NOW))
                .containsExactly(Map.entry(legacy, SendPathGuard.CONSENT_GATE));
    }

    @Test
    void flagOnPlanCampaignIsStillGated() {
        props.setConsentGateAllCampaigns(true);
        UUID legacy = member();
        when(consentGate.reasons(any(), anyCollection()))
                .thenReturn(Map.of(legacy, Optional.of(ConsentGate.LEGACY_UNPROVEN)));

        assertThat(guard.skipReasons(campaign(eventId, "audience_plan"), List.of(legacy), NOW))
                .containsExactly(Map.entry(legacy, SendPathGuard.CONSENT_GATE));
    }

    @Test
    void flagOnCapsStillWinOverConsentGate() {
        props.setConsentGateAllCampaigns(true);
        UUID m = member();
        sent(eventId, m, "sent", NOW.minus(1, ChronoUnit.DAYS));
        sent(eventId, m, "sent", NOW.minus(2, ChronoUnit.DAYS));
        when(consentGate.reasons(any(), anyCollection()))
                .thenReturn(Map.of(m, Optional.of(ConsentGate.NO_BASIS)));

        assertThat(guard.skipReasons(campaign(eventId, "manual"), List.of(m), NOW))
                .containsExactly(Map.entry(m, SendPathGuard.EVENT_CAP));
    }

    @Test
    void flagOnNoIdsMeansNoSkips() {
        props.setConsentGateAllCampaigns(true);

        assertThat(guard.skipReasons(campaign(null, "manual"), List.of(), NOW)).isEmpty();
        verify(consentGate, never()).reasons(any(), anyCollection());
    }
}
