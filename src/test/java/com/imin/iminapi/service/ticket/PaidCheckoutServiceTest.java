package com.imin.iminapi.service.ticket;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.repository.EventRepository;
import com.stripe.StripeClient;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.model.checkout.SessionCollection;
import com.stripe.service.ChargeService;
import com.stripe.service.CheckoutService;
import com.stripe.service.checkout.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class PaidCheckoutServiceTest {

    @Autowired PaidCheckoutService service;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    @MockitoBean StripeClient stripeClient;

    private CheckoutService checkoutService;
    private SessionService sessionService;
    private ChargeService chargeService;

    private Event event;
    private TicketTier tier;

    @BeforeEach
    void setUp() throws Exception {
        // Wire Stripe mocks for the PI-resolution code paths.
        checkoutService = mock(CheckoutService.class);
        sessionService = mock(SessionService.class);
        chargeService = mock(ChargeService.class);
        when(stripeClient.checkout()).thenReturn(checkoutService);
        when(checkoutService.sessions()).thenReturn(sessionService);
        when(stripeClient.charges()).thenReturn(chargeService);

        tickets.deleteAll();
        orders.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();

        Organization org = new Organization();
        org.setName("Issuance Org");
        org.setSlug("issuance-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("issuance@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("issuance-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Saturn Night");
        event.setSlug("issuance-event-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setPublishedAt(Instant.now().minusSeconds(3600));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        tier = new TicketTier();
        tier.setEventId(event.getId());
        tier.setName("GA");
        tier.setPriceMinor(1500);
        tier.setQuantity(100);
        tier.setReserved(0);
        tier.setSold(0);
        tier.setEnabled(true);
        tier = tiers.save(tier);
    }

    @AfterEach
    void tearDown() {
        tickets.deleteAll();
        orders.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    @Test
    void issues_order_and_two_tickets_with_pi_id_as_idempotency_key() throws Exception {
        PaymentIntent pi = pi("pi_test_happy_1", 3000, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "2",
                        "event_id", event.getId().toString()));
        wireBuyerEmail(pi, "buyer@example.com");
        wireSessionLookup(pi, "cs_test_happy_1", null);

        service.issuePaidOrder(pi);

        Order order = orders.findByStripePaymentIntentId("pi_test_happy_1").orElseThrow();
        assertThat(order.getEmail()).isEqualTo("buyer@example.com");
        assertThat(order.getStripeSessionId()).isEqualTo("cs_test_happy_1");
        assertThat(order.getTotalMinor()).isEqualTo(3000L);
        assertThat(order.getPaymentMethod()).isEqualTo("stripe");
        assertThat(order.getEventId()).isEqualTo(event.getId());

        List<Ticket> issued = tickets.findByOrderIdOrderByCreatedAtAsc(order.getId());
        assertThat(issued).hasSize(2);
        assertThat(issued).allSatisfy(t -> {
            assertThat(t.getTierId()).isEqualTo(tier.getId());
            assertThat(t.getTierName()).isEqualTo("GA");
            assertThat(t.getState()).isEqualTo("issued");
        });
    }

    @Test
    void second_delivery_of_same_pi_is_a_noop() throws Exception {
        PaymentIntent pi = pi("pi_test_dupe_1", 1500, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "1",
                        "event_id", event.getId().toString()));
        wireBuyerEmail(pi, "buyer@example.com");
        wireSessionLookup(pi, "cs_test_dupe_1", null);

        service.issuePaidOrder(pi);
        service.issuePaidOrder(pi);

        long matching = orders.findAll().stream()
                .filter(o -> "pi_test_dupe_1".equals(o.getStripePaymentIntentId()))
                .count();
        assertThat(matching).isEqualTo(1L);

        Order order = orders.findByStripePaymentIntentId("pi_test_dupe_1").orElseThrow();
        assertThat(tickets.findByOrderIdOrderByCreatedAtAsc(order.getId())).hasSize(1);
    }

    @Test
    void skips_when_metadata_is_missing() throws Exception {
        PaymentIntent pi = pi("pi_test_no_meta", 1500, "eur", Map.of());
        wireBuyerEmail(pi, "buyer@example.com");
        wireSessionLookup(pi, "cs_test_no_meta", null);

        service.issuePaidOrder(pi);

        assertThat(orders.findByStripePaymentIntentId("pi_test_no_meta")).isEmpty();
    }

    @Test
    void snapshots_application_fee_on_order_and_price_on_tickets() throws Exception {
        PaymentIntent pi = pi("pi_test_snapshot", 3000, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "2",
                        "event_id", event.getId().toString()));
        pi.setApplicationFeeAmount(249L);   // 5% of 3000 + 99 fixed = 249
        wireBuyerEmail(pi, "buyer@example.com");
        wireSessionLookup(pi, "cs_test_snapshot", null);

        service.issuePaidOrder(pi);

        Order order = orders.findByStripePaymentIntentId("pi_test_snapshot").orElseThrow();
        assertThat(order.getApplicationFeeMinor()).isEqualTo(249L);

        List<Ticket> issued = tickets.findByOrderIdOrderByCreatedAtAsc(order.getId());
        assertThat(issued).hasSize(2);
        assertThat(issued).allSatisfy(t -> assertThat(t.getPriceMinor()).isEqualTo(1500));
    }

    @Test
    void zero_application_fee_when_pi_has_no_fee() throws Exception {
        PaymentIntent pi = pi("pi_test_no_fee", 1500, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "1",
                        "event_id", event.getId().toString()));
        // applicationFeeAmount intentionally null
        wireBuyerEmail(pi, "buyer@example.com");
        wireSessionLookup(pi, "cs_test_no_fee", null);

        service.issuePaidOrder(pi);

        Order order = orders.findByStripePaymentIntentId("pi_test_no_fee").orElseThrow();
        assertThat(order.getApplicationFeeMinor()).isEqualTo(0L);
    }

    @Test
    void persists_ads_consent_true_from_pi_metadata() throws Exception {
        // §7: the buyer's cookie-consent ads-consent decision rides Stripe metadata
        // (StripeCheckoutService stamps "ads_consent") and must land on orders.ads_consent
        // so the server-side Meta CAPI event (MetaCapiOutboxWriter) is enabled.
        PaymentIntent pi = pi("pi_test_ads_consent", 1500, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "1",
                        "event_id", event.getId().toString(),
                        "ads_consent", "true"));
        wireBuyerEmail(pi, "buyer@example.com");
        wireSessionLookup(pi, "cs_test_ads_consent", null);

        service.issuePaidOrder(pi);

        Order order = orders.findByStripePaymentIntentId("pi_test_ads_consent").orElseThrow();
        assertThat(order.isAdsConsent()).isTrue();
    }

    @Test
    void defaults_ads_consent_false_when_metadata_absent() throws Exception {
        // No ads_consent key in metadata → must stay false (V60 default), so unconsented
        // orders never emit a server-side Meta event.
        PaymentIntent pi = pi("pi_test_no_ads_consent", 1500, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "1",
                        "event_id", event.getId().toString()));
        wireBuyerEmail(pi, "buyer@example.com");
        wireSessionLookup(pi, "cs_test_no_ads_consent", null);

        service.issuePaidOrder(pi);

        Order order = orders.findByStripePaymentIntentId("pi_test_no_ads_consent").orElseThrow();
        assertThat(order.isAdsConsent()).isFalse();
    }

    @Test
    void persists_utm_attribution_from_pi_metadata() throws Exception {
        // V62: the landing utm_* + the /track beacon's anon_id ride Stripe metadata
        // (StripeCheckoutService stamps them) and must land on orders.utm_* at
        // webhook-driven fulfilment — this is what turns per-campaign revenue from a
        // visit-share estimate into a true per-order sum.
        String campaignId = UUID.randomUUID().toString();
        PaymentIntent pi = pi("pi_test_utm", 1500, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "1",
                        "event_id", event.getId().toString(),
                        "utm_source", "imin",
                        "utm_medium", "email",
                        "utm_campaign", campaignId,
                        "anon_id", "anon-abc-123"));
        wireBuyerEmail(pi, "buyer@example.com");
        wireSessionLookup(pi, "cs_test_utm", null);

        service.issuePaidOrder(pi);

        Order order = orders.findByStripePaymentIntentId("pi_test_utm").orElseThrow();
        assertThat(order.getUtmSource()).isEqualTo("imin");
        assertThat(order.getUtmMedium()).isEqualTo("email");
        assertThat(order.getUtmCampaign()).isEqualTo(campaignId);
        assertThat(order.getAnonId()).isEqualTo("anon-abc-123");
    }

    @Test
    void utm_attribution_is_null_when_metadata_absent() throws Exception {
        // An organic buyer arrives with no tags — and sessions created before V62 that were
        // still in flight at deploy carry no utm keys either. Both must yield null, never "".
        PaymentIntent pi = pi("pi_test_no_utm", 1500, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "1",
                        "event_id", event.getId().toString()));
        wireBuyerEmail(pi, "buyer@example.com");
        wireSessionLookup(pi, "cs_test_no_utm", null);

        service.issuePaidOrder(pi);

        Order order = orders.findByStripePaymentIntentId("pi_test_no_utm").orElseThrow();
        assertThat(order.getUtmSource()).isNull();
        assertThat(order.getUtmMedium()).isNull();
        assertThat(order.getUtmCampaign()).isNull();
        assertThat(order.getAnonId()).isNull();
    }

    /**
     * W1.G/V78: the buyer's UI language is stamped into the Session/PI metadata at
     * checkout because the Order does not exist until this webhook runs. Read it back —
     * without this, a Spanish buyer's paid order gets an English ticket email.
     */
    @Test
    void persists_buyer_locale_from_pi_metadata() throws Exception {
        PaymentIntent pi = pi("pi_test_locale", 1500, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "1",
                        "event_id", event.getId().toString(),
                        "buyer_locale", "es"));
        wireBuyerEmail(pi, "buyer@example.com");
        wireSessionLookup(pi, "cs_test_locale", null);

        service.issuePaidOrder(pi);

        assertThat(orders.findByStripePaymentIntentId("pi_test_locale").orElseThrow()
                .getBuyerLocale()).isEqualTo("es");
    }

    /**
     * Absent key (pre-V78 session in flight at deploy) or an unsupported tag → null, i.e.
     * "no preference" ⇒ English, exactly like every historical order.
     */
    @Test
    void buyer_locale_is_null_when_metadata_absent_or_unsupported() throws Exception {
        PaymentIntent absent = pi("pi_test_no_locale", 1500, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "1",
                        "event_id", event.getId().toString()));
        wireBuyerEmail(absent, "buyer@example.com");
        wireSessionLookup(absent, "cs_test_no_locale", null);
        service.issuePaidOrder(absent);

        PaymentIntent junk = pi("pi_test_junk_locale", 1500, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "1",
                        "event_id", event.getId().toString(),
                        "buyer_locale", "klingon"));
        wireBuyerEmail(junk, "buyer2@example.com");
        wireSessionLookup(junk, "cs_test_junk_locale", null);
        service.issuePaidOrder(junk);

        assertThat(orders.findByStripePaymentIntentId("pi_test_no_locale").orElseThrow()
                .getBuyerLocale()).isNull();
        assertThat(orders.findByStripePaymentIntentId("pi_test_junk_locale").orElseThrow()
                .getBuyerLocale()).isNull();
    }

    @Test
    void uses_session_email_when_charge_email_missing() throws Exception {
        PaymentIntent pi = pi("pi_test_session_email", 1500, "eur",
                Map.of(
                        "tier_id", tier.getId().toString(),
                        "qty", "1",
                        "event_id", event.getId().toString()));
        wireBuyerEmail(pi, null); // charge has no email
        wireSessionLookup(pi, "cs_test_session_email", "from-session@example.com");

        service.issuePaidOrder(pi);

        Order order = orders.findByStripePaymentIntentId("pi_test_session_email").orElseThrow();
        assertThat(order.getEmail()).isEqualTo("from-session@example.com");
    }

    // ─── Stripe fixture helpers ──────────────────────────────────────────────

    private PaymentIntent pi(String id, long amount, String currency, Map<String, String> meta) {
        PaymentIntent p = new PaymentIntent();
        p.setId(id);
        p.setAmount(amount);
        p.setCurrency(currency);
        p.setMetadata(meta);
        p.setLatestCharge("ch_for_" + id);
        return p;
    }

    private void wireBuyerEmail(PaymentIntent pi, String email) throws Exception {
        Charge c = new Charge();
        if (email != null) {
            Charge.BillingDetails bd = new Charge.BillingDetails();
            bd.setEmail(email);
            c.setBillingDetails(bd);
        }
        when(chargeService.retrieve(eq(pi.getLatestCharge()))).thenReturn(c);
    }

    private void wireSessionLookup(PaymentIntent pi, String sessionId, String sessionEmail)
            throws Exception {
        Session s = new Session();
        s.setId(sessionId);
        if (sessionEmail != null) {
            Session.CustomerDetails cd = new Session.CustomerDetails();
            cd.setEmail(sessionEmail);
            s.setCustomerDetails(cd);
        }
        SessionCollection coll = new SessionCollection();
        coll.setData(List.of(s));
        when(sessionService.list(any(com.stripe.param.checkout.SessionListParams.class))).thenReturn(coll);
    }
}
