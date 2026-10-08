package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.audienceplan.engine.FanFeatureCalculator.Input;
import com.imin.iminapi.audienceplan.engine.FanFeatureCalculator.Result;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FanFeatureCalculatorTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TECHNO = "house & techno";
    private static final String POP = "pop";

    private static final AudiencePlanLogic LOGIC = shipped();
    private final FanFeatureCalculator calc = new FanFeatureCalculator(LOGIC);

    private List<Order> orders;
    private List<Ticket> tickets;
    private List<Event> events;
    private List<ConsentRecord> consents;
    private List<Instant> surveys;
    private boolean objected;

    @BeforeEach
    void reset() {
        orders = new ArrayList<>();
        tickets = new ArrayList<>();
        events = new ArrayList<>();
        consents = new ArrayList<>();
        surveys = new ArrayList<>();
        objected = false;
    }

    // ---- paid order rules (C6) ----

    @Test
    void freeOrder_ignored() {
        Order o = paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 1);
        o.setPaymentMethod("free");
        Result r = run();
        assertThat(r.paidOrders()).isZero();
        assertThat(r.fanClass()).isEqualTo("none");
        assertThat(r.lastPaidPurchaseAt()).isNull();
        assertThat(r.avgGroupSize()).isNull();
        assertThat(r.taste()).isEmpty();
    }

    @Test
    void zeroTotalStripeOrder_ignored() {
        paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 1).setTotalMinor(0);
        assertThat(run().paidOrders()).isZero();
    }

    @Test
    void testModeOrder_ignored() {
        paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 1).setTestMode(true);
        assertThat(run().paidOrders()).isZero();
    }

    @Test
    void fullyRefundedOrder_ignored() {
        Order o = paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 2);
        ticketsOf(o).forEach(t -> t.setState(Ticket.STATE_REFUNDED));
        assertThat(run().paidOrders()).isZero();
    }

    @Test
    void revokedOrder_ignored() {
        Order o = paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 1);
        ticketsOf(o).forEach(t -> t.setState(Ticket.STATE_REVOKED));
        assertThat(run().paidOrders()).isZero();
    }

    @Test
    void partiallyRefundedOrder_counts_withRemainingTicketsAsGroupSize() {
        Order o = paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 3);
        ticketsOf(o).get(0).setState(Ticket.STATE_REFUNDED);
        Result r = run();
        assertThat(r.paidOrders()).isEqualTo(1);
        assertThat(r.avgGroupSize()).isEqualByComparingTo(new BigDecimal("2.000"));
    }

    @Test
    void anotherOrgsOrder_ignored_andTasteOnlyFromThisOrg() {
        paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 1);
        Event foreign = event(POP, daysAgo(5));
        foreign.setOrgId(OTHER_ORG);
        paidOrder(foreign, daysAgo(5), 1).setOrgId(OTHER_ORG);
        Result r = run();
        assertThat(r.paidOrders()).isEqualTo(1);
        assertThat(r.taste()).containsOnlyKeys(TECHNO);
        assertThat(r.lastPaidPurchaseAt()).isEqualTo(daysAgo(10));
    }

    // ---- classes, first match ----

    @Test
    void threePaidWithin90_loyal() {
        paidOrder(event(TECHNO, daysAgo(80)), daysAgo(80), 1);
        paidOrder(event(TECHNO, daysAgo(40)), daysAgo(40), 1);
        paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 1);
        Result r = run();
        assertThat(r.paidOrders()).isEqualTo(3);
        assertThat(r.fanClass()).isEqualTo("loyal");
        assertThat(r.firstPaidPurchaseAt()).isEqualTo(daysAgo(80));
        assertThat(r.lastPaidPurchaseAt()).isEqualTo(daysAgo(10));
    }

    @Test
    void twoPaidWithin90_repeat() {
        paidOrder(event(TECHNO, daysAgo(40)), daysAgo(40), 1);
        paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 1);
        assertThat(run().fanClass()).isEqualTo("repeat");
    }

    @Test
    void lastPaid90DaysAgo_firstTimer() {
        paidOrder(event(TECHNO, daysAgo(90)), daysAgo(90), 1);
        Result r = run();
        assertThat(r.daysSinceLastPaid()).isEqualTo(90);
        assertThat(r.fanClass()).isEqualTo("first_timer");
    }

    @Test
    void lastPaid91DaysAgo_lapsing() {
        paidOrder(event(TECHNO, daysAgo(91)), daysAgo(91), 1);
        assertThat(run().fanClass()).isEqualTo("lapsing");
    }

    @Test
    void lastPaid180DaysAgo_lapsing() {
        paidOrder(event(TECHNO, daysAgo(180)), daysAgo(180), 1);
        assertThat(run().fanClass()).isEqualTo("lapsing");
    }

    @Test
    void lastPaid181DaysAgo_dormant() {
        paidOrder(event(TECHNO, daysAgo(181)), daysAgo(181), 1);
        assertThat(run().fanClass()).isEqualTo("dormant");
    }

    @Test
    void daysCountedAsCalendarDaysInOrgTimezone() {
        // 22:30 UTC on 2026-06-27 is already 2026-06-28 in Paris: 90 days, not 91.
        Instant purchase = Instant.parse("2026-06-27T22:30:00Z");
        paidOrder(event(TECHNO, purchase), purchase, 1);
        Result r = run();
        assertThat(r.daysSinceLastPaid()).isEqualTo(90);
        assertThat(r.fanClass()).isEqualTo("first_timer");
    }

    @Test
    void dormantWithLastContact1095Days_dormant() {
        paidOrder(event(TECHNO, daysAgo(1095)), daysAgo(1095), 1);
        Result r = run();
        assertThat(r.lastContactFromPersonAt()).isEqualTo(daysAgo(1095));
        assertThat(r.fanClass()).isEqualTo("dormant");
    }

    @Test
    void dormantWithLastContact1096Days_none() {
        paidOrder(event(TECHNO, daysAgo(1096)), daysAgo(1096), 1);
        assertThat(run().fanClass()).isEqualTo("none");
    }

    @Test
    void explicitConsentByThePerson_refreshesLastContact() {
        paidOrder(event(TECHNO, daysAgo(1096)), daysAgo(1096), 1);
        consent("subscribed", "explicit", "checkout", daysAgo(30));
        Result r = run();
        assertThat(r.lastContactFromPersonAt()).isEqualTo(daysAgo(30));
        assertThat(r.fanClass()).isEqualTo("dormant");
    }

    @ParameterizedTest
    @ValueSource(strings = {"checkout", "door_qr", "survey", "preference_centre_row", "order_confirmation"})
    void explicitConsentFromEachPersonSource_refreshesLastContact(String source) {
        paidOrder(event(TECHNO, daysAgo(1096)), daysAgo(1096), 1);
        consent("subscribed", "explicit", source, daysAgo(30));
        assertThat(run().lastContactFromPersonAt()).isEqualTo(daysAgo(30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"door_qr", "survey"})
    void signUpAwaitingConfirmation_doesNotRefreshLastContact_untilConfirmed(String source) {
        paidOrder(event(TECHNO, daysAgo(1096)), daysAgo(1096), 1);
        ConsentRecord c = consent("subscribed", "explicit", source, daysAgo(30));
        c.setConfirmationRequired(true);
        assertThat(run().lastContactFromPersonAt()).isEqualTo(daysAgo(1096));

        c.setConfirmedAt(daysAgo(29));
        assertThat(run().lastContactFromPersonAt()).isEqualTo(daysAgo(30));
    }

    @Test
    void organizerTypedManualConsent_doesNotRefreshLastContact() {
        paidOrder(event(TECHNO, daysAgo(1096)), daysAgo(1096), 1);
        consent("subscribed", "explicit", "manual", daysAgo(30));
        Result r = run();
        assertThat(r.lastContactFromPersonAt()).isEqualTo(daysAgo(1096));
        assertThat(r.fanClass()).isEqualTo("none");
    }

    @Test
    void organizerImportConsent_doesNotRefreshLastContact() {
        paidOrder(event(TECHNO, daysAgo(1096)), daysAgo(1096), 1);
        consent("subscribed", "explicit", "organizer_import", daysAgo(30));
        consent("subscribed", "explicit", "organizer_import_row", daysAgo(20));
        Result r = run();
        assertThat(r.lastContactFromPersonAt()).isEqualTo(daysAgo(1096));
        assertThat(r.fanClass()).isEqualTo("none");
    }

    @Test
    void unsubscribeAndSoftOptInRecords_doNotRefreshLastContact() {
        paidOrder(event(TECHNO, daysAgo(400)), daysAgo(400), 1);
        consent("unsubscribed", null, "unsubscribe_link", daysAgo(5));
        consent("subscribed", "soft_opt_in", "checkout", daysAgo(10));
        assertThat(run().lastContactFromPersonAt()).isEqualTo(daysAgo(400));
    }

    @Test
    void surveyResponse_refreshesLastContact() {
        paidOrder(event(TECHNO, daysAgo(1096)), daysAgo(1096), 1);
        surveys.add(daysAgo(3));
        Result r = run();
        assertThat(r.lastContactFromPersonAt()).isEqualTo(daysAgo(3));
        assertThat(r.fanClass()).isEqualTo("dormant");
    }

    @Test
    void importRowExplicit_noPaid_imported() {
        consent("subscribed", "explicit", "organizer_import_row", daysAgo(5));
        Result r = run();
        assertThat(r.fanClass()).isEqualTo("imported");
        assertThat(r.lastContactFromPersonAt()).isNull();
    }

    @Test
    void legacyBulkImportExplicit_noPaid_none() {
        consent("subscribed", "explicit", "organizer_import", daysAgo(5));
        assertThat(run().fanClass()).isEqualTo("none");
    }

    @Test
    void importRowFollowedByLaterCheckoutConsent_noPaid_none() {
        consent("subscribed", "explicit", "organizer_import_row", daysAgo(50));
        consent("subscribed", "explicit", "checkout", daysAgo(5));
        assertThat(run().fanClass()).isEqualTo("none");
    }

    @Test
    void importRowLatestSubscribingRecord_evenAfterUnsubscribeBefore_imported() {
        consent("unsubscribed", null, "unsubscribe_link", daysAgo(60));
        consent("subscribed", "explicit", "organizer_import_row", daysAgo(50));
        consent("unsubscribed", null, "unsubscribe_link", daysAgo(1));
        assertThat(run().fanClass()).isEqualTo("imported");
    }

    @ParameterizedTest
    @ValueSource(strings = {"door_qr", "survey"})
    void importRowFollowedByLaterPendingSignUp_keepsImportBasis_untilConfirmed(String source) {
        consent("subscribed", "explicit", "organizer_import_row", daysAgo(50));
        ConsentRecord pending = consent("subscribed", "explicit", source, daysAgo(5));
        pending.setConfirmationRequired(true);
        assertThat(run().fanClass()).isEqualTo("imported");

        pending.setConfirmedAt(daysAgo(4));
        assertThat(run().fanClass()).isEqualTo("none");
    }

    @Test
    void importRowWithNonExplicitBasis_noPaid_none() {
        consent("subscribed", "soft_opt_in", "organizer_import_row", daysAgo(5));
        assertThat(run().fanClass()).isEqualTo("none");
    }

    @Test
    void importRowOnSmsChannel_noPaid_none() {
        consent("subscribed", "explicit", "organizer_import_row", daysAgo(5)).setChannel("sms");
        assertThat(run().fanClass()).isEqualTo("none");
    }

    @Test
    void laterSmsConsent_doesNotHideEmailImportRow_imported() {
        consent("subscribed", "explicit", "organizer_import_row", daysAgo(50));
        consent("subscribed", "explicit", "order_confirmation", daysAgo(5)).setChannel("sms");
        assertThat(run().fanClass()).isEqualTo("imported");
    }

    @Test
    void noImportRecord_noPaid_none() {
        Result r = run();
        assertThat(r.fanClass()).isEqualTo("none");
        assertThat(r.paidOrders()).isZero();
        assertThat(r.daysSinceLastPaid()).isNull();
        assertThat(r.lastContactFromPersonAt()).isNull();
    }

    @Test
    void importRowWithRecentPaidOrder_paidClassWins() {
        consent("subscribed", "explicit", "organizer_import_row", daysAgo(50));
        paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 1);
        assertThat(run().fanClass()).isEqualTo("first_timer");
    }

    // ---- taste ----

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> tasteRows() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("no genre key",
                        List.of(new TasteCalculator.Purchase(null, NOW), new TasteCalculator.Purchase(POP, NOW)),
                        Map.of(POP, 1.0)),
                org.junit.jupiter.params.provider.Arguments.of("no purchase time",
                        List.of(new TasteCalculator.Purchase(TECHNO, null), new TasteCalculator.Purchase(POP, NOW)),
                        Map.of(POP, 1.0)),
                org.junit.jupiter.params.provider.Arguments.of("nothing qualifies",
                        List.of(new TasteCalculator.Purchase(null, null)), Map.of()));
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("tasteRows")
    void taste_skipsPurchasesWithoutGenreOrTime(String name, List<TasteCalculator.Purchase> purchases,
                                                Map<String, Double> expected) {
        assertThat(TasteCalculator.taste(purchases, java.util.Set.of(POP, TECHNO), 180, NOW)).isEqualTo(expected);
    }

    @Test
    void nonWhitelistedGenre_contributesNothing() {
        paidOrder(event("minimal", daysAgo(10)), daysAgo(10), 1);
        paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 1);
        assertThat(run().taste()).containsOnlyKeys(TECHNO).containsEntry(TECHNO, 1.0);
    }

    @Test
    void onlyNonWhitelistedGenres_emptyTaste() {
        paidOrder(event("", daysAgo(10)), daysAgo(10), 1);
        assertThat(run().taste()).isEmpty();
    }

    @Test
    void queerNightEvent_contributesOnlyItsBucket_andIsNotAFormat() {
        Event e = event(POP, daysAgo(10));
        e.setName("Queer Night Special");
        e.setType("Queer night");
        Event club = event(TECHNO, daysAgo(10));
        club.setType(" Club ");
        paidOrder(e, daysAgo(10), 1);
        paidOrder(club, daysAgo(10), 1);
        Result r = run();
        assertThat(r.taste()).containsOnlyKeys(POP, TECHNO);
        assertThat(r.formats()).containsExactly("club");
    }

    @Test
    void formats_onlyClosedWizardTypes_openAirKeyed_freeTextDropped() {
        String[] types = {"Festival", "Rave", "Club", "Concert", "Open Air", "Club Night", "warehouse party"};
        for (String type : types) {
            Event e = event(TECHNO, daysAgo(10));
            e.setType(type);
            paidOrder(e, daysAgo(10), 1);
        }
        assertThat(run().formats()).containsExactly("club", "concert", "festival", "open_air", "rave");
    }

    @Test
    void eventFrom180DaysAgo_weighsHalfOfToday() {
        paidOrder(event(TECHNO, NOW), daysAgo(1), 1);
        paidOrder(event(POP, NOW.minus(Duration.ofDays(180))), daysAgo(181), 1);
        Map<String, Double> taste = run().taste();
        assertThat(taste.get(POP) / taste.get(TECHNO)).isCloseTo(0.5, within(1e-9));
        assertThat(taste.get(TECHNO) + taste.get(POP)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void futureEvent_weighsAsToday() {
        paidOrder(event(TECHNO, NOW.plus(Duration.ofDays(20))), daysAgo(1), 1);
        paidOrder(event(POP, NOW), daysAgo(1), 1);
        Map<String, Double> taste = run().taste();
        assertThat(taste.get(TECHNO)).isCloseTo(0.5, within(1e-9));
        assertThat(taste.get(POP)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void twoOrdersForOneEvent_countOnceInTaste() {
        Event techno = event(TECHNO, NOW);
        paidOrder(techno, daysAgo(2), 1);
        paidOrder(techno, daysAgo(1), 1);
        paidOrder(event(POP, NOW), daysAgo(1), 1);
        Map<String, Double> taste = run().taste();
        assertThat(taste.get(TECHNO)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void eventWithoutStart_agedFromItsFirstOrder() {
        paidOrder(event(TECHNO, null), NOW.minus(Duration.ofDays(180)), 1);
        paidOrder(event(POP, NOW), daysAgo(1), 1);
        Map<String, Double> taste = run().taste();
        assertThat(taste.get(TECHNO) / taste.get(POP)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void objectedProfiling_emptyTasteCitiesFormats_restStillComputed() {
        Event e = event(TECHNO, daysAgo(10));
        e.setVenueCityKey("metz");
        e.setType("Club");
        paidOrder(e, daysAgo(10), 2);
        objected = true;
        Result r = run();
        assertThat(r.taste()).isEmpty();
        assertThat(r.cities()).isEmpty();
        assertThat(r.formats()).isEmpty();
        assertThat(r.paidOrders()).isEqualTo(1);
        assertThat(r.fanClass()).isEqualTo("first_timer");
        assertThat(r.lastContactFromPersonAt()).isEqualTo(daysAgo(10));
        assertThat(r.noShowN()).isEqualTo(1);
        assertThat(r.avgGroupSize()).isEqualByComparingTo(new BigDecimal("2.000"));
    }

    // ---- retention clear (M1-12) ----

    @Test
    void retentionUnsubscribe_withoutLaterContact_emptiesProfiling_restStillComputed() {
        Event e = event(TECHNO, daysAgo(1100));
        e.setVenueCityKey("metz");
        e.setType("Club");
        paidOrder(e, daysAgo(1100), 1);
        consent("unsubscribed", null, FanFeatureCalculator.RETENTION_SOURCE, daysAgo(3));
        Result r = run();
        assertThat(r.taste()).isEmpty();
        assertThat(r.cities()).isEmpty();
        assertThat(r.formats()).isEmpty();
        assertThat(r.paidOrders()).isEqualTo(1);
        assertThat(r.lastContactFromPersonAt()).isEqualTo(daysAgo(1100));
    }

    @Test
    void purchaseAfterRetentionUnsubscribe_restoresProfiling() {
        Event old = event(TECHNO, daysAgo(1100));
        paidOrder(old, daysAgo(1100), 1);
        consent("unsubscribed", null, FanFeatureCalculator.RETENTION_SOURCE, daysAgo(3));
        Event fresh = event(TECHNO, daysAgo(1));
        fresh.setVenueCityKey("metz");
        paidOrder(fresh, daysAgo(1), 1);
        Result r = run();
        assertThat(r.taste()).containsOnlyKeys(TECHNO);
        assertThat(r.cities()).containsExactly("metz");
    }

    @Test
    void retentionUnsubscribeOnSmsChannel_doesNotEmptyProfiling() {
        Event e = event(TECHNO, daysAgo(1100));
        paidOrder(e, daysAgo(1100), 1);
        consent("unsubscribed", null, FanFeatureCalculator.RETENTION_SOURCE, daysAgo(3)).setChannel("sms");
        assertThat(run().taste()).containsOnlyKeys(TECHNO);
    }

    @Test
    void operatorUnsubscribeWithAnotherSource_doesNotEmptyProfiling() {
        Event e = event(TECHNO, daysAgo(1100));
        paidOrder(e, daysAgo(1100), 1);
        consent("unsubscribed", null, "manual", daysAgo(3));
        assertThat(run().taste()).containsOnlyKeys(TECHNO);
    }

    @Test
    void retentionCleared_contactAtTheSameInstantOrNone_staysCleared_laterContactLifts() {
        ConsentRecord r = consent("unsubscribed", null, FanFeatureCalculator.RETENTION_SOURCE, daysAgo(3));
        assertThat(FanFeatureCalculator.retentionCleared(List.of(r), null)).isTrue();
        assertThat(FanFeatureCalculator.retentionCleared(List.of(r), daysAgo(3))).isTrue();
        assertThat(FanFeatureCalculator.retentionCleared(List.of(r), daysAgo(2))).isFalse();
        assertThat(FanFeatureCalculator.retentionCleared(List.of(), null)).isFalse();
    }

    @Test
    void retentionCleared_latestOfSeveralClearsDecides() {
        ConsentRecord first = consent("unsubscribed", null, FanFeatureCalculator.RETENTION_SOURCE, daysAgo(400));
        ConsentRecord second = consent("unsubscribed", null, FanFeatureCalculator.RETENTION_SOURCE, daysAgo(3));
        assertThat(FanFeatureCalculator.retentionCleared(List.of(second, first), daysAgo(10))).isTrue();
    }

    // ---- inputs that must never count (C13, C14) ----

    @Test
    void recentEmailOpenAndOrganizerTypedColumns_areNotInputs() {
        // Input has no membership: last_email_open/click, city, genres, tags, vibe and notes cannot reach the calculator.
        assertThat(Input.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("orgId", "orgZone", "objectedProfiling", "orders", "tickets", "events",
                        "consents", "surveyResponseAts");
        paidOrder(event(TECHNO, daysAgo(1096)), daysAgo(1096), 1);
        Result r = run();
        assertThat(r.lastContactFromPersonAt()).isEqualTo(daysAgo(1096));
        assertThat(r.fanClass()).isEqualTo("none");
    }

    // ---- no-shows, group size, cities, version ----

    @Test
    void noShow_countedOnlyForEndedEvents() {
        Event ended = event(TECHNO, daysAgo(20));
        ended.setEndsAt(daysAgo(20).plus(Duration.ofHours(6)));
        Event upcoming = event(TECHNO, NOW.plus(Duration.ofDays(3)));
        Event startedNotEnded = event(TECHNO, NOW.minus(Duration.ofHours(1)));
        startedNotEnded.setEndsAt(NOW.plus(Duration.ofHours(5)));
        Event endedNoEnd = event(POP, daysAgo(30));
        paidOrder(ended, daysAgo(25), 2);
        paidOrder(upcoming, daysAgo(2), 1);
        paidOrder(startedNotEnded, daysAgo(2), 1);
        paidOrder(endedNoEnd, daysAgo(31), 1);
        assertThat(run().noShowN()).isEqualTo(2);
    }

    @Test
    void noShow_notCountedForEventWithNoStartOrEnd() {
        paidOrder(event(TECHNO, null), daysAgo(40), 1);
        assertThat(run().noShowN()).isZero();
    }

    @Test
    void orderForAnotherOrgsOrMissingEvent_countsAsPaid_butAddsNoEventFeatures() {
        Event foreign = event(POP, daysAgo(30));
        foreign.setOrgId(OTHER_ORG);
        foreign.setVenueCityKey("paris");
        foreign.setType("Club");
        paidOrder(foreign, daysAgo(30), 1);
        Event missing = new Event();
        missing.setId(UUID.randomUUID());
        paidOrder(missing, daysAgo(20), 1);
        Result r = run();
        assertThat(r.paidOrders()).isEqualTo(2);
        assertThat(r.taste()).isEmpty();
        assertThat(r.cities()).isEmpty();
        assertThat(r.formats()).isEmpty();
        assertThat(r.noShowN()).isZero();
    }

    @Test
    void noShow_notCountedWhenAnyTicketRedeemed() {
        Event ended = event(TECHNO, daysAgo(20));
        Order o = paidOrder(ended, daysAgo(25), 3);
        ticketsOf(o).get(1).setState(Ticket.STATE_REDEEMED);
        assertThat(run().noShowN()).isZero();
    }

    @Test
    void noShow_partiallyRefundedOrderStillCounts() {
        Event ended = event(TECHNO, daysAgo(20));
        Order o = paidOrder(ended, daysAgo(25), 2);
        ticketsOf(o).get(0).setState(Ticket.STATE_REDEEMED);
        ticketsOf(o).get(1).setState(Ticket.STATE_REFUNDED);
        Event other = event(POP, daysAgo(15));
        Order o2 = paidOrder(other, daysAgo(16), 2);
        ticketsOf(o2).get(0).setState(Ticket.STATE_REFUNDED);
        assertThat(run().noShowN()).isEqualTo(1);
    }

    @Test
    void avgGroupSize_isMeanCountableTicketsPerPaidOrder() {
        paidOrder(event(TECHNO, daysAgo(10)), daysAgo(10), 1);
        paidOrder(event(TECHNO, daysAgo(5)), daysAgo(5), 2);
        paidOrder(event(TECHNO, daysAgo(3)), daysAgo(3), 2);
        assertThat(run().avgGroupSize()).isEqualByComparingTo(new BigDecimal("1.667"));
    }

    @Test
    void cities_distinctSortedNonBlankCityKeys() {
        Event metz = event(TECHNO, daysAgo(10));
        metz.setVenueCityKey("metz");
        Event nancy = event(TECHNO, daysAgo(5));
        nancy.setVenueCityKey("nancy");
        Event metz2 = event(POP, daysAgo(4));
        metz2.setVenueCityKey("metz");
        Event blank = event(POP, daysAgo(3));
        paidOrder(nancy, daysAgo(5), 1);
        paidOrder(metz, daysAgo(10), 1);
        paidOrder(metz2, daysAgo(4), 1);
        paidOrder(blank, daysAgo(3), 1);
        Result r = run();
        assertThat(r.cities()).containsExactly("metz", "nancy");
        assertThat(r.formats()).isEmpty();
    }

    @Test
    void logicVersion_copiedFromLogicFile() {
        assertThat(run().logicVersion()).isEqualTo(LOGIC.logicVersion()).isEqualTo(1);
    }

    // ---- helpers ----

    private Result run() {
        return calc.calculate(new Input(ORG, PARIS, objected, orders, tickets, events, consents, surveys), CLOCK);
    }

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    private Event event(String genreKey, Instant startsAt) {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setOrgId(ORG);
        e.setGenreKey(genreKey);
        e.setStartsAt(startsAt);
        events.add(e);
        return e;
    }

    private Order paidOrder(Event event, Instant createdAt, int ticketCount) {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setOrgId(ORG);
        o.setEventId(event.getId());
        o.setPaymentMethod("stripe");
        o.setTotalMinor(2_000L * ticketCount);
        o.setCreatedAt(createdAt);
        orders.add(o);
        for (int i = 0; i < ticketCount; i++) {
            Ticket t = new Ticket();
            t.setId(UUID.randomUUID());
            t.setOrderId(o.getId());
            t.setEventId(event.getId());
            tickets.add(t);
        }
        return o;
    }

    private List<Ticket> ticketsOf(Order o) {
        return tickets.stream().filter(t -> t.getOrderId().equals(o.getId())).toList();
    }

    private ConsentRecord consent(String status, String basis, String source, Instant at) {
        ConsentRecord c = new ConsentRecord();
        c.setId(UUID.randomUUID());
        c.setStatus(status);
        c.setLawfulBasis(basis);
        c.setSource(source);
        c.setOccurredAt(at);
        consents.add(c);
        return c;
    }

    private static AudiencePlanLogic shipped() {
        ClassLoader cl = FanFeatureCalculatorTest.class.getClassLoader();
        try (InputStream logic = cl.getResourceAsStream("audienceplan/logic-v1.yaml");
             InputStream priors = cl.getResourceAsStream("audienceplan/priors-v1.yaml");
             InputStream genres = cl.getResourceAsStream("audienceplan/genres-v1.yaml")) {
            return LogicLoader.parse(logic, priors, genres);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
