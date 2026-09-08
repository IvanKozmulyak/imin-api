package com.imin.iminapi.service.ticket;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.stripe.StripeClient;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.SessionCollection;
import com.stripe.service.ChargeService;
import com.stripe.service.CheckoutService;
import com.stripe.service.checkout.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A losing duplicate delivery must NOT be reported as a handled success.
 *
 * <p>{@code issuePaidOrder} used to catch {@code DataIntegrityViolationException}
 * around {@code orders.save} and {@code return false} with the comment "the other
 * deliverer is finishing the job". That could never happen on the webhook path:
 * {@code Order} uses {@code GenerationType.UUID}, so Hibernate defers the INSERT
 * to flush/commit — {@code FreeCheckoutService} spells this out for the same
 * entity — and the constraint therefore fires outside the try, rolling the whole
 * delivery back so Stripe retries onto the idempotent short-circuit. The catch
 * was live only on the (then non-transactional) reconciler path, which discarded
 * the boolean anyway. Swallowing it would be wrong in any case: rollback + retry
 * is what makes the loser correct, and reporting success would suppress the
 * caller's exactly-once side effects (promo-usage increment) for a delivery that
 * wrote nothing.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class PaidCheckoutDuplicateKeyTest {

    @Autowired PaidCheckoutService service;
    @Autowired TicketTierRepository tiers;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    @MockitoBean OrderRepository orders;
    @MockitoBean StripeClient stripeClient;

    private Event event;
    private TicketTier tier;

    @BeforeEach
    void setUp() throws Exception {
        CheckoutService checkoutService = mock(CheckoutService.class);
        SessionService sessionService = mock(SessionService.class);
        ChargeService chargeService = mock(ChargeService.class);
        when(stripeClient.checkout()).thenReturn(checkoutService);
        when(checkoutService.sessions()).thenReturn(sessionService);
        when(stripeClient.charges()).thenReturn(chargeService);

        Charge c = new Charge();
        Charge.BillingDetails bd = new Charge.BillingDetails();
        bd.setEmail("racer@example.test");
        c.setBillingDetails(bd);
        when(chargeService.retrieve(any(String.class))).thenReturn(c);
        SessionCollection empty = new SessionCollection();
        empty.setData(List.of());
        when(sessionService.list(any(com.stripe.param.checkout.SessionListParams.class)))
                .thenReturn(empty);

        Organization org = new Organization();
        org.setName("Race Org");
        org.setSlug("race-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("race@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("race-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Race Night");
        event.setSlug("race-event-" + UUID.randomUUID().toString().substring(0, 8));
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

    @Test
    void a_duplicate_order_insert_is_not_swallowed_as_a_success() {
        PaymentIntent pi = new PaymentIntent();
        pi.setId("pi_race_loser");
        pi.setAmount(1500L);
        pi.setCurrency("eur");
        pi.setLatestCharge("ch_race");
        pi.setMetadata(Map.of(
                "tier_id", tier.getId().toString(),
                "qty", "1",
                "event_id", event.getId().toString(),
                "buyer_email", "racer@example.test",
                "client", "native"));

        when(orders.save(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "\"orders_stripe_payment_intent_id_unique\""));

        assertThatThrownBy(() -> service.issuePaidOrder(pi))
                .as("the loser must roll back so Stripe retries onto the idempotent "
                        + "short-circuit, not report a success it did not perform")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
