package com.imin.iminapi.referral;

import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.event.FreeCheckoutService;
import com.imin.iminapi.service.ticket.PaidCheckoutService;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PropertyFlips;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/** A friend's order is credited to the referrer's order on both checkout paths, and only when the link may count. */
@IminIntegrationTest
class ReferralAttributionTest {

    @Autowired FreeCheckoutService freeCheckout;
    @Autowired PaidCheckoutService paidCheckout;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired StripeProperties stripeProps;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("taskExecutor") Executor asyncExecutor;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;
    @Autowired @Qualifier("ticketEmailExecutor") Executor ticketEmailExecutor;

    private final List<UUID> orgIds = new ArrayList<>();
    private final String suffix = "_" + UUID.randomUUID().toString().substring(0, 12);
    private Organization org;
    private User owner;
    private Event event;
    private TicketTier freeTier;
    private TicketTier paidTier;

    @BeforeEach
    void setUp() {
        // New orders are stamped live, like production.
        flips.set(stripeProps, "secretKey", "sk_live_dummy");
        org = fx.org();
        orgIds.add(org.getId());
        owner = fx.owner(org);
        event = publicEvent();
        freeTier = tier(event, 0);
        paidTier = tier(event, 1500);
    }

    @AfterEach
    void tearDown() {
        AsyncDrain.drain(asyncExecutor);
        AsyncDrain.drain(fanFeatureExecutor);
        AsyncDrain.drain(ticketEmailExecutor);
        OrgRows.delete(jdbc, orgIds);
    }

    enum Path { FREE, PAID_PAYMENT_INTENT }

    @ParameterizedTest
    @EnumSource(Path.class)
    void aFriendsOrderIsCreditedToTheReferrer(Path path) {
        Order referrer = referrerOrder(event, fx.email("referrer"));

        Order friend = buy(path, event, fx.email("friend"), 2, referrer.getReferralCode());

        assertThat(orders.findById(friend.getId()).orElseThrow().getReferredByOrderId()).isEqualTo(referrer.getId());
        assertThat(orders.countValidReferredTickets(referrer.getId())).isEqualTo(2);
    }

    enum Refusal { UNKNOWN_CODE, CODE_OF_ANOTHER_EVENT, BUYERS_OWN_EMAIL_IN_ANOTHER_CASE, TEST_MODE_REFERRER }

    @ParameterizedTest
    @EnumSource(Refusal.class)
    void codesThatMustNotCredit(Refusal refusal) {
        String friendEmail = fx.email("friend");
        String code = switch (refusal) {
            case UNKNOWN_CODE -> "Zz9" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
            case CODE_OF_ANOTHER_EVENT -> referrerOrder(publicEvent(), fx.email("elsewhere")).getReferralCode();
            case BUYERS_OWN_EMAIL_IN_ANOTHER_CASE -> referrerOrder(event, friendEmail.toUpperCase()).getReferralCode();
            case TEST_MODE_REFERRER -> {
                Order r = referrerOrder(event, fx.email("test-mode"));
                r.setTestMode(true);
                yield orders.save(r).getReferralCode();
            }
        };

        Order friend = buy(Path.FREE, event, friendEmail, 1, code);

        assertThat(orders.findById(friend.getId()).orElseThrow().getReferredByOrderId()).isNull();
    }

    private Order buy(Path path, Event ev, String email, int qty, String code) {
        CheckoutAttribution attribution = new CheckoutAttribution(null, null, null, null, code);
        if (path == Path.FREE) {
            return freeCheckout.issueFreeOrder(ev, freeTier, qty, email, null, false, false, attribution, null);
        }
        Map<String, String> meta = new HashMap<>();
        meta.put("tier_id", paidTier.getId().toString());
        meta.put("event_id", ev.getId().toString());
        meta.put("qty", String.valueOf(qty));
        meta.put("client", "native");
        meta.put("buyer_email", email);
        attribution.putInto(meta);
        PaymentIntent pi = new PaymentIntent();
        pi.setId("pi_crew" + suffix + "_" + UUID.randomUUID().toString().substring(0, 6));
        pi.setAmount(1500L * qty);
        pi.setCurrency("eur");
        pi.setMetadata(meta);
        assertThat(paidCheckout.issuePaidOrder(pi)).isTrue();
        return orders.findByStripePaymentIntentId(pi.getId()).orElseThrow();
    }

    private Order referrerOrder(Event ev, String email) {
        Order o = fx.order(ev, email);
        fx.ticket(o, Ticket.STATE_ISSUED);
        return o;
    }

    private Event publicEvent() {
        Event e = fx.event(org, owner, EventStatus.LIVE, Instant.now().plus(10, ChronoUnit.DAYS));
        e.setPublishedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        return events.save(e);
    }

    private TicketTier tier(Event ev, int priceMinor) {
        TicketTier t = fx.tier(ev, priceMinor, 100);
        t.setEnabled(true);
        return tiers.save(t);
    }
}
