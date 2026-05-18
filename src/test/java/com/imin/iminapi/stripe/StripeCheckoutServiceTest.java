package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.TicketTierKind;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private StripeProperties props;
    private Clock clock;
    private StripeCheckoutService svc;

    private final UUID eventId = UUID.randomUUID();
    private final UUID tierId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

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
        props = new StripeProperties();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        svc = new StripeCheckoutService(stripeClient, events, tiers, orgs, promos,
                connectService, inventoryService, props, clock);

        // Default happy-path wiring
        Event event = event();
        Organization org = org();
        TicketTier tier = tier();
        when(events.findPublic(eventId)).thenReturn(Optional.of(event));
        when(tiers.findByIdAndEventId(tierId, eventId)).thenReturn(Optional.of(tier));
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));
        when(connectService.getStatus(any(), eq(orgId)))
                .thenReturn(new StripeConnectService.StatusResult("acct_test", true, true, null));

        Session session = mock(Session.class);
        when(session.getUrl()).thenReturn("https://checkout.stripe.com/c/pay/cs_test");
        when(sessionService.create(any(SessionCreateParams.class))).thenReturn(session);
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
        t.setKind(TicketTierKind.STANDARD);
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
        String url = svc.createCheckoutSession(eventId, tierId, 2, null);

        assertThat(url).isEqualTo("https://checkout.stripe.com/c/pay/cs_test");

        // Order matters: inventory must be reserved BEFORE the Stripe session is created,
        // so we never hand a buyer a checkout URL we can't honor.
        InOrder order = inOrder(inventoryService, sessionService);
        order.verify(inventoryService).reserve(tierId, 2);
        order.verify(sessionService).create(any(SessionCreateParams.class));
    }

    @Test
    void createCheckoutSession_stampsTierIdAndQtyMetadata() throws Exception {
        svc.createCheckoutSession(eventId, tierId, 3, null);

        ArgumentCaptor<SessionCreateParams> captor = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(sessionService).create(captor.capture());

        SessionCreateParams sent = captor.getValue();
        assertThat(sent.getMetadata()).containsEntry("tier_id", tierId.toString());
        assertThat(sent.getMetadata()).containsEntry("qty", "3");
        assertThat(sent.getMetadata()).containsEntry("event_id", eventId.toString());
    }

    @Test
    void createCheckoutSession_remapsConflictFromReserveTo404() throws Exception {
        // InventoryService throws CONFLICT when there aren't enough tickets — we collapse
        // that to 404 so the buyer-facing public API can't enumerate inventory state.
        doThrow(new ApiException(HttpStatus.CONFLICT,
                com.imin.iminapi.security.ErrorCode.INVALID_STATE,
                "Not enough tickets available"))
                .when(inventoryService).reserve(tierId, 2);

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
        InOrder order = inOrder(inventoryService, sessionService);
        order.verify(inventoryService).reserve(tierId, 2);
        order.verify(sessionService).create(any(SessionCreateParams.class));
        order.verify(inventoryService).releaseReservation(tierId, 2);
    }

    @Test
    void createCheckoutSession_rejectsQuantityOutOfRange() {
        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 0, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> svc.createCheckoutSession(eventId, tierId, 11, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(inventoryService, never()).reserve(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
