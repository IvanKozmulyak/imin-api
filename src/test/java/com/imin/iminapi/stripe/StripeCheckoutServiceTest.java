package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.event.FreeCheckoutService;
import com.imin.iminapi.service.event.InventoryService;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.service.CheckoutService;
import com.stripe.service.checkout.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeCheckoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private StripeClient stripeClient;
    private CheckoutService checkoutService;
    private SessionService sessionService;
    private EventRepository events;
    private TicketTierRepository tiers;
    private OrganizationRepository orgs;
    private PromoCodeRepository promos;
    private StripeConnectService connectService;
    private InventoryService inventoryService;
    private FreeCheckoutService freeCheckoutService;
    private StripeProductService productService;
    private StripeProperties props;
    private Clock clock;
    private StripeCheckoutService svc;

    private final UUID eventId = UUID.randomUUID();
    private final UUID tierId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        stripeClient = mock(StripeClient.class);
        checkoutService = mock(CheckoutService.class);
        sessionService = mock(SessionService.class);
        when(stripeClient.checkout()).thenReturn(checkoutService);
        when(checkoutService.sessions()).thenReturn(sessionService);

        events = mock(EventRepository.class);
        tiers = mock(TicketTierRepository.class);
        orgs = mock(OrganizationRepository.class);
        promos = mock(PromoCodeRepository.class);
        connectService = mock(StripeConnectService.class);
        inventoryService = mock(InventoryService.class);
        freeCheckoutService = mock(FreeCheckoutService.class);
        productService = mock(StripeProductService.class);
        props = new StripeProperties();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        svc = new StripeCheckoutService(stripeClient, events, tiers, orgs, promos,
                connectService, inventoryService, freeCheckoutService, props, productService, clock);

        // Default happy-path wiring
        Event event = event();
        Organization org = org();
        TicketTier tier = tier();
        when(events.findPublic(eventId)).thenReturn(Optional.of(event));
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(tier));
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));
        when(connectService.getStatusLive(eq(orgId)))
                .thenReturn(new StripeConnectService.StatusResult("acct_test",
                        com.imin.iminapi.stripe.StripeConnectState.ACTIVE,
                        true, true, java.util.List.of(), java.util.List.of(), null));

        Session session = mock(Session.class);
        when(session.getUrl()).thenReturn("https://checkout.stripe.com/c/pay/cs_test");
        when(session.getId()).thenReturn("cs_test");
        when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);

        // Reservation id is returned by reserve() and stamped into metadata.
        when(inventoryService.reserve(eq(tierId), anyInt(),
                any(Instant.class), nullable(String.class)))
                .thenReturn(reservationId);
    }

    private Event event() {
        Event e = new Event();
        e.setId(eventId);
        e.setOrgId(orgId);
        e.setName("Test Event");
        e.setSlug("test-event");
        e.setStatus(EventStatus.LIVE);
        e.setVisibility(EventVisibility.PUBLIC);
        e.setPublishedAt(NOW.minusSeconds(3600));
        return e;
    }

    private Organization org() {
        Organization o = new Organization();
        o.setId(orgId);
        o.setName("Test Org");
        o.setContactEmail("o@example.com");
        o.setCountry("DE");
        o.setStripeAccountId("acct_test");
        return o;
    }

    private TicketTier tier() {
        TicketTier t = new TicketTier();
        t.setId(tierId);
        t.setEventId(eventId);
        t.setName("GA");
        t.setPriceMinor(1000);
        t.setQuantity(100);
        t.setSold(0);
        t.setReserved(0);
        t.setEnabled(true);
        t.setStripePriceId("price_test_123");
        return t;
    }

    @Test
    void createCheckoutSession_reservesInventoryBeforeStripeSessionCreate() throws Exception {
        // Default TTL is 30 minutes; reserve() must be called with the same expiresAt
        // we'll later stamp onto the Stripe session.
        props.setCheckoutSessionTtlMinutes(30);
        Instant expectedExpires = NOW.plus(Duration.ofMinutes(30));

        String url = svc.createCheckoutSession(eventId, tierId, 2, null);

        assertThat(url).isEqualTo("https://checkout.stripe.com/c/pay/cs_test");

        // Order matters: inventory must be reserved BEFORE the Stripe session is created,
        // so we never hand a buyer a checkout URL we can't honor.
        InOrder ord = inOrder(inventoryService, sessionService);
        ord.verify(inventoryService).reserve(eq(tierId), eq(2), eq(expectedExpires),
                isNull());
        ord.verify(sessionService).create(any(SessionCreateParams.class));
    }

    @Test
    void ticketLineItemUsesTheTierPriceNotTheStoredStripePrice() throws Exception {
        // The stored Stripe Price can be stale if a product re-sync missed; tier.priceMinor is
        // what the buyer was quoted and what the amount gate later recomputes.
        TicketTier t = tier();
        t.setStripeProductId("prod_test_123");
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(t));

        svc.createCheckoutSession(eventId, tierId, 2, null);

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());
        SessionCreateParams.LineItem ticketLine = captor.getValue().getLineItems().get(0);
        assertThat(ticketLine.getPrice()).isNull();
        assertThat(ticketLine.getQuantity()).isEqualTo(2L);
        assertThat(ticketLine.getPriceData().getUnitAmount()).isEqualTo(1000L);
        assertThat(ticketLine.getPriceData().getCurrency()).isEqualTo("eur");
        // Keeps the one-shot coupon's applies_to.products scoping able to bind.
        assertThat(ticketLine.getPriceData().getProduct()).isEqualTo("prod_test_123");
    }

    @Test
    void fallsBackToProductDataWhenTheProductIdIsNull() throws Exception {
        // Tier fixture has no stripeProductId — a promo then cannot be product-scoped.
        svc.createCheckoutSession(eventId, tierId, 1, null);

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());
        SessionCreateParams.LineItem.PriceData priceData =
                captor.getValue().getLineItems().get(0).getPriceData();
        assertThat(priceData.getProduct()).isNull();
        assertThat(priceData.getProductData().getName()).isEqualTo("GA");
        assertThat(priceData.getUnitAmount()).isEqualTo(1000L);
    }

    @Test
    void createCheckoutSession_stampsReservationAndInventoryMetadata() throws Exception {
        svc.createCheckoutSession(eventId, tierId, 3, null);

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());

        SessionCreateParams sent = captor.getValue();
        // reservation_id is the primary inventory key for the webhook handler.
        assertThat(sent.getMetadata()).containsEntry("reservation_id", reservationId.toString());
        // tier_id/qty/event_id remain for issuance (PaidCheckoutService reads them).
        assertThat(sent.getMetadata()).containsEntry("tier_id", tierId.toString());
        assertThat(sent.getMetadata()).containsEntry("qty", "3");
        assertThat(sent.getMetadata()).containsEntry("event_id", eventId.toString());
    }

    @Test
    void createCheckoutSession_mirrorsMetadataOntoPaymentIntent() throws Exception {
        // The webhook handler keys off payment_intent.succeeded for fulfilment, so the PI
        // must carry the same reservation_id/tier_id/qty/event_id that the Session does.
        svc.createCheckoutSession(eventId, tierId, 3, null);

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());

        SessionCreateParams.PaymentIntentData pid = captor.getValue().getPaymentIntentData();
        assertThat(pid).isNotNull();
        assertThat(pid.getMetadata()).containsEntry("reservation_id", reservationId.toString());
        assertThat(pid.getMetadata()).containsEntry("tier_id", tierId.toString());
        assertThat(pid.getMetadata()).containsEntry("qty", "3");
        assertThat(pid.getMetadata()).containsEntry("event_id", eventId.toString());
    }

    /**
     * W1.G/V78: the paid path has no Order until the webhook fires, so the buyer's UI
     * language has to ride the Stripe metadata on BOTH the Session and the PaymentIntent
     * (fulfilment reads the PI). Normalized on the way in — the stored tag is lowercase.
     */
    @Test
    void createCheckoutSession_stampsBuyerLocaleOntoSessionAndPiMetadata() throws Exception {
        svc.createCheckoutSession(eventId, tierId, 1, null, null, "buyer@example.com",
                false, false, com.imin.iminapi.model.CheckoutAttribution.NONE, "ES");

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());

        assertThat(captor.getValue().getMetadata()).containsEntry("buyer_locale", "es");
        assertThat(captor.getValue().getPaymentIntentData().getMetadata())
                .containsEntry("buyer_locale", "es");
    }

    /**
     * An unsupported tag is dropped rather than stored: a missing key means "no
     * preference", which is a different thing from "the buyer chose English".
     */
    @Test
    void createCheckoutSession_omitsBuyerLocaleMetadata_whenUnsupportedOrAbsent() throws Exception {
        svc.createCheckoutSession(eventId, tierId, 1, null, null, "buyer@example.com",
                false, false, com.imin.iminapi.model.CheckoutAttribution.NONE, "kl");

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());

        assertThat(captor.getValue().getMetadata()).doesNotContainKey("buyer_locale");
        assertThat(captor.getValue().getPaymentIntentData().getMetadata())
                .doesNotContainKey("buyer_locale");
    }

    @Test
    void createCheckoutSession_stampsAdsConsentTrueOntoSessionAndPiMetadata() throws Exception {
        // The paid path has no Order to write until the webhook fires, so the buyer's
        // ads-consent (§7) must ride Stripe session + PI metadata for PaidCheckoutService
        // to snapshot onto orders.ads_consent.
        svc.createCheckoutSession(eventId, tierId, 1, null, null, "buyer@example.com", true, false);

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());
        SessionCreateParams sent = captor.getValue();

        assertThat(sent.getMetadata()).containsEntry("ads_consent", "true");
        assertThat(sent.getPaymentIntentData().getMetadata()).containsEntry("ads_consent", "true");
    }

    @Test
    void createCheckoutSession_stampsAdsConsentFalse_whenBuyerDidNotConsent() throws Exception {
        // Default (no consent) must persist the flag as "false", never omit it — the webhook
        // read-back keys off exactly "true".
        svc.createCheckoutSession(eventId, tierId, 1, null, null, "buyer@example.com", false, false);

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());

        assertThat(captor.getValue().getMetadata()).containsEntry("ads_consent", "false");
        assertThat(captor.getValue().getPaymentIntentData().getMetadata())
                .containsEntry("ads_consent", "false");
    }

    @Test
    void createCheckoutSession_setsExpiresAtMatchingConfiguredTtl() throws Exception {
        // Stripe's documented minimum lifetime is 30 minutes — anything shorter rejects.
        // Mirrored onto TicketReservation.expires_at so the sweeper can self-heal.
        props.setCheckoutSessionTtlMinutes(30);
        svc.createCheckoutSession(eventId, tierId, 1, null);

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());

        Long expiresAt = captor.getValue().getExpiresAt();
        assertThat(expiresAt).isNotNull();
        long expected = NOW.plus(Duration.ofMinutes(30)).getEpochSecond();
        assertThat(expiresAt).isEqualTo(expected);
    }

    @Test
    void createCheckoutSession_attachesSessionIdToReservation_onSuccess() throws Exception {
        svc.createCheckoutSession(eventId, tierId, 1, null);

        verify(inventoryService).attachSessionId(eq(reservationId), eq("cs_test"));
    }

    /**
     * events-1: the tier-level sale window was the only gate on the buy path, so a buyer
     * holding a tierId could reserve inventory and be charged before the event-level
     * on-sale time. Leak-safe 404, same shape the tier-level checks use.
     */
    @Test
    void createCheckoutSession_returns404_whenEventOnSaleAtIsInTheFuture() throws Exception {
        Event notYetOnSale = event();
        notYetOnSale.setOnSaleAt(NOW.plusSeconds(3600));
        when(events.findPublic(eventId)).thenReturn(Optional.of(notYetOnSale));

        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 1, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(inventoryService, never()).reserve(any(UUID.class), anyInt(),
                any(Instant.class), nullable(String.class));
        verify(sessionService, never()).create(any(SessionCreateParams.class));
    }

    @Test
    void createCheckoutSession_returns404_whenEventIsCancelled() throws Exception {
        Event cancelled = event();
        cancelled.setStatus(EventStatus.CANCELLED);
        when(events.findPublic(eventId)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 1, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(sessionService, never()).create(any(SessionCreateParams.class));
    }

    @Test
    void createCheckoutSession_remapsConflictFromReserveTo404() throws Exception {
        // InventoryService throws CONFLICT when there aren't enough tickets — we collapse
        // that to 404 so the buyer-facing public API can't enumerate inventory state.
        doThrow(new ApiException(HttpStatus.CONFLICT,
                com.imin.iminapi.security.ErrorCode.INVALID_STATE,
                "Not enough tickets available"))
                .when(inventoryService).reserve(eq(tierId), eq(2), any(Instant.class),
                        nullable(String.class));

        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 2, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));

        // We never created the Stripe session — the reserve failure short-circuited.
        verify(sessionService, never()).create(any(SessionCreateParams.class));
    }

    @Test
    void createCheckoutSession_releasesReservation_whenStripeSessionCreateFails() throws Exception {
        when(sessionService.create(any(SessionCreateParams.class)))
                .thenThrow(new ApiConnectionException("simulated network failure"));

        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 2, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_GATEWAY));

        // Reservation must be rolled back so the seats go back to the pool.
        InOrder ord = inOrder(inventoryService, sessionService);
        ord.verify(inventoryService).reserve(eq(tierId), eq(2), any(Instant.class),
                nullable(String.class));
        ord.verify(sessionService).create(any(SessionCreateParams.class));
        ord.verify(inventoryService).releaseReservation(eq(reservationId), eq("STRIPE_CREATE_FAILED"));
    }

    // ── stripe-10 — a coupon failure must not strand the seats ────────────────────
    @Test
    void createCheckoutSession_releasesReservation_whenCouponCreateFails() throws Exception {
        com.imin.iminapi.model.PromoCode promo = new com.imin.iminapi.model.PromoCode();
        promo.setId(UUID.randomUUID());
        promo.setEventId(eventId);
        promo.setCode("VECHIRKA20");
        promo.setDiscountPct(20);
        promo.setMaxUses(50);
        promo.setUsedCount(0);
        promo.setEnabled(true);
        when(promos.findByEventId(eventId)).thenReturn(java.util.List.of(promo));
        // A promo checkout needs a synced product to scope the coupon to (see the 503 test).
        TicketTier withProduct = tier();
        withProduct.setStripeProductId("prod_test_123");
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(withProduct));

        com.stripe.service.CouponService coupons = mock(com.stripe.service.CouponService.class);
        when(stripeClient.coupons()).thenReturn(coupons);
        when(coupons.create(any(com.stripe.param.CouponCreateParams.class)))
                .thenThrow(new ApiConnectionException("simulated coupon outage"));

        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 2, "VECHIRKA20"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_GATEWAY));

        // createCheckout is NOT transactional, so without an explicit release these 2 seats
        // stayed held for the full 30-minute session TTL — a short Stripe blip on a hot tier
        // during a promo drop reads to buyers as sold out.
        InOrder ord = inOrder(inventoryService, coupons);
        ord.verify(inventoryService).reserve(eq(tierId), eq(2), any(Instant.class),
                nullable(String.class));
        ord.verify(coupons).create(any(com.stripe.param.CouponCreateParams.class));
        ord.verify(inventoryService).releaseReservation(eq(reservationId), eq("STRIPE_COUPON_FAILED"));
        // The session was never attempted, so nothing else needs unwinding.
        verify(sessionService, never()).create(any(SessionCreateParams.class));
    }

    /**
     * percent_off let Stripe round the ticket line itself, so the hosted total could differ
     * from the quote by a cent. amount_off carries our own computed discount exactly.
     */
    @Test
    void couponIsAmountOffInTheEventCurrencyScopedToTheTicketProduct() throws Exception {
        TicketTier withProduct = tier();
        withProduct.setStripeProductId("prod_test_123");
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(withProduct));
        when(promos.findByEventId(eventId)).thenReturn(java.util.List.of(promo(20)));

        com.stripe.service.CouponService coupons = mock(com.stripe.service.CouponService.class);
        when(stripeClient.coupons()).thenReturn(coupons);
        com.stripe.model.Coupon coupon = mock(com.stripe.model.Coupon.class);
        when(coupon.getId()).thenReturn("co_test");
        when(coupons.create(any(com.stripe.param.CouponCreateParams.class))).thenReturn(coupon);

        svc.createCheckoutSession(eventId, tierId, 2, "VECHIRKA20");

        ArgumentCaptor<com.stripe.param.CouponCreateParams> captor =
                ArgumentCaptor.forClass(com.stripe.param.CouponCreateParams.class);
        verify(coupons).create(captor.capture());
        com.stripe.param.CouponCreateParams sent = captor.getValue();
        assertThat(sent.getPercentOff()).isNull();
        assertThat(sent.getAmountOff()).isEqualTo(400L);   // 20% of 2 × 1000
        assertThat(sent.getCurrency()).isEqualTo("eur");
        assertThat(sent.getAppliesTo().getProducts()).containsExactly("prod_test_123");
    }

    /**
     * With no product to scope to, a coupon discounts the service-fee line as well. One sync
     * attempt, then refuse — mis-charging the buyer is not the safer half of that choice.
     */
    @Test
    void promoWithNoStripeProductIsRefusedAfterASyncAttempt() throws Exception {
        when(promos.findByEventId(eventId)).thenReturn(java.util.List.of(promo(20)));

        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 2, "VECHIRKA20"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException ae = (ApiException) ex;
                    assertThat(ae.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ae.code())
                            .isEqualTo(com.imin.iminapi.security.ErrorCode.UPSTREAM_UNAVAILABLE);
                });

        verify(productService).syncTier(any(TicketTier.class), any(Event.class));
        verify(inventoryService).releaseReservation(eq(reservationId), eq("TIER_PRODUCT_UNAVAILABLE"));
        verify(sessionService, never()).create(any(SessionCreateParams.class));
    }

    @Test
    void promoProceedsWhenTheSyncSuppliesTheProductId() throws Exception {
        when(promos.findByEventId(eventId)).thenReturn(java.util.List.of(promo(20)));
        org.mockito.Mockito.doAnswer(inv -> {
            ((TicketTier) inv.getArgument(0)).setStripeProductId("prod_synced");
            return null;
        }).when(productService).syncTier(any(TicketTier.class), any(Event.class));

        com.stripe.service.CouponService coupons = mock(com.stripe.service.CouponService.class);
        when(stripeClient.coupons()).thenReturn(coupons);
        com.stripe.model.Coupon coupon = mock(com.stripe.model.Coupon.class);
        when(coupon.getId()).thenReturn("co_test");
        when(coupons.create(any(com.stripe.param.CouponCreateParams.class))).thenReturn(coupon);

        svc.createCheckoutSession(eventId, tierId, 2, "VECHIRKA20");

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());
        assertThat(captor.getValue().getLineItems().get(0).getPriceData().getProduct())
                .isEqualTo("prod_synced");
    }

    /**
     * The amount gate compares the charge against THIS stamp, not against the live tier price,
     * so an organizer edit inside an open session cannot turn a paid order into a refusal.
     */
    @Test
    void createCheckoutSession_stampsTheExpectedTotalTheBuyerAgreedTo() throws Exception {
        svc.createCheckoutSession(eventId, tierId, 3, null);

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());
        SessionCreateParams sent = captor.getValue();
        // 3 × 1000 tickets + fee (5% of 3000 = 150, plus 99 × 3 = 297) = 3447.
        assertThat(sent.getMetadata()).containsEntry("expected_total_minor", "3447");
        assertThat(sent.getMetadata()).containsEntry("expected_currency", "eur");
        assertThat(sent.getPaymentIntentData().getMetadata())
                .as("the webhook reads the PI, so the stamp has to be on both")
                .containsEntry("expected_total_minor", "3447");
    }

    /**
     * The stamp has to be the DISCOUNTED total, and the coupon has to take off exactly the
     * discount that produced it — a coupon and a stamp that disagree is a paid buyer the amount
     * gate then refuses to issue tickets to.
     */
    @Test
    void promoCheckout_stampsTheDiscountedTotalAndMintsAMatchingCoupon() throws Exception {
        TicketTier withProduct = tier();
        withProduct.setStripeProductId("prod_test");
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(withProduct));
        when(promos.findByEventId(eventId)).thenReturn(java.util.List.of(promo(20)));

        com.stripe.service.CouponService coupons = mock(com.stripe.service.CouponService.class);
        when(stripeClient.coupons()).thenReturn(coupons);
        com.stripe.model.Coupon coupon = mock(com.stripe.model.Coupon.class);
        when(coupon.getId()).thenReturn("co_test");
        when(coupons.create(any(com.stripe.param.CouponCreateParams.class))).thenReturn(coupon);

        svc.createCheckoutSession(eventId, tierId, 2, "VECHIRKA20");

        ArgumentCaptor<com.stripe.param.CouponCreateParams> couponSent =
                ArgumentCaptor.forClass(com.stripe.param.CouponCreateParams.class);
        verify(coupons).create(couponSent.capture());
        assertThat(couponSent.getValue().getAmountOff())
                .as("20% of the 2 × 1000 subtotal, to the cent — never percent_off")
                .isEqualTo(400L);

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());
        SessionCreateParams sent = captor.getValue();
        // 2 × 1000 − 400 discount = 1600, plus the fee on the UNDISCOUNTED subtotal
        // (5% of 2000 = 100, plus 99 × 2 = 198) = 298. Total 1898.
        assertThat(sent.getMetadata()).containsEntry("expected_total_minor", "1898");
        assertThat(sent.getPaymentIntentData().getMetadata())
                .containsEntry("expected_total_minor", "1898");
    }

    /**
     * A percentage of a cheap ticket can round to nothing. Stripe rejects {@code amount_off: 0},
     * so the coupon is skipped entirely — and the stamp is the undiscounted total, which is what
     * the buyer is actually charged.
     */
    @Test
    void aDiscountThatRoundsToZeroMintsNoCoupon() throws Exception {
        TicketTier cheap = tier();
        cheap.setPriceMinor(30);            // 0.30 — 1% of it rounds to 0
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(cheap));
        when(promos.findByEventId(eventId)).thenReturn(java.util.List.of(promo(1)));

        com.stripe.service.CouponService coupons = mock(com.stripe.service.CouponService.class);
        when(stripeClient.coupons()).thenReturn(coupons);

        svc.createCheckoutSession(eventId, tierId, 1, "VECHIRKA20");

        verify(coupons, never()).create(any(com.stripe.param.CouponCreateParams.class));
        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());
        SessionCreateParams sent = captor.getValue();
        assertThat(sent.getDiscounts()).isNullOrEmpty();
        // 30 + fee (5% of 30 = 2, plus 99) = 131 — nothing came off.
        assertThat(sent.getMetadata()).containsEntry("expected_total_minor", "131");
    }

    private com.imin.iminapi.model.PromoCode promo(int pct) {
        com.imin.iminapi.model.PromoCode p = new com.imin.iminapi.model.PromoCode();
        p.setId(UUID.randomUUID());
        p.setEventId(eventId);
        p.setCode("VECHIRKA20");
        p.setDiscountPct(pct);
        p.setMaxUses(50);
        p.setUsedCount(0);
        p.setEnabled(true);
        return p;
    }

    @Test
    void createCheckoutSession_rejectsQuantityOutOfRange() {
        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 0, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 11, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(inventoryService, never()).reserve(any(), anyInt(),
                any(Instant.class), nullable(String.class));
    }

    @Test
    void createCheckoutSession_returns409_whenExpectedPriceMismatches() {
        // The fixture tier price is 1000 (see setUp).
        assertThatThrownBy(() ->
                svc.createCheckoutSession(eventId, tierId, 1, null, 999))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException ae = (ApiException) ex;
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo(com.imin.iminapi.security.ErrorCode.PRICE_CHANGED);
                    assertThat(ae.fields()).containsEntry("currentPriceMinor", "1000");
                });

        // Inventory must NOT have been reserved when the price drifted.
        verify(inventoryService, never()).reserve(any(), anyInt(),
                any(Instant.class), nullable(String.class));
    }

    @Test
    void createCheckoutSession_accepts_whenExpectedPriceMatches() throws Exception {
        String url = svc.createCheckoutSession(eventId, tierId, 1, null, 1000);
        assertThat(url).isNotBlank();
    }

    // ---- Shared prelude (hosted + native) -----------------------------------

    /**
     * The invariant {@code NativePaymentIntentTest} cannot prove, because it stubs
     * the prelude: <b>a promo discounts the tickets and never the platform fee.</b>
     *
     * <p>The hosted path expresses the discount as a Stripe Coupon scoped to the
     * ticket Product, so the fee line item is untouched by construction. The native
     * path has no coupon — it subtracts the discount from the PaymentIntent amount —
     * so the only thing keeping the platform's cut intact is that
     * {@code reserveAndBuildMetadata} feeds {@code QuoteService.computeFee} the
     * UNDISCOUNTED subtotal. This drives the real method to pin that.
     *
     * <p>2 × €25.00 = 5000. Fee = round(5000 × 500 / 10000) + 99 × 2 = 250 + 198 = 448,
     * with or without the 20% promo.
     */
    @Test
    void reserveAndBuildMetadata_computesFeeOnUndiscountedSubtotal() {
        TicketTier priced25 = tier();
        priced25.setPriceMinor(2500);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(priced25));

        StripeCheckoutService.Priced plain = svc.priceIt(eventId, tierId, 2, null, null);
        StripeCheckoutService.PaidPrelude noPromo = svc.reserveAndBuildMetadata(plain, eventId, tierId, 2,
                "buyer@example.test", false, false,
                com.imin.iminapi.model.CheckoutAttribution.NONE, "en", true);

        assertThat(plain.subtotalMinor()).isEqualTo(5000L);
        assertThat(plain.netTotalMinor()).isEqualTo(5000L);
        assertThat(noPromo.applicationFee()).isEqualTo(448L);

        com.imin.iminapi.model.PromoCode promo = new com.imin.iminapi.model.PromoCode();
        promo.setId(UUID.randomUUID());
        promo.setEventId(eventId);
        promo.setCode("VECHIRKA20");
        promo.setDiscountPct(20);
        promo.setMaxUses(50);
        promo.setUsedCount(0);
        promo.setEnabled(true);
        when(promos.findByEventId(eventId)).thenReturn(java.util.List.of(promo));

        StripeCheckoutService.Priced discounted = svc.priceIt(eventId, tierId, 2, "VECHIRKA20", null);
        StripeCheckoutService.PaidPrelude withPromo = svc.reserveAndBuildMetadata(discounted, eventId, tierId, 2,
                "buyer@example.test", false, false,
                com.imin.iminapi.model.CheckoutAttribution.NONE, "en", true);

        assertThat(discounted.discountMinor()).isEqualTo(1000L);
        assertThat(discounted.netTotalMinor()).isEqualTo(4000L);
        // The assertion that fails if someone writes computeFee(netTotal, ...).
        assertThat(withPromo.applicationFee()).isEqualTo(noPromo.applicationFee());
        assertThat(withPromo.metadata()).containsEntry("promo_id", promo.getId().toString());
    }

    /**
     * The native-only metadata keys. {@code buyer_email} is the only recoverable
     * source of the buyer's address for a PaymentIntent with no Checkout Session,
     * and {@code client} is what tells {@code PaidCheckoutService} to skip the
     * guaranteed-empty session lookup. Both are stamped on BOTH flows on purpose —
     * a key present on one path only is a paid buyer with no ticket on the other.
     */
    @Test
    void reserveAndBuildMetadata_stampsBuyerEmailAndClientOnBothFlows() {
        StripeCheckoutService.Priced priced = svc.priceIt(eventId, tierId, 1, null, null);

        assertThat(svc.reserveAndBuildMetadata(priced, eventId, tierId, 1, " Buyer@Example.test ",
                false, false, com.imin.iminapi.model.CheckoutAttribution.NONE, "en", false).metadata())
                .containsEntry("buyer_email", "Buyer@Example.test")
                .containsEntry("client", "web");

        assertThat(svc.reserveAndBuildMetadata(priced, eventId, tierId, 1, "buyer@example.test",
                false, false, com.imin.iminapi.model.CheckoutAttribution.NONE, "en", true).metadata())
                .containsEntry("buyer_email", "buyer@example.test")
                .containsEntry("client", "native");

        // A guest who supplied no address must not get an empty-string key — absent
        // means "unknown", and the resolver's null check depends on that.
        assertThat(svc.reserveAndBuildMetadata(priced, eventId, tierId, 1, "   ",
                false, false, com.imin.iminapi.model.CheckoutAttribution.NONE, "en", true).metadata())
                .doesNotContainKey("buyer_email");
    }

    /** {@code priceIt} takes no hold and calls no Stripe — that is what lets the native flow reject a free total cleanly. */
    @Test
    void priceIt_isSideEffectFree() throws Exception {
        svc.priceIt(eventId, tierId, 2, null, null);

        verify(inventoryService, never()).reserve(any(), anyInt(), any(Instant.class), nullable(String.class));
        verify(sessionService, never()).create(any(SessionCreateParams.class));
        verify(connectService, never()).getStatusLive(any());
    }

    // ---- Free flow ----------------------------------------------------------

    @Test
    void createCheckoutSession_dispatchesToFreeFlow_whenTierIsFree() throws Exception {
        // Reconfigure the tier mock to return a free tier.
        TicketTier freeTier = tier();
        freeTier.setPriceMinor(0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(freeTier));

        com.imin.iminapi.model.Order order = new com.imin.iminapi.model.Order();
        order.setId(UUID.randomUUID());
        order.setToken("ord_abc");
        when(freeCheckoutService.issueFreeOrder(any(), any(), eq(1), eq("free@example.com"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class),
                any(com.imin.iminapi.model.CheckoutConsent.class)))
                .thenReturn(order);
        when(freeCheckoutService.orderUrl(order)).thenReturn("http://localhost:3000/order/ord_abc");

        String url = svc.createCheckoutSession(eventId, tierId, 1, null, 0, "free@example.com");

        assertThat(url).isEqualTo("http://localhost:3000/order/ord_abc");
        verify(freeCheckoutService).issueFreeOrder(any(), any(), eq(1), eq("free@example.com"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class),
                any(com.imin.iminapi.model.CheckoutConsent.class));
        // Branded email + downstream side effects now ride TicketsIssuedEvent published
        // inside issueFreeOrder — no inline confirmation call to verify here.
        // Stripe must NOT be called for free orders.
        verify(sessionService, never()).create(any(SessionCreateParams.class));
    }

    /**
     * W1.G/V78: the free path writes the Order inline, so the normalized locale must reach
     * FreeCheckoutService — there is no Stripe metadata hop to carry it.
     */
    @Test
    void createCheckoutSession_passesNormalizedLocaleToFreeFlow() throws Exception {
        TicketTier freeTier = tier();
        freeTier.setPriceMinor(0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(freeTier));

        com.imin.iminapi.model.Order order = new com.imin.iminapi.model.Order();
        order.setId(UUID.randomUUID());
        order.setToken("ord_fr");
        when(freeCheckoutService.issueFreeOrder(any(), any(), eq(1), eq("free@example.com"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class),
                any(com.imin.iminapi.model.CheckoutConsent.class)))
                .thenReturn(order);
        when(freeCheckoutService.orderUrl(order)).thenReturn("http://localhost:3000/order/ord_fr");

        svc.createCheckoutSession(eventId, tierId, 1, null, 0, "free@example.com",
                false, false, com.imin.iminapi.model.CheckoutAttribution.NONE, "  FR  ");

        verify(freeCheckoutService).issueFreeOrder(any(), any(), eq(1), eq("free@example.com"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), eq("fr"), nullable(String.class),
                any(com.imin.iminapi.model.CheckoutConsent.class));
    }

    /** Junk locale never reaches the column — it collapses to null ("no preference"). */
    @Test
    void createCheckoutSession_passesNullLocaleToFreeFlow_whenUnsupported() throws Exception {
        TicketTier freeTier = tier();
        freeTier.setPriceMinor(0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(freeTier));

        com.imin.iminapi.model.Order order = new com.imin.iminapi.model.Order();
        order.setId(UUID.randomUUID());
        order.setToken("ord_junk");
        when(freeCheckoutService.issueFreeOrder(any(), any(), eq(1), eq("free@example.com"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class),
                any(com.imin.iminapi.model.CheckoutConsent.class)))
                .thenReturn(order);
        when(freeCheckoutService.orderUrl(order)).thenReturn("http://localhost:3000/order/ord_junk");

        svc.createCheckoutSession(eventId, tierId, 1, null, 0, "free@example.com",
                false, false, com.imin.iminapi.model.CheckoutAttribution.NONE, "klingon");

        verify(freeCheckoutService).issueFreeOrder(any(), any(), eq(1), eq("free@example.com"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), isNull(), nullable(String.class),
                any(com.imin.iminapi.model.CheckoutConsent.class));
    }

    @Test
    void createCheckoutSession_returns400_whenFreeFlowMissingEmail() {
        TicketTier freeTier = tier();
        freeTier.setPriceMinor(0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(freeTier));

        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 1, null, 0, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException ae = (ApiException) ex;
                    assertThat(ae.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ae.fields()).containsKey("email");
                });

        verify(freeCheckoutService, never()).issueFreeOrder(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class),
                any(com.imin.iminapi.model.CheckoutConsent.class));
    }

    @Test
    void createCheckoutSession_collapsesInventoryShortageTo404_onFreeFlow() {
        TicketTier freeTier = tier();
        freeTier.setPriceMinor(0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(freeTier));

        when(freeCheckoutService.issueFreeOrder(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class),
                any(com.imin.iminapi.model.CheckoutConsent.class)))
                .thenThrow(new ApiException(HttpStatus.CONFLICT,
                        com.imin.iminapi.security.ErrorCode.INVALID_STATE,
                        "Not enough tickets available"));

        assertThatThrownBy(() ->
                svc.createCheckoutSession(eventId, tierId, 1, null, 0, "free@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void createCheckoutSession_dispatchesToFreeFlow_whenPromoZeroesPaidTier() throws Exception {
        // Paid tier (1000) with a 100%-off promo → net 0 → must bypass Stripe.
        com.imin.iminapi.model.PromoCode promo = new com.imin.iminapi.model.PromoCode();
        promo.setId(UUID.randomUUID());
        promo.setEventId(eventId);
        promo.setCode("ALLFREE");
        promo.setDiscountPct(100);
        promo.setMaxUses(50);
        promo.setUsedCount(0);
        promo.setEnabled(true);
        when(promos.findByEventId(eventId)).thenReturn(java.util.List.of(promo));

        com.imin.iminapi.model.Order order = new com.imin.iminapi.model.Order();
        order.setId(UUID.randomUUID());
        order.setToken("ord_zeroed");
        when(freeCheckoutService.issueFreeOrder(any(), any(), eq(1), eq("buyer@example.com"), eq(promo),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class),
                any(com.imin.iminapi.model.CheckoutConsent.class)))
                .thenReturn(order);
        when(freeCheckoutService.orderUrl(order)).thenReturn("http://localhost:3000/order/ord_zeroed");

        String url = svc.createCheckoutSession(eventId, tierId, 1, "ALLFREE", 1000, "buyer@example.com");

        assertThat(url).isEqualTo("http://localhost:3000/order/ord_zeroed");
        verify(freeCheckoutService).issueFreeOrder(any(), any(), eq(1), eq("buyer@example.com"), eq(promo),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class),
                any(com.imin.iminapi.model.CheckoutConsent.class));
        verify(sessionService, never()).create(any(SessionCreateParams.class));
    }
}
