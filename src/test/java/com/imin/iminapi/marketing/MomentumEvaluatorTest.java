package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.marketing.service.MomentumCopyGenerator;
import com.imin.iminapi.marketing.dto.MomentumDraftPayload;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Rule firing + guardrails against H2. Copy generation and SendGate are mocked
 * so no OpenRouter call happens and the audience floor is controllable.
 * The org (with its prebuilt "Repeat" segment), event, ticket-tier and order
 * fixtures are created by MomentumTestSupport.seedLiveEvent(...) (Task 6 Step 2),
 * which calls SegmentService.ensurePrebuiltSegments(orgId) so the evaluator's
 * defaultTargetSegmentId("Repeat") resolves before it reaches the mocked SendGate.
 * NOTE: SegmentService is NOT mocked here — it runs for real against H2.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class MomentumEvaluatorTest {

    @Autowired
    com.imin.iminapi.marketing.service.MomentumEvaluator evaluator;
    @Autowired
    MomentumSuggestionRepository suggestions;
    @Autowired
    MomentumTestSupport support; // seeds org (+ prebuilt "Repeat" segment)/event/tiers/orders — Task-6 helper

    @MockitoBean
    MomentumCopyGenerator copy;
    @MockitoBean
    SendGateService sendGate;
    // MomentumNotifier (Task 7) injects NotificationRepository; mock it so (a) the
    // best-effort in-app write never depends on a seeded OWNER user in the firing tests,
    // and (b) notificationFailureDoesNotRollBackSuggestion can force save(...) to throw.
    @MockitoBean
    NotificationRepository notifications;

    @Autowired
    DataSource dataSource;

    // Shared H2 context — clear momentum_suggestions AND the fixtures the seeder created,
    // in FK-safe order, both before and after each test (audience convention). Without this,
    // (1) 'suggested' rows leaked from MomentumRepositoryTest or an earlier evaluator test are
    // visited by expireStale's GLOBAL findByStatus("suggested"), and (2) leaked events/orders
    // become extra findMomentumCandidates rows — both make firing assertions order-dependent.
    @BeforeEach
    @AfterEach
    void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from momentum_suggestions");
            s.execute("delete from orders");
            s.execute("delete from ticket_tiers");
            s.execute("delete from events");
            s.execute("delete from segments");
            s.execute("delete from users");
            s.execute("delete from organizations");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }

    @BeforeEach
    void stubs() {
        when(copy.generate(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MomentumDraftPayload("s", "p", "b", null, null, "why"));
        // Above the floor by default.
        when(sendGate.evaluate(any(), any()))
                .thenReturn(new SendGateService.GateResult(
                        List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                UUID.randomUUID(), UUID.randomUUID()), List.of()));
    }

    @Test
    void firesLaunchPushWhenOnSale48hLowSellThrough() {
        UUID event = support.seedLiveEvent(
                /*sold*/ 5, /*capacity*/ 100,
                /*onSaleAt*/ Instant.now().minusSeconds(50L * 3600),
                /*startsAt*/ Instant.now().plusSeconds(30L * 86400));
        evaluator.runOnce();
        List<MomentumSuggestion> made = suggestions.findByEventIdAndStatus(event, "suggested");
        assertThat(made).extracting(MomentumSuggestion::getTriggerType).contains("launch_push");
    }

    @Test
    void snapshotsTheRealSoldPerDaySeriesAtEvaluation() {
        // The spark is EVIDENCE, so the evaluator freezes it alongside the numbers it fired on
        // — the organizer sees the curve the engine actually saw, and the chart can never
        // contradict the `sold` scalar printed next to it.
        // on-sale 10 days back (still >= the 48h launch_push gate) so the spark window is the
        // full SPARK_DAYS regardless of what time of day the suite runs — a 50h on-sale would
        // clamp the window to 3 or 4 points depending on which side of UTC midnight "now" fell.
        UUID event = support.seedLiveEvent(
                /*sold*/ 5, /*capacity*/ 100,
                /*onSaleAt*/ Instant.now().minusSeconds(10L * 86400),
                /*startsAt*/ Instant.now().plusSeconds(30L * 86400));
        support.seedDailyTickets(event, 1, 3, 1); // real ticket rows on the last 3 days

        evaluator.runOnce();

        MomentumSuggestion made = suggestions.findByEventIdAndStatus(event, "suggested").stream()
                .filter(s -> "launch_push".equals(s.getTriggerType())).findFirst().orElseThrow();
        assertThat(made.getMetricsSnapshot()).contains("\"spark\":[0,0,0,0,0,0,0,1,3,1]");
        assertThat(made.getMetricsSnapshot()).contains("\"ticketsPerDay7d\":");
    }

    @Test
    void doesNotFireBelowAudienceFloor() {
        when(sendGate.evaluate(any(), any()))
                .thenReturn(new SendGateService.GateResult(
                        List.of(UUID.randomUUID(), UUID.randomUUID()), List.of())); // 2 < floor 10
        UUID event = support.seedLiveEvent(5, 100,
                Instant.now().minusSeconds(50L * 3600),
                Instant.now().plusSeconds(30L * 86400));
        evaluator.runOnce();
        assertThat(suggestions.findByEventIdAndStatus(event, "suggested")).isEmpty();
    }

    @Test
    void firesSoldOutAtFullCapacity() {
        UUID event = support.seedLiveEvent(100, 100,
                Instant.now().minusSeconds(10L * 86400),
                Instant.now().plusSeconds(5L * 86400));
        evaluator.runOnce();
        assertThat(suggestions.findByEventIdAndStatus(event, "suggested"))
                .extracting(MomentumSuggestion::getTriggerType).contains("sold_out");
    }

    @Test
    void soldReadsTicketCountNotOrderCount() {
        // Regression guard for the order-vs-ticket bug: a genuinely sold-out event whose
        // tiers sum to sold=capacity must fire SOLD_OUT even though only a FEW orders exist
        // (MomentumTestSupport seeds a small fixed order count for velocity, decoupled from
        // ticket count). Under the old `orders.countByEventId` logic sellThroughPct would be
        // ~3% and SOLD_OUT would never fire; under `tiers.sumSoldByEventId` it is 100%.
        UUID event = support.seedLiveEvent(/*tickets sold*/ 60, /*capacity*/ 60,
                Instant.now().minusSeconds(10L * 86400),
                Instant.now().plusSeconds(5L * 86400));
        evaluator.runOnce();
        assertThat(suggestions.findByEventIdAndStatus(event, "suggested"))
                .extracting(MomentumSuggestion::getTriggerType).contains("sold_out");
    }

    @Test
    void urgencyUsesRealHoursNotDaysTimes24() {
        // Regression: pickTrigger compared daysOut*24 against the 72h window. daysOut is
        // floor(hours/24), so an event 80h out has daysOut=3 -> 72 -> fired URGENCY even
        // though it's 8h past the real window. With the fix (m.hoursToStart()=80) it must
        // NOT fire. 60% sold is inside the [30,90] urgency band, so ONLY the hours guard
        // decides. Fails on the old code (urgency present), passes on the fix.
        UUID event = support.seedLiveEvent(/*sold*/ 60, /*capacity*/ 100,
                Instant.now().minusSeconds(15L * 86400),
                Instant.now().plusSeconds(80L * 3600)); // 80h out -> daysOut=3
        evaluator.runOnce();
        assertThat(suggestions.findByEventIdAndStatus(event, "suggested"))
                .extracting(MomentumSuggestion::getTriggerType)
                .doesNotContain("urgency_72h");
    }

    @Test
    void firesUrgencyInsideRealSeventyTwoHours() {
        // Guard against over-tightening: an event genuinely inside 72h still fires.
        UUID event = support.seedLiveEvent(/*sold*/ 60, /*capacity*/ 100,
                Instant.now().minusSeconds(15L * 86400),
                Instant.now().plusSeconds(70L * 3600)); // 70h out, really inside the window
        evaluator.runOnce();
        assertThat(suggestions.findByEventIdAndStatus(event, "suggested"))
                .extracting(MomentumSuggestion::getTriggerType)
                .contains("urgency_72h");
    }

    @Test
    void slumpComparesTicketsPerDayNotOrdersPerDay() {
        // Regression: the slump rule compared velocity7d (ORDERS/day) against a TICKETS/day
        // threshold. seedLiveEvent seeds 3 orders -> velocity7d=0.43, and required-to-50% here
        // is (50-21)/20=1.45, so the old code fired SLUMP. But the real recent pace is 3
        // tickets/day (seeded below), which is ABOVE 1.45 — not a slump. With the fix (both
        // sides tickets/day) it must NOT fire. Fails on the old code, passes on the fix.
        UUID event = support.seedLiveEvent(/*sold*/ 21, /*capacity*/ 100,
                Instant.now().minusSeconds(12L * 86400),   // full spark window, >48h on-sale
                Instant.now().plusSeconds(20L * 86400));   // 20 days out (>= slump floor 14)
        support.seedDailyTickets(event, 3, 3, 3, 3, 3, 3, 3); // 3 tickets/day for 7 days -> 3.0/day
        evaluator.runOnce();
        assertThat(suggestions.findByEventIdAndStatus(event, "suggested"))
                .extracting(MomentumSuggestion::getTriggerType)
                .doesNotContain("slump");
    }

    @Test
    void respectsSingleLiveSuggestionPerTrigger() {
        UUID event = support.seedLiveEvent(5, 100,
                Instant.now().minusSeconds(50L * 3600),
                Instant.now().plusSeconds(30L * 86400));
        evaluator.runOnce();
        evaluator.runOnce(); // second run must not create a duplicate live launch_push
        assertThat(suggestions.findByEventIdAndStatus(event, "suggested"))
                .filteredOn(s -> s.getTriggerType().equals("launch_push")).hasSize(1);
    }

    @Test
    void respectsCooldown() {
        UUID event = support.seedLiveEvent(5, 100,
                Instant.now().minusSeconds(50L * 3600),
                Instant.now().plusSeconds(30L * 86400));
        // A dismissed launch_push 3 days ago (< 7-day cooldown) must block a re-suggest.
        MomentumSuggestion prior = new MomentumSuggestion();
        prior.setId(UUID.randomUUID());
        prior.setOrgId(support.orgIdOf(event));
        prior.setEventId(event);
        prior.setTriggerType("launch_push");
        prior.setStatus("dismissed");
        prior.setMetricsSnapshot("{}");
        prior.setDraftPayload("{}");
        prior.setSuggestedAt(Instant.now().minusSeconds(3L * 86400));
        suggestions.save(prior);

        evaluator.runOnce();
        assertThat(suggestions.findByEventIdAndStatus(event, "suggested")).isEmpty();
    }

    @Test
    void expiresLiveSuggestionsForStartedEvents() {
        UUID event = support.seedLiveEvent(5, 100,
                Instant.now().minusSeconds(50L * 3600),
                Instant.now().plusSeconds(30L * 86400));
        MomentumSuggestion live = new MomentumSuggestion();
        live.setId(UUID.randomUUID());
        live.setOrgId(support.orgIdOf(event));
        live.setEventId(event);
        live.setTriggerType("launch_push");
        live.setStatus("suggested");
        live.setMetricsSnapshot("{}");
        live.setDraftPayload("{}");
        live.setSuggestedAt(Instant.now().minusSeconds(3600));
        suggestions.save(live);
        support.markStarted(event); // startsAt moved to the past

        evaluator.runOnce();
        MomentumSuggestion reloaded = suggestions.findById(live.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("expired");
    }

    @Test
    void notificationFailureDoesNotRollBackSuggestion() {
        // Correctness guard for the REQUIRES_NEW notifier (Task 7): if the best-effort
        // in-app notification write blows up, the suggestion that was just persisted must
        // STILL exist. This only holds because MomentumNotifier writes in its OWN
        // (REQUIRES_NEW) transaction — a same-transaction save() + swallow would leave
        // run()'s transaction rollback-only and lose the suggestion at commit.
        doThrow(new RuntimeException("boom")).when(notifications).save(any(Notification.class));

        UUID event = support.seedLiveEvent(5, 100,
                Instant.now().minusSeconds(50L * 3600),
                Instant.now().plusSeconds(30L * 86400));

        evaluator.runOnce(); // must not throw despite the notification save failing

        assertThat(suggestions.findByEventIdAndStatus(event, "suggested"))
                .extracting(MomentumSuggestion::getTriggerType).contains("launch_push");
    }
}
