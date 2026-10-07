package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.publicapi.QuoteRequest;
import com.imin.iminapi.dto.publicapi.QuoteResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IminIntegrationTest
// Read-only service: rollback keeps these LIVE PUBLIC events out of every other class's global listing.
@Transactional
class QuoteServiceTest {

    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired QuoteService quoteService;
    @Autowired EventRepository eventRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired TicketTierRepository ticketTierRepository;
    @Autowired PromoCodeRepository promoCodeRepository;
    @Autowired UserRepository userRepository;
    @Autowired MutableClock clock;

    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);

        org = new Organization();
        org.setName("Quote Test Org");
        org.setSlug("quote-org-" + UUID.randomUUID());
        org.setContactEmail("quote-org@example.com");
        org.setCountry("DE");
        org = organizationRepository.save(org);

        owner = new User();
        owner.setEmail("quote-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = userRepository.save(owner);
    }

    // ---- fixtures ----------------------------------------------------------

    Event publishedLiveEvent() {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Quote Event");
        e.setSlug("quote-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(NOW.minusSeconds(3600));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return eventRepository.save(e);
    }

    Event draftEvent() {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Draft Event");
        e.setSlug("draft-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        // An unpublished event keeps its publishedAt, so only the status check hides it.
        e.setPublishedAt(NOW.minusSeconds(3600));
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return eventRepository.save(e);
    }

    Event cancelledEvent() {
        Event e = publishedLiveEvent();
        e.setStatus(EventStatus.CANCELLED);
        return eventRepository.save(e);
    }

    TicketTier tier(UUID eventId, int priceMinor) {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(priceMinor);
        t.setQuantity(100);
        t.setEnabled(true);
        return ticketTierRepository.save(t);
    }

    PromoCode promo(UUID eventId, String code, int pct, int maxUses,
                            int usedCount, boolean enabled) {
        PromoCode p = new PromoCode();
        p.setEventId(eventId);
        p.setCode(code);
        p.setDiscountPct(pct);
        p.setMaxUses(maxUses);
        p.setUsedCount(usedCount);
        p.setEnabled(enabled);
        return promoCodeRepository.save(p);
    }

    // ---- (a) 200 with no promo, totals match -------------------------------

    @Test
    void quote_returns200_withTotals_whenNoPromo() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        QuoteResponse r = quoteService.quote(e.getId(),
                new QuoteRequest(t.getId(), 2, null, null));

        // Fee = 99 × 2 + round(5000 × 5%) = 198 + 250 = 448.
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.unitPriceMinor()).isEqualTo(2500);
        assertThat(r.subtotalMinor()).isEqualTo(5000L);
        assertThat(r.discountMinor()).isZero();
        assertThat(r.feeMinor()).isEqualTo(448L);
        assertThat(r.totalMinor()).isEqualTo(5448L);
        assertThat(r.promo()).isNull();
    }

    // ---- (b) 200 with valid promo applies discount -------------------------

    @Test
    void quote_appliesDiscount_whenPromoValid() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);
        promo(e.getId(), "FRIENDS10", 10, 50, 0, true);

        QuoteResponse r = quoteService.quote(e.getId(),
                new QuoteRequest(t.getId(), 2, "friends10", null)); // case-insensitive

        // Fee follows the undiscounted subtotal: 99 × 2 + round(5000 × 5%) = 448.
        assertThat(r.subtotalMinor()).isEqualTo(5000L);
        assertThat(r.discountMinor()).isEqualTo(500L);
        assertThat(r.feeMinor()).isEqualTo(448L);
        assertThat(r.totalMinor()).isEqualTo(4948L);
        assertThat(r.promo()).isNotNull();
        assertThat(r.promo().applied()).isTrue();
        assertThat(r.promo().code()).isEqualTo("FRIENDS10");
        assertThat(r.promo().discountPct()).isEqualTo(10);
        assertThat(r.promo().reason()).isNull();

        // Promo must NOT have been incremented (that's for the paid webhook).
        PromoCode reloaded = promoCodeRepository.findByEventIdAndCodeIgnoreCase(
                e.getId(), "FRIENDS10").orElseThrow();
        assertThat(reloaded.getUsedCount()).isZero();
    }

    // ---- (c) 200 with an unusable promo: applied=false + reason ---------
    // (PromoCode model has no `expiresAt`; the closest "expired-like" state in
    //  the schema is `usedCount >= maxUses`.)

    static Stream<Arguments> rejectedPromos() {
        return Stream.of(
                Arguments.of("unknown", "NOPE", null, "Invalid code"),
                Arguments.of("exhausted", "SOLDOUT", new int[] {15, 50, 50, 1}, "Promo code has reached its usage limit"),
                Arguments.of("disabled", "OFF", new int[] {25, 50, 0, 0}, "Promo code is no longer active"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedPromos")
    void quote_returnsAppliedFalse_whenPromoUnusable(String name, String code, int[] stored, String reason) {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);
        if (stored != null) promo(e.getId(), code, stored[0], stored[1], stored[2], stored[3] == 1);

        QuoteResponse r = quoteService.quote(e.getId(),
                new QuoteRequest(t.getId(), 2, code, null));

        // No discount; the fee still applies (99 × 2 + 5% × 5000 = 448).
        assertThat(r.discountMinor()).isZero();
        assertThat(r.feeMinor()).isEqualTo(448L);
        assertThat(r.totalMinor()).isEqualTo(5448L);
        assertThat(r.promo()).isNotNull();
        assertThat(r.promo().applied()).isFalse();
        assertThat(r.promo().code()).isEqualTo(code);
        assertThat(r.promo().discountPct()).isZero();
        assertThat(r.promo().reason()).isEqualTo(reason);
    }

    // ---- 404: event not buyable, or tier not buyable on this event -----
    // A CANCELLED event stays reachable by share-link (EventRepository.findPublic is
    // deliberately CANCELLED-tolerant so the detail page can render the banner), but a
    // buyer holding a tierId must not be able to price or buy a ticket for it.

    /** Returns {eventId, tierId} for the quote that must 404. */
    static Stream<Arguments> notBuyable() {
        return Stream.of(
                Arguments.of("draft event", (Function<QuoteServiceTest, UUID[]>) t -> {
                    Event e = t.draftEvent();
                    return new UUID[] {e.getId(), t.tier(e.getId(), 2500).getId()};
                }),
                Arguments.of("cancelled event", (Function<QuoteServiceTest, UUID[]>) t -> {
                    Event e = t.cancelledEvent();
                    return new UUID[] {e.getId(), t.tier(e.getId(), 2500).getId()};
                }),
                Arguments.of("past event", (Function<QuoteServiceTest, UUID[]>) t -> {
                    Event e = t.publishedLiveEvent();
                    e.setStatus(EventStatus.PAST);
                    t.eventRepository.save(e);
                    return new UUID[] {e.getId(), t.tier(e.getId(), 2500).getId()};
                }),
                // The event-level sale window is enforced even when the tier itself has none.
                Arguments.of("event sale not opened yet", (Function<QuoteServiceTest, UUID[]>) t -> {
                    Event e = t.publishedLiveEvent();
                    e.setOnSaleAt(NOW.plusSeconds(3600));
                    t.eventRepository.save(e);
                    return new UUID[] {e.getId(), t.tier(e.getId(), 2500).getId()};
                }),
                Arguments.of("event sale closed", (Function<QuoteServiceTest, UUID[]>) t -> {
                    Event e = t.publishedLiveEvent();
                    e.setSaleClosesAt(NOW.minusSeconds(60));
                    t.eventRepository.save(e);
                    return new UUID[] {e.getId(), t.tier(e.getId(), 2500).getId()};
                }),
                Arguments.of("tier of a different event", (Function<QuoteServiceTest, UUID[]>) t -> {
                    Event a = t.publishedLiveEvent();
                    Event b = t.publishedLiveEvent();
                    return new UUID[] {a.getId(), t.tier(b.getId(), 2500).getId()};
                }),
                Arguments.of("tier sale closed", (Function<QuoteServiceTest, UUID[]>) t -> {
                    Event e = t.publishedLiveEvent();
                    TicketTier tier = t.tier(e.getId(), 2000);
                    tier.setSaleClosesAt(NOW.minusSeconds(60)); // closed a minute ago
                    t.ticketTierRepository.save(tier);
                    return new UUID[] {e.getId(), tier.getId()};
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notBuyable")
    void quote_returns404_whenNotBuyable(String name, Function<QuoteServiceTest, UUID[]> arrange) {
        UUID[] ids = arrange.apply(this);

        assertThatThrownBy(() ->
                quoteService.quote(ids[0], new QuoteRequest(ids[1], 1, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // ---- 400: malformed body -------------------------------------------

    static Stream<Arguments> malformed() {
        return Stream.of(
                Arguments.of("quantity zero", true, 0, null, "quantity"),
                Arguments.of("quantity missing", true, null, null, "quantity"),
                Arguments.of("tierId missing", false, 1, null, "tierId"),
                Arguments.of("promoCode blank", true, 1, "   ", "promoCode"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    void quote_returns400_whenBodyMalformed(String name, boolean withTier, Integer quantity, String promoCode,
                                            String field) {
        Event e = publishedLiveEvent();
        UUID tierId = withTier ? tier(e.getId(), 2500).getId() : null;

        assertThatThrownBy(() ->
                quoteService.quote(e.getId(), new QuoteRequest(tierId, quantity, promoCode, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(api.fields()).containsOnlyKeys(field);
                });
    }

    // ---- price-drift detection ---------------------------------------------

    @Test
    void quote_returns200_whenExpectedPriceMatches() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        QuoteResponse r = quoteService.quote(e.getId(),
                new QuoteRequest(t.getId(), 1, null, 2500));

        assertThat(r.unitPriceMinor()).isEqualTo(2500);
    }

    @Test
    void quote_returns409PriceChanged_whenExpectedPriceMismatches() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        assertThatThrownBy(() ->
                quoteService.quote(e.getId(), new QuoteRequest(t.getId(), 1, null, 2000)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException ae = (ApiException) ex;
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.PRICE_CHANGED);
                    assertThat(ae.fields()).containsEntry("currentPriceMinor", "2500");
                });
    }

    // ---- free / zero-total quote (Feature 5) -------------------------------

    @Test
    void quote_returnsTotalZero_whenTierIsFree() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 0);

        QuoteResponse r = quoteService.quote(e.getId(),
                new QuoteRequest(t.getId(), 1, null, null));

        assertThat(r.unitPriceMinor()).isEqualTo(0);
        assertThat(r.subtotalMinor()).isEqualTo(0L);
        assertThat(r.discountMinor()).isEqualTo(0L);
        assertThat(r.feeMinor()).isEqualTo(0L);
        assertThat(r.totalMinor()).isEqualTo(0L);
        assertThat(r.promo()).isNull();
    }

    // A 100%-off promo on a PAID tier zeroes the net, so the fee is waived too —
    // checkout routes this order down the no-Stripe free path (netTotal == 0), so a
    // quoted fee would promise a charge that never happens (W0.2).
    @Test
    void quote_waivesFee_whenPromoZeroesPaidTier() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);
        promo(e.getId(), "COMP100", 100, 50, 0, true);

        QuoteResponse r = quoteService.quote(e.getId(),
                new QuoteRequest(t.getId(), 2, "COMP100", null));

        assertThat(r.subtotalMinor()).isEqualTo(5000L);
        assertThat(r.discountMinor()).isEqualTo(5000L);
        assertThat(r.feeMinor()).isZero();
        assertThat(r.totalMinor()).isZero();
        assertThat(r.promo()).isNotNull();
        assertThat(r.promo().applied()).isTrue();
        assertThat(r.promo().discountPct()).isEqualTo(100);
    }
}
