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
        props = new StripeProperties();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        svc = new StripeCheckoutService(stripeClient, events, tiers, orgs, promos,
                connectService, inventoryService, freeCheckoutService, props, clock);

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
                any(), nullable(String.class), nullable(String.class)))
                .thenReturn(order);
        when(freeCheckoutService.findOrderTickets(order.getId())).thenReturn(java.util.List.of());
        when(freeCheckoutService.orderUrl(order)).thenReturn("http://localhost:3000/order/ord_abc");

        String url = svc.createCheckoutSession(eventId, tierId, 1, null, 0, "free@example.com");

        assertThat(url).isEqualTo("http://localhost:3000/order/ord_abc");
        verify(freeCheckoutService).issueFreeOrder(any(), any(), eq(1), eq("free@example.com"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class));
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
                any(), nullable(String.class), nullable(String.class)))
                .thenReturn(order);
        when(freeCheckoutService.orderUrl(order)).thenReturn("http://localhost:3000/order/ord_fr");

        svc.createCheckoutSession(eventId, tierId, 1, null, 0, "free@example.com",
                false, false, com.imin.iminapi.model.CheckoutAttribution.NONE, "  FR  ");

        verify(freeCheckoutService).issueFreeOrder(any(), any(), eq(1), eq("free@example.com"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), eq("fr"), nullable(String.class));
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
                any(), nullable(String.class), nullable(String.class)))
                .thenReturn(order);
        when(freeCheckoutService.orderUrl(order)).thenReturn("http://localhost:3000/order/ord_junk");

        svc.createCheckoutSession(eventId, tierId, 1, null, 0, "free@example.com",
                false, false, com.imin.iminapi.model.CheckoutAttribution.NONE, "klingon");

        verify(freeCheckoutService).issueFreeOrder(any(), any(), eq(1), eq("free@example.com"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), isNull(), nullable(String.class));
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
                any(), nullable(String.class), nullable(String.class));
    }

    @Test
    void createCheckoutSession_collapsesInventoryShortageTo404_onFreeFlow() {
        TicketTier freeTier = tier();
        freeTier.setPriceMinor(0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(freeTier));

        when(freeCheckoutService.issueFreeOrder(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class)))
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
                any(), nullable(String.class), nullable(String.class)))
                .thenReturn(order);
        when(freeCheckoutService.findOrderTickets(order.getId())).thenReturn(java.util.List.of());
        when(freeCheckoutService.orderUrl(order)).thenReturn("http://localhost:3000/order/ord_zeroed");

        String url = svc.createCheckoutSession(eventId, tierId, 1, "ALLFREE", 1000, "buyer@example.com");

        assertThat(url).isEqualTo("http://localhost:3000/order/ord_zeroed");
        verify(freeCheckoutService).issueFreeOrder(any(), any(), eq(1), eq("buyer@example.com"), eq(promo),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), nullable(String.class), nullable(String.class));
        verify(sessionService, never()).create(any(SessionCreateParams.class));
    }
}
