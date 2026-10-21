package com.imin.iminapi.referral;

import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.dto.publicapi.PublicOrderResponse;
import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.referral.dto.PutReferralPerkRequest;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.event.FreeCheckoutService;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** When a referrer's perk unlocks, what it says, and what stopping or switching perks off does to it. */
@IminIntegrationTest
class ReferralPerkUnlockTest {

    @Autowired ReferralPerkService perkService;
    @Autowired ReferralService referralService;
    @Autowired ReferralPerkUnlockRepository unlocks;
    @Autowired EventReferralPerkRepository perks;
    @Autowired ReferralProperties referralProps;
    @Autowired FreeCheckoutService freeCheckout;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
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
    private Event event;
    private AuthPrincipal owner;

    @BeforeEach
    void setUp() {
        flips.set(referralProps, "perksEnabled", true);
        flips.set(stripeProps, "secretKey", "sk_live_dummy");
        Organization org = fx.org();
        orgIds.add(org.getId());
        User user = fx.owner(org);
        owner = fx.principal(user);
        event = fx.event(org, user, EventStatus.LIVE, Instant.now().plus(10, ChronoUnit.DAYS));
        event.setPublishedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        event = events.save(event);
    }

    @AfterEach
    void tearDown() {
        AsyncDrain.drain(asyncExecutor);
        AsyncDrain.drain(fanFeatureExecutor);
        AsyncDrain.drain(ticketEmailExecutor);
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void theThirdValidTicketUnlocksThroughTheListener() {
        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Skip the line", 3));
        TicketTier free = fx.tier(event, 0, 100);
        Order referrer = referrer();

        buyFree(free, 2, referrer.getReferralCode());
        AsyncDrain.drain(asyncExecutor);
        assertThat(unlocks.findById(referrer.getId())).as("2 of 3 tickets").isEmpty();

        buyFree(free, 1, referrer.getReferralCode());
        AsyncDrain.drain(asyncExecutor);

        ReferralPerkUnlock unlock = unlocks.findById(referrer.getId()).orElseThrow();
        assertThat(unlock.getLabel()).isEqualTo("Skip the line");
        assertThat(unlock.getThreshold()).isEqualTo((short) 3);
        assertThat(unlock.getEventId()).isEqualTo(event.getId());
        assertThat(unlock.getGivenAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {Ticket.STATE_REFUNDED, Ticket.STATE_REVOKED})
    void refundedOrRevokedTicketsDoNotCount(String excludedState) {
        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Skip the line", 2));
        Order referrer = referrer();
        friend(referrer, excludedState);
        friend(referrer, Ticket.STATE_ISSUED);

        perkService.evaluate(referrer.getId());

        assertThat(orders.countValidReferredTickets(referrer.getId())).isEqualTo(1);
        assertThat(unlocks.findById(referrer.getId())).isEmpty();
    }

    enum Path { REFERRER, EVENT }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Path.class)
    void aReferrerWhoNoLongerHoldsATicketNeverUnlocks(Path path) {
        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Skip the line", 1));
        Order referrer = referrer();
        jdbc.update("UPDATE tickets SET state = 'refunded' WHERE order_id = ?", referrer.getId());
        friend(referrer, Ticket.STATE_ISSUED, Ticket.STATE_ISSUED);

        if (path == Path.REFERRER) perkService.evaluate(referrer.getId());
        else perkService.evaluateEvent(event.getId());

        assertThat(orders.countValidReferredTickets(referrer.getId())).isEqualTo(2);
        assertThat(unlocks.findById(referrer.getId())).isEmpty();
    }

    @Test
    void loweringTheThresholdUnlocksBuyersAlreadyPastIt() {
        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Skip the line", 3));
        Order referrer = referrer();
        friend(referrer, Ticket.STATE_ISSUED, Ticket.STATE_REDEEMED);
        perkService.evaluate(referrer.getId());
        assertThat(unlocks.findById(referrer.getId())).isEmpty();

        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Skip the line", 2));

        assertThat(unlocks.findById(referrer.getId())).get()
                .extracting(ReferralPerkUnlock::getThreshold).isEqualTo((short) 2);
    }

    @Test
    void editingThePerkKeepsTheLabelExistingUnlocksWerePromised() {
        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Skip the line", 1));
        Order early = referrer();
        friend(early, Ticket.STATE_ISSUED);
        perkService.evaluate(early.getId());

        perkService.put(owner, event.getId(), new PutReferralPerkRequest("  Free coat check  ", 1));
        Order late = referrer();
        friend(late, Ticket.STATE_ISSUED);
        perkService.evaluate(late.getId());

        assertThat(unlocks.findById(early.getId()).orElseThrow().getLabel()).isEqualTo("Skip the line");
        assertThat(orderPageUnlock(early).label()).isEqualTo("Skip the line");
        assertThat(unlocks.findById(late.getId()).orElseThrow().getLabel()).isEqualTo("Free coat check");
    }

    @Test
    void stoppingNewUnlocksKeepsExistingOnesAndBlocksNewOnes() {
        perkService.put(owner, event.getId(), new PutReferralPerkRequest("Skip the line", 1));
        Order early = referrer();
        friend(early, Ticket.STATE_ISSUED);
        perkService.evaluate(early.getId());

        perkService.stop(owner, event.getId());
        Order late = referrer();
        friend(late, Ticket.STATE_ISSUED);
        perkService.evaluate(late.getId());
        perkService.evaluateEvent(event.getId());

        assertThat(unlocks.findById(early.getId())).isPresent();
        assertThat(orderPageUnlock(early).label()).isEqualTo("Skip the line");
        assertThat(unlocks.findById(late.getId())).isEmpty();
        PublicOrderResponse.Referral lateCard = orderPage(late);
        assertThat(lateCard.perk()).isNull();
        assertThat(lateCard.unlock()).isNull();
    }

    @Test
    void withPerksSwitchedOffNothingIsPromised() {
        flips.set(referralProps, "perksEnabled", false);
        perks.upsert(event.getId(), "Skip the line", (short) 1, Instant.now(), null);
        Order referrer = referrer();
        friend(referrer, Ticket.STATE_ISSUED, Ticket.STATE_ISSUED);

        perkService.evaluate(referrer.getId());
        perkService.evaluateEvent(event.getId());

        assertThat(unlocks.findById(referrer.getId())).isEmpty();
        PublicOrderResponse.Referral card = orderPage(referrer);
        assertThat(card.tickets()).isEqualTo(2);
        assertThat(card.perk()).isNull();
        assertThat(card.unlock()).isNull();
        assertThatThrownBy(() -> perkService.put(owner, event.getId(), new PutReferralPerkRequest("Coat check", 2)))
                .isInstanceOf(ApiException.class).hasMessageContaining("not found");
    }

    private PublicOrderResponse.Unlock orderPageUnlock(Order order) {
        PublicOrderResponse.Unlock unlock = orderPage(order).unlock();
        assertThat(unlock).isNotNull();
        return unlock;
    }

    private PublicOrderResponse.Referral orderPage(Order order) {
        Order fresh = orders.findById(order.getId()).orElseThrow();
        return referralService.forOrder(fresh, events.findById(event.getId()).orElseThrow(),
                tickets.findByOrderIdOrderByCreatedAtAsc(fresh.getId()));
    }

    private void buyFree(TicketTier tier, int qty, String code) {
        freeCheckout.issueFreeOrder(event, tier, qty, fx.email("friend"), null, false, false,
                new CheckoutAttribution(null, null, null, null, code), null);
    }

    private Order referrer() {
        Order o = fx.order(event, fx.email("referrer"));
        fx.ticket(o, Ticket.STATE_ISSUED);
        return o;
    }

    /** A friend's LIVE order bought with the referrer's link, one ticket per state, created in that order. */
    private Order friend(Order referrer, String... ticketStates) {
        Order o = new Order();
        o.setToken("ORD_" + UUID.randomUUID());
        o.setEventId(event.getId());
        o.setOrgId(event.getOrgId());
        o.setEmail(fx.email("friend"));
        o.setTotalMinor(1500L * ticketStates.length);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setReferredByOrderId(referrer.getId());
        o = orders.save(o);
        for (String state : ticketStates) fx.ticket(o, state);
        return o;
    }
}
