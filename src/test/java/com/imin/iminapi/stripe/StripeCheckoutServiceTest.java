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
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(order);
        when(freeCheckoutService.findOrderTickets(order.getId())).thenReturn(java.util.List.of());
        when(freeCheckoutService.orderUrl(order)).thenReturn("http://localhost:3000/order/ord_abc");

        String url = svc.createCheckoutSession(eventId, tierId, 1, null, 0, "free@example.com");

        assertThat(url).isEqualTo("http://localhost:3000/order/ord_abc");
        verify(freeCheckoutService).issueFreeOrder(any(), any(), eq(1), eq("free@example.com"),
                org.mockito.ArgumentMatchers.isNull());
        verify(freeCheckoutService).sendConfirmation(eq(order), any(), any());
        // Stripe must NOT be called for free orders.
        verify(sessionService, never()).create(any(SessionCreateParams.class));
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
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void createCheckoutSession_collapsesInventoryShortageTo404_onFreeFlow() {
        TicketTier freeTier = tier();
        freeTier.setPriceMinor(0);
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(freeTier));

        when(freeCheckoutService.issueFreeOrder(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any()))
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
        when(freeCheckoutService.issueFreeOrder(any(), any(), eq(1), eq("buyer@example.com"), eq(promo)))
                .thenReturn(order);
        when(freeCheckoutService.findOrderTickets(order.getId())).thenReturn(java.util.List.of());
        when(freeCheckoutService.orderUrl(order)).thenReturn("http://localhost:3000/order/ord_zeroed");

        String url = svc.createCheckoutSession(eventId, tierId, 1, "ALLFREE", 1000, "buyer@example.com");

        assertThat(url).isEqualTo("http://localhost:3000/order/ord_zeroed");
        verify(freeCheckoutService).issueFreeOrder(any(), any(), eq(1), eq("buyer@example.com"), eq(promo));
        verify(sessionService, never()).create(any(SessionCreateParams.class));
    }
}
