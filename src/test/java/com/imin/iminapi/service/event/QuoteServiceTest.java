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
import com.imin.iminapi.stripe.StripeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuoteService.class, QuoteServiceTest.FixedClockConfig.class})
@EnableConfigurationProperties(StripeProperties.class)
class QuoteServiceTest {

    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @Autowired QuoteService quoteService;
    @Autowired EventRepository eventRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired TicketTierRepository ticketTierRepository;
    @Autowired PromoCodeRepository promoCodeRepository;
    @Autowired UserRepository userRepository;

    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.setName("Quote Test Org");
        org.setSlug("quote-org-" + UUID.randomUUID().toString().substring(0, 8));
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

    private Event publishedLiveEvent() {
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

    private Event draftEvent() {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Draft Event");
        e.setSlug("draft-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return eventRepository.save(e);
    }

    private TicketTier tier(UUID eventId, int priceMinor) {
        TicketTier t = new TicketTier();
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(priceMinor);
        t.setQuantity(100);
        t.setEnabled(true);
        return ticketTierRepository.save(t);
    }

    private PromoCode promo(UUID eventId, String code, int pct, int maxUses,
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

    // ---- (c) 200 with invalid promo: applied=false + reason ----------------

    @Test
    void quote_returnsAppliedFalse_whenPromoUnknown() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        QuoteResponse r = quoteService.quote(e.getId(),
                new QuoteRequest(t.getId(), 2, "NOPE", null));

        assertThat(r.discountMinor()).isZero();
        // Invalid promo → no discount; fee still applies (99 × 2 + 5% × 5000 = 448).
        assertThat(r.feeMinor()).isEqualTo(448L);
        assertThat(r.totalMinor()).isEqualTo(5448L);
        assertThat(r.promo()).isNotNull();
        assertThat(r.promo().applied()).isFalse();
        assertThat(r.promo().code()).isEqualTo("NOPE");
        assertThat(r.promo().reason()).isEqualTo("Invalid code");
    }

    // ---- (d) 200 with exhausted promo: applied=false + reason --------------
    // (PromoCode model has no `expiresAt`; the closest "expired-like" state in
    //  the schema is `usedCount >= maxUses`. Disabled is covered separately.)

    @Test
    void quote_returnsAppliedFalse_whenPromoExhausted() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);
        promo(e.getId(), "SOLDOUT", 15, 50, 50, true);

        QuoteResponse r = quoteService.quote(e.getId(),
                new QuoteRequest(t.getId(), 1, "SOLDOUT", null));

        assertThat(r.discountMinor()).isZero();
        assertThat(r.promo().applied()).isFalse();
        assertThat(r.promo().code()).isEqualTo("SOLDOUT");
        assertThat(r.promo().reason()).contains("usage limit");
    }

    @Test
    void quote_returnsAppliedFalse_whenPromoDisabled() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);
        promo(e.getId(), "OFF", 25, 50, 0, false);

        QuoteResponse r = quoteService.quote(e.getId(),
                new QuoteRequest(t.getId(), 1, "OFF", null));

        assertThat(r.promo().applied()).isFalse();
        assertThat(r.promo().reason()).contains("no longer active");
    }

    // ---- (e) 404 on draft event --------------------------------------------

    @Test
    void quote_returns404_onDraftEvent() {
        Event e = draftEvent();
        TicketTier t = tier(e.getId(), 2500);

        assertThatThrownBy(() ->
                quoteService.quote(e.getId(), new QuoteRequest(t.getId(), 1, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    // ---- (f) 404 on tier from different event ------------------------------

    @Test
    void quote_returns404_whenTierBelongsToDifferentEvent() {
        Event a = publishedLiveEvent();
        Event b = publishedLiveEvent();
        TicketTier tOfB = tier(b.getId(), 2500);

        // Request quote on event A with a tier owned by event B.
        assertThatThrownBy(() ->
                quoteService.quote(a.getId(), new QuoteRequest(tOfB.getId(), 1, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    // ---- (g) 400 on quantity=0 ---------------------------------------------

    @Test
    void quote_returns400_whenQuantityZero() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        assertThatThrownBy(() ->
                quoteService.quote(e.getId(), new QuoteRequest(t.getId(), 0, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(api.fields()).containsKey("quantity");
                });
    }

    @Test
    void quote_returns400_whenQuantityMissing() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        assertThatThrownBy(() ->
                quoteService.quote(e.getId(), new QuoteRequest(t.getId(), null, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.fields()).containsKey("quantity");
                });
    }

    @Test
    void quote_returns400_whenTierIdMissing() {
        Event e = publishedLiveEvent();

        assertThatThrownBy(() ->
                quoteService.quote(e.getId(), new QuoteRequest(null, 1, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.fields()).containsKey("tierId");
                });
    }

    @Test
    void quote_returns400_whenPromoCodeBlank() {
        Event e = publishedLiveEvent();
        TicketTier t = tier(e.getId(), 2500);

        assertThatThrownBy(() ->
                quoteService.quote(e.getId(), new QuoteRequest(t.getId(), 1, "   ", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.fields()).containsKey("promoCode");
                });
    }

    // ---- bonus: tier with closed sale window → 404 -------------------------

    @Test
    void quote_returns404_whenTierSaleHasClosed() {
        Event e = publishedLiveEvent();
        TicketTier t = new TicketTier();
        t.setEventId(e.getId());
        t.setName("Early bird");
        t.setPriceMinor(2000);
        t.setQuantity(50);
        t.setEnabled(true);
        t.setSaleClosesAt(NOW.minusSeconds(60)); // closed a minute ago
        ticketTierRepository.save(t);

        assertThatThrownBy(() ->
                quoteService.quote(e.getId(), new QuoteRequest(t.getId(), 1, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status())
                        .isEqualTo(HttpStatus.NOT_FOUND));
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
