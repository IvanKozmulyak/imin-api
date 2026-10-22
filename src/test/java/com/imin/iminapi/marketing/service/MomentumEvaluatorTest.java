package com.imin.iminapi.marketing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audienceplan.config.PlanRefreshExecutor;
import com.imin.iminapi.marketing.MomentumTestSupport;
import com.imin.iminapi.marketing.config.MarketingChatClient;
import com.imin.iminapi.marketing.dto.MomentumDraftPayload;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    @Autowired MomentumService momentum;
    @Autowired MomentumTestSupport support;
    @Autowired MomentumThresholds thresholds;
    @Autowired PropertyFlips flips;
    @Autowired @Qualifier(MarketingChatClient.NAME) ChatClient chatClient;
    @Autowired EventRepository events;
    @Autowired UserRepository users;
    @Autowired NotificationRepository notifications;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier(PlanRefreshExecutor.NAME) Executor planRefreshExecutor;
    @Autowired IminFixtures fx;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired SegmentRepository segmentRepo;

    private static final ObjectMapper JSON = new ObjectMapper();

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
        CampaignRows.delete(jdbc, orgIds);
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

    /**
     * tier.sold 100/100 with 95 of those seats held by a test-mode order: the room is physically full,
     * so the card is sold out, while sold, velocity (3 live orders) and the spark stay live.
     */
    @Test
    void test_mode_orders_count_in_neither_sold_velocity_nor_spark() {
        UUID event = liveEvent(/*sold*/ 100, /*capacity*/ 100,
                /*onSaleAt*/ Instant.now().minusSeconds(10L * 86400),
                /*startsAt*/ Instant.now().plusSeconds(30L * 86400));
        support.seedDailyTickets(event, 1, 3, 1);
        support.seedTestModeOrder(event, 95);

        evaluate(event);

        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType).containsExactly("sold_out");
        String snapshot = live(event).get(0).getMetricsSnapshot();
        assertThat(snapshot).contains("\"sold\":5,", "\"sellThroughPct\":5,", "\"velocity7d\":0.43,",
                "\"spark\":[0,0,0,0,0,0,0,1,3,1]", "\"seatsLeft\":0");
        var card = momentum.list(support.principalFor(support.orgIdOf(event)), "suggested").stream()
                .filter(s -> s.eventId().equals(event)).findFirst().orElseThrow();
        assertThat(card.pace()).startsWith("100 of 100 gone");
    }

    /** No tier quantity is unknown capacity, not a full room: no seat is left, yet nothing sold out. */
    @Test
    void an_event_without_capacity_is_never_called_sold_out() {
        UUID event = liveEvent(/*sold*/ 0, /*capacity*/ 0,
                /*onSaleAt*/ Instant.now().minusSeconds(10L * 86400),
                /*startsAt*/ Instant.now().plusSeconds(30L * 86400));

        evaluate(event);

        assertThat(live(event)).isEmpty();
    }

    /**
     * 60 of 100 seats gone, 20 of them to a test-mode order: live sold is 40% (inside the urgency band),
     * and the card counts the 40 seats still buyable, not 100 − 40 live.
     */
    @Test
    void urgency_counts_the_seats_left_including_test_held_ones() {
        UUID event = liveEvent(/*sold*/ 60, /*capacity*/ 100,
                /*onSaleAt*/ Instant.now().minusSeconds(10L * 86400),
                /*startsAt*/ Instant.now().plusSeconds(60L * 3600));
        support.seedDailyTickets(event, 40);
        support.seedTestModeOrder(event, 20);

        evaluate(event);

        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType).containsExactly("urgency_72h");
        assertThat(live(event).get(0).getMetricsSnapshot()).contains("\"sold\":40,", "\"seatsLeft\":40");
        var card = momentum.list(support.principalFor(support.orgIdOf(event)), "suggested").stream()
                .filter(s -> s.eventId().equals(event)).findFirst().orElseThrow();
        assertThat(card.headline()).isEqualTo("LAST 40 TICKETS");
        assertThat(card.pace()).contains("40 of 100 left");
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

    /** The notification opens its own card on the Momentum tab, and its kind names the trigger for a localized title. */
    @Test
    void firedSuggestionNotifiesTheOrgOwnerInApp() {
        UUID event = liveEvent(5, 100,
                Instant.now().minusSeconds(50L * 3600),
                Instant.now().plusSeconds(30L * 86400));
        UUID ownerId = ownerOf(event);

        evaluate(event); // must not throw

        MomentumSuggestion fired = live(event).stream()
                .filter(s -> "launch_push".equals(s.getTriggerType())).findFirst().orElseThrow();
        List<Notification> unread = notifications.findByUserIdAndReadAtIsNull(ownerId);
        assertThat(unread).singleElement().satisfies(n -> {
            assertThat(n.getId()).isNotNull();
            assertThat(n.getKind()).isEqualTo("momentum_suggestion.launch_push");
            assertThat(n.getTitle()).isEqualTo("New campaign suggestion: launch push");
            assertThat(n.getBody()).isEqualTo("why");
            assertThat(n.getLink()).isEqualTo("/marketing?tab=momentum&suggestion=" + fired.getId());
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

    // Excluded clickers come first, so a dropped filter would return them: a ticket holder, an 8-day-old click,
    // another event's campaign, an objected member and one pending erasure.
    @Test
    void clickedNoBuy_snapshotsTheClickersWithoutALiveTicket() throws Exception {
        UUID event = quietEvent(0, 100);
        UUID org = support.orgIdOf(event);
        Instant now = Instant.now();

        String holder = fx.email("cnb-holder");
        clicker(org, event, holder, now.minus(Duration.ofHours(1)), false, "active");
        ticketFor(event, holder, Ticket.STATE_ISSUED);
        clicker(org, event, fx.email("cnb-stale"), now.minus(Duration.ofDays(8)), false, "active");
        clicker(org, otherEventOf(event), fx.email("cnb-elsewhere"), now.minus(Duration.ofHours(1)), false, "active");
        clicker(org, event, fx.email("cnb-objected"), now.minus(Duration.ofHours(1)), true, "active");
        clicker(org, event, fx.email("cnb-erasing"), now.minus(Duration.ofHours(1)), false, "erase_pending");

        String refunded = fx.email("cnb-refunded");
        UUID refundedOnly = clicker(org, event, refunded, now.minus(Duration.ofDays(6)), false, "active");
        ticketFor(event, refunded, Ticket.STATE_REFUNDED);
        UUID twice = clicker(org, event, fx.email("cnb-twice"), now.minus(Duration.ofHours(3)), false, "active");
        clickAgain(org, event, twice, now.minus(Duration.ofHours(4)));
        List<UUID> included = List.of(refundedOnly, twice,
                clicker(org, event, fx.email("cnb-recent"), now.minus(Duration.ofHours(2)), false, "active"),
                clicker(org, event, fx.email("cnb-midweek"), now.minus(Duration.ofDays(3)), false, "active"));
        flips.set(thresholds, "minAudienceFloor", included.size());

        evaluate(event);

        assertThat(live(event)).extracting(MomentumSuggestion::getTriggerType).containsExactly("clicked_no_buy");
        MomentumSuggestion made = live(event).get(0);
        UUID segmentId = UUID.fromString(JSON.readTree(made.getDraftPayload()).path("segmentId").asText());
        Segment snapshot = segmentRepo.findByIdAndOrgId(segmentId, org).orElseThrow();
        assertThat(snapshot.getKind()).isEqualTo("static");
        assertThat(snapshot.getOrigin()).isEqualTo(Segment.ORIGIN_MOMENTUM);
        assertThat(snapshot.getName()).isEqualTo("Momentum: clicked tickets, no ticket · Momentum Test Event");
        List<UUID> frozen = new ArrayList<>();
        for (JsonNode id : JSON.readTree(snapshot.getSnapshotIds())) frozen.add(UUID.fromString(id.asText()));
        assertThat(frozen).containsExactlyInAnyOrderElementsOf(included);
        assertThat(JSON.readTree(made.getMetricsSnapshot()).path("clickedNoTicket").asInt(-1))
                .isEqualTo(included.size());
        assertThat(notifications.findByUserIdAndReadAtIsNull(ownerOf(event)))
                .extracting(Notification::getKind).containsExactly("momentum_suggestion.clicked_no_buy");
    }

    enum Guardrail {
        ANOTHER_TRIGGER_LIVE, OWN_SUGGESTION_IN_COOLDOWN, SOLD_OUT, BELOW_FLOOR, EVERY_CLICKER_HOLDS_A_TICKET
    }

    /** Each case has three recent clickers and a floor they meet, so only its guardrail holds the suggestion back. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Guardrail.class)
    void clickedNoBuy_isHeldBackByItsGuardrails(Guardrail guardrail) {
        UUID event = guardrail == Guardrail.SOLD_OUT ? quietEvent(100, 100) : quietEvent(0, 100);
        UUID org = support.orgIdOf(event);
        int clickers = 3;
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < clickers; i++) {
            emails.add(fx.email("cnb-guard"));
            clicker(org, event, emails.get(i), Instant.now().minus(Duration.ofHours(1)), false, "active");
        }
        flips.set(thresholds, "minAudienceFloor", clickers);
        switch (guardrail) {
            case ANOTHER_TRIGGER_LIVE -> prior(event, "launch_push", "suggested", Duration.ofHours(1));
            case OWN_SUGGESTION_IN_COOLDOWN -> prior(event, "clicked_no_buy", "dismissed", Duration.ofDays(3));
            // The dismissed sold_out keeps the sales-curve trigger in cooldown, so no live suggestion stands in.
            case SOLD_OUT -> prior(event, "sold_out", "dismissed", Duration.ofDays(1));
            case BELOW_FLOOR -> flips.set(thresholds, "minAudienceFloor", clickers + 1);
            // With no floor, only the empty-target check keeps a suggestion for nobody from being written.
            case EVERY_CLICKER_HOLDS_A_TICKET -> {
                emails.forEach(email -> ticketFor(event, email, Ticket.STATE_ISSUED));
                flips.set(thresholds, "minAudienceFloor", 0);
            }
        }

        evaluate(event);

        assertThat(suggestions.findByEventIdAndTriggerTypeAndStatus(event, "clicked_no_buy", "suggested")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from segments where org_id = ? and origin = 'momentum'",
                Integer.class, org)).isZero();
    }

    /** On sale for a day and a month out: no sales-curve rule matches while sell-through stays low. */
    private UUID quietEvent(int sold, int capacity) {
        return liveEvent(sold, capacity, Instant.now().minus(Duration.ofHours(24)), Instant.now().plus(Duration.ofDays(30)));
    }

    private UUID otherEventOf(UUID eventId) {
        Event e = new Event();
        e.setOrgId(support.orgIdOf(eventId));
        e.setName("Another Event");
        e.setSlug("event-" + UUID.randomUUID());
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.now().plus(Duration.ofDays(30)));
        e.setCreatedBy(ownerOf(eventId));
        return events.save(e).getId();
    }

    /** A subscribed member whose row in a sent email campaign about {@code eventId} has a ticket click at {@code at}. */
    private UUID clicker(UUID orgId, UUID eventId, String email, Instant at, boolean objected, String status) {
        Consumer consumer = new Consumer();
        consumer.setNormalizedEmail(email.toLowerCase(Locale.ROOT));
        consumer = consumers.save(consumer);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        m.setObjectedProfiling(objected);
        m.setStatus(status);
        m = memberships.save(m);

        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("clicked-no-buy");
        c.setStatus("sent");
        c.setEventId(eventId);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(email);
        r.setStatus("delivered");
        r.setClickedAt(at);
        r.setTicketClickedAt(at);
        recipients.save(r);
        return m.getMembershipId();
    }

    /** A second email campaign about the event whose ticket link the member clicked too. */
    private void clickAgain(UUID orgId, UUID eventId, UUID membershipId, Instant at) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("clicked-no-buy-2");
        c.setStatus("sent");
        c.setEventId(eventId);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(membershipId);
        r.setEmail("again-" + membershipId + "@example.com");
        r.setStatus("delivered");
        r.setClickedAt(at);
        r.setTicketClickedAt(at);
        recipients.save(r);
    }

    /** A non-test order for the event, matched to the member by email, holding one ticket in {@code state}. */
    private void ticketFor(UUID eventId, String email, String state) {
        fx.ticket(fx.order(events.findById(eventId).orElseThrow(), email), state);
    }

    private void prior(UUID eventId, String trigger, String status, Duration ago) {
        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(support.orgIdOf(eventId));
        s.setEventId(eventId);
        s.setTriggerType(trigger);
        s.setStatus(status);
        s.setMetricsSnapshot("{}");
        s.setDraftPayload("{}");
        s.setSuggestedAt(Instant.now().minus(ago));
        suggestions.save(s);
    }
}
