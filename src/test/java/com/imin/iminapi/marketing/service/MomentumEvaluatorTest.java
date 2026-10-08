package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audienceplan.config.PlanRefreshExecutor;
import com.imin.iminapi.marketing.MomentumTestSupport;
import com.imin.iminapi.marketing.dto.MomentumDraftPayload;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rule firing and guardrails over the real copy generator, SendGate and notifier. Firing rows evaluate
 * their own event only: {@code runOnce} walks every org's on-sale events in the shared database.
 */
@IminIntegrationTest
class MomentumEvaluatorTest {

    @Autowired MomentumEvaluator evaluator;
    @Autowired MomentumSuggestionRepository suggestions;
    @Autowired MomentumTestSupport support;
    @Autowired MomentumThresholds thresholds;
    @Autowired PropertyFlips flips;
    @Autowired ChatClient chatClient;
    @Autowired EventRepository events;
    @Autowired UserRepository users;
    @Autowired NotificationRepository notifications;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier(PlanRefreshExecutor.NAME) Executor planRefreshExecutor;

    private final List<UUID> orgIds = new ArrayList<>();
    private int defaultFloor;

    @BeforeEach
    void realCopyAndNoFloor() {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.entity(any(Class.class))).thenReturn(new MomentumDraftPayload("s", "p", "b", null, null, "why"));
        // A fresh org's Repeat segment is empty; the floor is the one guardrail rows opt back into.
        defaultFloor = thresholds.getMinAudienceFloor();
        flips.set(thresholds, "minAudienceFloor", 0);
    }

    /** Each fired trigger queues a plan refresh; let it finish before the rows it locks are deleted. */
    @AfterEach
    void deleteOwnRows() {
        AsyncDrain.drain(planRefreshExecutor);
        for (UUID orgId : orgIds) jdbc.update("delete from momentum_suggestions where org_id = ?", orgId);
        OrgRows.delete(jdbc, orgIds);
    }

    private UUID liveEvent(int sold, int capacity, Instant onSaleAt, Instant startsAt) {
        UUID event = support.seedLiveEvent(sold, capacity, onSaleAt, startsAt);
        orgIds.add(support.orgIdOf(event));
        return event;
    }

    /** The plain body on the bean behind the scheduling proxy, for this event only. */
    private void evaluate(UUID eventId) {
        MomentumEvaluator target = AopTestUtils.getUltimateTargetObject(evaluator);
        target.evaluateOne(events.findById(eventId).orElseThrow(), Instant.now());
    }

    private UUID ownerOf(UUID event) {
        return users.findByOrgIdOrderByCreatedAtAsc(support.orgIdOf(event)).stream()
                .filter(u -> u.getRole() == UserRole.OWNER).map(User::getId).findFirst().orElseThrow();
    }

    private List<MomentumSuggestion> live(UUID event) {
        return suggestions.findByEventIdAndStatus(event, "suggested");
    }

    @Test
    void firesLaunchPushWhenOnSale48hLowSellThrough() {
        UUID event = liveEvent(/*sold*/ 5, /*capacity*/ 100,
                /*onSaleAt*/ Instant.now().minusSeconds(50L * 3600),
                /*startsAt*/ Instant.now().plusSeconds(30L * 86400));
        evaluate(event);
        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType).contains("launch_push");
    }

    @Test
    void snapshotsTheRealSoldPerDaySeriesAtEvaluation() {
        // The spark is EVIDENCE, so the evaluator freezes it alongside the numbers it fired on
        // — the organizer sees the curve the engine actually saw, and the chart can never
        // contradict the `sold` scalar printed next to it.
        // on-sale 10 days back (still >= the 48h launch_push gate) so the spark window is the
        // full SPARK_DAYS regardless of what time of day the suite runs — a 50h on-sale would
        // clamp the window to 3 or 4 points depending on which side of UTC midnight "now" fell.
        UUID event = liveEvent(/*sold*/ 5, /*capacity*/ 100,
                /*onSaleAt*/ Instant.now().minusSeconds(10L * 86400),
                /*startsAt*/ Instant.now().plusSeconds(30L * 86400));
        support.seedDailyTickets(event, 1, 3, 1); // real ticket rows on the last 3 days

        evaluate(event);

        MomentumSuggestion made = live(event).stream()
                .filter(s -> "launch_push".equals(s.getTriggerType())).findFirst().orElseThrow();
        assertThat(made.getMetricsSnapshot()).contains("\"spark\":[0,0,0,0,0,0,0,1,3,1]");
        assertThat(made.getMetricsSnapshot()).contains("\"ticketsPerDay7d\":");
    }

    @Test
    void doesNotFireBelowAudienceFloor() {
        flips.set(thresholds, "minAudienceFloor", defaultFloor);
        // A launch_push candidate whose Repeat segment has no sendable member.
        UUID event = liveEvent(5, 100,
                Instant.now().minusSeconds(50L * 3600),
                Instant.now().plusSeconds(30L * 86400));
        evaluate(event);
        assertThat(live(event)).isEmpty();
    }

    @Test
    void firesSoldOutAtFullCapacity() {
        UUID event = liveEvent(100, 100,
                Instant.now().minusSeconds(10L * 86400),
                Instant.now().plusSeconds(5L * 86400));
        evaluate(event);
        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType).contains("sold_out");
    }

    @Test
    void soldReadsTicketCountNotOrderCount() {
        // Regression guard for the order-vs-ticket bug: a genuinely sold-out event whose
        // tiers sum to sold=capacity must fire SOLD_OUT even though only a FEW orders exist
        // (MomentumTestSupport seeds a small fixed order count for velocity, decoupled from
        // ticket count). Under the old `orders.countByEventId` logic sellThroughPct would be
        // ~3% and SOLD_OUT would never fire; under `tiers.sumSoldByEventId` it is 100%.
        UUID event = liveEvent(/*tickets sold*/ 60, /*capacity*/ 60,
                Instant.now().minusSeconds(10L * 86400),
                Instant.now().plusSeconds(5L * 86400));
        evaluate(event);
        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType).contains("sold_out");
    }

    @Test
    void urgencyUsesRealHoursNotDaysTimes24() {
        // Regression: pickTrigger compared daysOut*24 against the 72h window. daysOut is
        // floor(hours/24), so an event 80h out has daysOut=3 -> 72 -> fired URGENCY even
        // though it's 8h past the real window. With the fix (m.hoursToStart()=80) it must
        // NOT fire. 60% sold is inside the [30,90] urgency band, so ONLY the hours guard
        // decides. Fails on the old code (urgency present), passes on the fix.
        UUID event = liveEvent(/*sold*/ 60, /*capacity*/ 100,
                Instant.now().minusSeconds(15L * 86400),
                Instant.now().plusSeconds(80L * 3600)); // 80h out -> daysOut=3
        evaluate(event);
        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType)
                .doesNotContain("urgency_72h");
    }

    @Test
    void firesUrgencyInsideRealSeventyTwoHours() {
        // Guard against over-tightening: an event genuinely inside 72h still fires.
        UUID event = liveEvent(/*sold*/ 60, /*capacity*/ 100,
                Instant.now().minusSeconds(15L * 86400),
                Instant.now().plusSeconds(70L * 3600)); // 70h out, really inside the window
        evaluate(event);
        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType).contains("urgency_72h");
    }

    @Test
    void slumpComparesTicketsPerDayNotOrdersPerDay() {
        // Regression: the slump rule compared velocity7d (ORDERS/day) against a TICKETS/day
        // threshold. seedLiveEvent seeds 3 orders -> velocity7d=0.43, and required-to-50% here
        // is (50-21)/20=1.45, so the old code fired SLUMP. But the real recent pace is 3
        // tickets/day (seeded below), which is ABOVE 1.45 — not a slump. With the fix (both
        // sides tickets/day) it must NOT fire. Fails on the old code, passes on the fix.
        UUID event = liveEvent(/*sold*/ 21, /*capacity*/ 100,
                Instant.now().minusSeconds(12L * 86400),   // full spark window, >48h on-sale
                Instant.now().plusSeconds(20L * 86400));   // 20 days out (>= slump floor 14)
        support.seedDailyTickets(event, 3, 3, 3, 3, 3, 3, 3); // 3 tickets/day for 7 days -> 3.0/day
        evaluate(event);
        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType).doesNotContain("slump");
    }

    @Test
    void respectsSingleLiveSuggestionPerTrigger() {
        UUID event = liveEvent(5, 100,
                Instant.now().minusSeconds(50L * 3600),
                Instant.now().plusSeconds(30L * 86400));
        evaluate(event);
        evaluate(event); // second run must not create a duplicate live launch_push
        assertThat(live(event)).filteredOn(s -> s.getTriggerType().equals("launch_push")).hasSize(1);
    }

    @Test
    void respectsCooldown() {
        UUID event = liveEvent(5, 100,
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

        evaluate(event);
        assertThat(live(event)).isEmpty();
    }

    @Test
    void expiresLiveSuggestionsForStartedEvents() {
        // The full pass walks every org's events; the default floor keeps it from drafting for theirs.
        flips.set(thresholds, "minAudienceFloor", defaultFloor);
        UUID event = liveEvent(5, 100,
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
    void firedSuggestionNotifiesTheOrgOwnerInApp() {
        UUID event = liveEvent(5, 100,
                Instant.now().minusSeconds(50L * 3600),
                Instant.now().plusSeconds(30L * 86400));
        UUID ownerId = ownerOf(event);

        evaluate(event); // must not throw

        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType).contains("launch_push");
        List<Notification> unread = notifications.findByUserIdAndReadAtIsNull(ownerId);
        assertThat(unread).singleElement().satisfies(n -> {
            assertThat(n.getId()).isNotNull();
            assertThat(n.getKind()).isEqualTo("momentum_suggestion");
            assertThat(n.getTitle()).isEqualTo("New campaign suggestion: launch push");
            assertThat(n.getBody()).isEqualTo("why");
            assertThat(n.getLink()).isEqualTo("/marketing");
        });
    }

    @Test
    void notificationFailureDoesNotRollBackSuggestion() {
        // The notifier writes in its OWN (REQUIRES_NEW) transaction and swallows a failure there,
        // so a failed in-app write can neither throw out of the evaluation nor lose the suggestion.
        UUID event = liveEvent(5, 100,
                Instant.now().minusSeconds(50L * 3600),
                Instant.now().plusSeconds(30L * 86400));
        UUID ownerId = ownerOf(event);

        try (var fault = PgFaults.failWrites(jdbc, "notifications", "user_id", ownerId)) {
            evaluate(event); // must not throw despite the notification insert failing
        }

        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType).contains("launch_push");
        assertThat(notifications.findByUserIdAndReadAtIsNull(ownerId)).isEmpty();
    }
}
