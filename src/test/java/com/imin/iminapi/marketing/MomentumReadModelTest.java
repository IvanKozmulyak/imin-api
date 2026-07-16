package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.marketing.service.MomentumMetrics;
import com.imin.iminapi.marketing.service.MomentumService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enriched Momentum suggestion read-model (spec §6.4), end to end against H2.
 *
 * <p>Covers the fields the card needs and the resolution strategy behind each: {@code eventName}
 * and {@code segmentLabel} resolved LIVE (a rename must reach the card), {@code smsLocked}
 * derived from the org's REAL opted-in phone count, and the spark series carrying REAL
 * per-day ticket sales — including a declining one, which is the case the deleted FE
 * fabrication was structurally unable to draw.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class MomentumReadModelTest {

    @Autowired MomentumService service;
    @Autowired MomentumSuggestionRepository suggestions;
    @Autowired MomentumTestSupport support;
    @Autowired SegmentRepository segments;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired DataSource dataSource;

    // Shared H2 context — same FK-safe wipe convention as the sibling Momentum tests.
    // tickets cascade from orders (V24: tickets.order_id ... ON DELETE CASCADE).
    @BeforeEach
    @AfterEach
    void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from momentum_suggestions");
            s.execute("delete from campaigns");
            s.execute("delete from tickets");
            s.execute("delete from orders");
            s.execute("delete from ticket_tiers");
            s.execute("delete from events");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
            s.execute("delete from segments");
            s.execute("delete from users");
            s.execute("delete from organizations");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }

    /** Persist a suggestion whose snapshot is built from REAL seeded ticket rows. */
    private MomentumSuggestion seed(UUID orgId, UUID eventId, String trigger,
                                    int sold, int capacity, Instant onSaleAt, Instant startsAt,
                                    UUID segmentId, List<Instant> soldTicketsAt) {
        MomentumMetrics m = MomentumMetrics.compute(
                sold, capacity, 3, onSaleAt, startsAt, Instant.now(), soldTicketsAt);
        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(orgId);
        s.setEventId(eventId);
        s.setTriggerType(trigger);
        s.setStatus("suggested");
        s.setMetricsSnapshot(m.toJson());
        s.setDraftPayload("{\"subject\":\"S\",\"preheader\":\"p\",\"bodyMd\":\"b\",\"segmentId\":"
                + (segmentId == null ? "null" : "\"" + segmentId + "\"")
                + ",\"posterUrl\":null,\"why\":\"w\"}");
        s.setSuggestedAt(Instant.now());
        return suggestions.save(s);
    }

    private UUID repeatSegmentId(UUID orgId) {
        return segments.findByOrgId(orgId).stream()
                .filter(sg -> "Repeat".equals(sg.getName()))
                .map(sg -> sg.getId()).findFirst().orElseThrow();
    }

    // ---------- eventName ----------

    @Test
    void eventNameIsResolvedLiveAndSurvivesARename() {
        UUID event = support.seedLiveEvent(487, 600, Instant.now().minus(Duration.ofDays(30)),
                Instant.now().plus(Duration.ofHours(64)));
        UUID org = support.orgIdOf(event);
        seed(org, event, "urgency_72h", 487, 600, Instant.now().minus(Duration.ofDays(30)),
                Instant.now().plus(Duration.ofHours(64)), repeatSegmentId(org), List.of());

        assertThat(service.list(support.principalFor(org), "suggested").get(0).eventName())
                .isEqualTo("Momentum Test Event");

        // The suggestion row is NOT rewritten — only the event is. A live resolve must follow
        // the rename; a snapshotted name would leave the card describing an event that no
        // longer goes by that name.
        support.renameEvent(event, "Subterrane // Vol. 09");

        MomentumSuggestionDto after = service.list(support.principalFor(org), "suggested").get(0);
        assertThat(after.eventName()).isEqualTo("Subterrane // Vol. 09");
        // …while the evidence that justified it stayed frozen.
        assertThat(after.metricsSnapshot()).contains("\"sold\":487");
    }

    @Test
    void eventNameIsNullRatherThanInventedWhenTheEventIsGone() {
        UUID event = support.seedLiveEvent(10, 100, Instant.now().minus(Duration.ofDays(5)),
                Instant.now().plus(Duration.ofDays(30)));
        UUID org = support.orgIdOf(event);
        // momentum_suggestions.event_id has no FK (V58), so an orphan row is representable.
        seed(org, UUID.randomUUID(), "launch_push", 10, 100,
                Instant.now().minus(Duration.ofDays(5)), Instant.now().plus(Duration.ofDays(30)),
                null, List.of());

        assertThat(service.list(support.principalFor(org), "suggested").get(0).eventName()).isNull();
    }

    // ---------- smsLocked ----------

    @Test
    void smsLockedIsTrueAtZeroOptedInPhonesAndFalseAboveZero() {
        UUID event = support.seedLiveEvent(487, 600, Instant.now().minus(Duration.ofDays(30)),
                Instant.now().plus(Duration.ofHours(64)));
        UUID org = support.orgIdOf(event);
        seed(org, event, "urgency_72h", 487, 600, Instant.now().minus(Duration.ofDays(30)),
                Instant.now().plus(Duration.ofHours(64)), repeatSegmentId(org), List.of());

        // No memberships at all ⇒ 0 phones ⇒ SMS genuinely cannot be sent.
        assertThat(service.list(support.principalFor(org), "suggested").get(0).smsLocked()).isTrue();

        // A subscribed membership WITHOUT a phone must not unlock it — the count requires both.
        addMembership(org, null, "subscribed");
        assertThat(service.list(support.principalFor(org), "suggested").get(0).smsLocked()).isTrue();

        // A phone without SMS consent must not unlock it either.
        addMembership(org, "+380501234567", "never");
        assertThat(service.list(support.principalFor(org), "suggested").get(0).smsLocked()).isTrue();

        // Phone + explicit SMS subscription ⇒ unlocked, live, with no suggestion rewrite.
        addMembership(org, "+380501234568", "subscribed");
        assertThat(service.list(support.principalFor(org), "suggested").get(0).smsLocked()).isFalse();
    }

    private void addMembership(UUID orgId, String phone, String smsConsent) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("mom-" + UUID.randomUUID() + "@example.com");
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        m.setPhoneE164(phone);
        m.setSmsConsentStatus(smsConsent);
        memberships.save(m);
    }

    // ---------- segmentLabel ----------

    @Test
    void segmentLabelResolvesTheDraftSegmentAndIsNullWhenAbsent() {
        UUID event = support.seedLiveEvent(10, 100, Instant.now().minus(Duration.ofDays(5)),
                Instant.now().plus(Duration.ofDays(30)));
        UUID org = support.orgIdOf(event);
        seed(org, event, "launch_push", 10, 100, Instant.now().minus(Duration.ofDays(5)),
                Instant.now().plus(Duration.ofDays(30)), repeatSegmentId(org), List.of());

        assertThat(service.list(support.principalFor(org), "suggested").get(0).segmentLabel())
                .isEqualTo("Repeat");

        // A draft with no segment has no label to show — null, never a stand-in string.
        suggestions.deleteAll();
        seed(org, event, "launch_push", 10, 100, Instant.now().minus(Duration.ofDays(5)),
                Instant.now().plus(Duration.ofDays(30)), null, List.of());
        assertThat(service.list(support.principalFor(org), "suggested").get(0).segmentLabel()).isNull();
    }

    // ---------- the spark series ----------

    @Test
    void sparkCarriesRealPerDaySalesFromTheTicketsTable() {
        Instant onSale = Instant.now().minus(Duration.ofDays(30));
        Instant starts = Instant.now().plus(Duration.ofHours(64));
        UUID event = support.seedLiveEvent(21, 600, onSale, starts);
        UUID org = support.orgIdOf(event);

        // Real ticket rows: 4 days of sales, oldest → newest.
        support.seedDailyTickets(event, 2, 9, 4, 6);
        List<Instant> soldAt = ticketTimestamps(event);

        seed(org, event, "urgency_72h", 21, 600, onSale, starts, repeatSegmentId(org), soldAt);

        MomentumSuggestionDto dto = service.list(support.principalFor(org), "suggested").get(0);
        // On-sale 30 days ago, so the window is the full 10-day span: the six quiet days are
        // REAL zeros (tickets were buyable and nobody bought), not padding.
        assertThat(dto.spark()).containsExactly(0, 0, 0, 0, 0, 0, 2, 9, 4, 6);
        // Sums to the tickets that actually exist — it is the same data, not a parallel story.
        assertThat(dto.spark().stream().mapToInt(Integer::intValue).sum()).isEqualTo(21);
    }

    @Test
    void sparkRendersARealDeclineForASlumpCase() {
        // THE regression this work exists to prevent. The FE used to synthesize the curve as
        // [pct-12, pct-4, pct] — monotonically rising BY CONSTRUCTION, so a slump could never
        // look like a slump. A real declining series must survive to the DTO and the prose.
        Instant onSale = Instant.now().minus(Duration.ofDays(30));
        // +1h of slack: daysOut floors the duration, and the clock advances between seeding
        // startsAt here and computing the metrics below — exactly 20d would round to 19.
        Instant starts = Instant.now().plus(Duration.ofDays(20)).plus(Duration.ofHours(1));
        UUID event = support.seedLiveEvent(97, 280, onSale, starts);
        UUID org = support.orgIdOf(event);

        support.seedDailyTickets(event, 9, 14, 11, 16, 13, 10, 8, 6, 5, 5);
        seed(org, event, "slump", 97, 280, onSale, starts, repeatSegmentId(org),
                ticketTimestamps(event));

        MomentumSuggestionDto dto = service.list(support.principalFor(org), "suggested").get(0);

        assertThat(dto.spark()).containsExactly(9, 14, 11, 16, 13, 10, 8, 6, 5, 5);
        // The decline is genuinely representable: the series ends well below its peak.
        assertThat(dto.spark().get(dto.spark().size() - 1)).isLessThan(dto.spark().get(0));
        assertThat(dto.spark().stream().mapToInt(Integer::intValue).max().orElseThrow()).isEqualTo(16);
        // …and the prose reads the decline off that real series rather than asserting it.
        assertThat(dto.pace()).contains("down 46% on the previous 5 days");
        assertThat(dto.headline()).isEqualTo("20 DAYS OUT");
        assertThat(dto.daysOutLabel()).isEqualTo("20 days out");
    }

    @Test
    void sparkExcludesRefundedTicketsSoItMatchesTheSoldScalar() {
        Instant onSale = Instant.now().minus(Duration.ofDays(30));
        Instant starts = Instant.now().plus(Duration.ofDays(20));
        UUID event = support.seedLiveEvent(10, 280, onSale, starts);
        UUID org = support.orgIdOf(event);

        support.seedDailyTickets(event, 5, 5);
        support.refundTickets(event, 3); // RefundService flips state to 'refunded'

        seed(org, event, "slump", 7, 280, onSale, starts, repeatSegmentId(org),
                ticketTimestamps(event));

        MomentumSuggestionDto dto = service.list(support.principalFor(org), "suggested").get(0);
        // 10 issued − 3 refunded = 7 in the SOLD set, the same predicate tierAggregates uses.
        assertThat(dto.spark().stream().mapToInt(Integer::intValue).sum()).isEqualTo(7);
    }

    @Test
    void sparkIsNullNotFabricatedWhenTheSnapshotHasNoSeries() {
        // A row written before the series existed (legacy snapshot). The card must lose the
        // chart, NOT gain a curve reconstructed from sellThroughPct.
        UUID event = support.seedLiveEvent(10, 100, Instant.now().minus(Duration.ofDays(5)),
                Instant.now().plus(Duration.ofDays(30)));
        UUID org = support.orgIdOf(event);
        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(org);
        s.setEventId(event);
        s.setTriggerType("launch_push");
        s.setStatus("suggested");
        s.setMetricsSnapshot("{\"sold\":10,\"capacity\":100,\"sellThroughPct\":10,\"daysOut\":30,"
                + "\"hoursSinceOnSale\":120,\"velocity7d\":0.43}");
        s.setDraftPayload("{\"subject\":\"S\",\"segmentId\":null}");
        s.setSuggestedAt(Instant.now());
        suggestions.save(s);

        MomentumSuggestionDto dto = service.list(support.principalFor(org), "suggested").get(0);
        assertThat(dto.spark()).isNull();
        // Prose still works off the numbers the legacy snapshot DOES have…
        assertThat(dto.headline()).isEqualTo("TICKETS LIVE");
        assertThat(dto.daysOutLabel()).isEqualTo("On-sale 5 days");
        // …and stays silent about velocity it cannot measure.
        assertThat(dto.pace()).doesNotContain("tickets/day");
    }

    @Test
    void malformedSnapshotDegradesToNullProseInsteadOf500ingTheList() {
        UUID event = support.seedLiveEvent(10, 100, Instant.now().minus(Duration.ofDays(5)),
                Instant.now().plus(Duration.ofDays(30)));
        UUID org = support.orgIdOf(event);
        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(org);
        s.setEventId(event);
        s.setTriggerType("launch_push");
        s.setStatus("suggested");
        s.setMetricsSnapshot("not json at all");
        s.setDraftPayload("{\"segmentId\":\"not-a-uuid\"}");
        s.setSuggestedAt(Instant.now());
        suggestions.save(s);

        MomentumSuggestionDto dto = service.list(support.principalFor(org), "suggested").get(0);
        assertThat(dto.headline()).isNull();
        assertThat(dto.pace()).isNull();
        assertThat(dto.spark()).isNull();
        assertThat(dto.segmentLabel()).isNull();
        // The identity fields still resolve — one bad column does not blank the card.
        assertThat(dto.eventName()).isEqualTo("Momentum Test Event");
    }

    /** SOLD ticket timestamps for an event, as the evaluator would read them. */
    private List<Instant> ticketTimestamps(UUID eventId) {
        return support.soldTicketCreatedAt(eventId, Instant.now().minus(
                Duration.ofDays(MomentumMetrics.SPARK_DAYS)));
    }
}
