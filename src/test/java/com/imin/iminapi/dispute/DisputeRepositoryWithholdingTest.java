package com.imin.iminapi.dispute;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DisputeRepository#hasOpenOrLostByOrderId(UUID)} is the refund money gate, and it runs
 * a derived {@code … StatusIn} query over a converter-mapped enum — the status is stored in its
 * lowercase wire form, so a mapping that regressed to the constant name would match nothing and
 * silently let a charged-back order be refunded. Mocks cannot see that; this executes the query.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DisputeRepositoryWithholdingTest {

    @Autowired DisputeRepository disputes;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private Organization org;
    private Event event;

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.setName("Dispute Org");
        org.setSlug("dispute-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("disputes@test.example");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Chargeback Night");
        e.setSlug("chargeback-night-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setCreatedBy(owner.getId());
        event = events.save(e);
    }

    private Order newOrder() {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(1149);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        return orders.save(o);
    }

    private Ticket newTicket(Order o, String state) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        t.setOrderId(o.getId());
        t.setEventId(event.getId());
        t.setTierId(UUID.randomUUID());   // tier_id is deliberately not a FK (V24)
        t.setTierName("GA");
        t.setPriceMinor(1149);
        t.setState(state);
        return tickets.saveAndFlush(t);
    }

    private Dispute newDispute(Order o, DisputeStatus status) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().substring(0, 12));
        d.setOrgId(org.getId());
        d.setEventId(event.getId());
        d.setOrderId(o.getId());
        d.setAmountMinor(1149);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setOpenedAt(Instant.now().minusSeconds(3600));
        return disputes.saveAndFlush(d);
    }

    /**
     * The restore gate. A LOST sibling still withholds — the money is gone for good — so a win
     * on the same order must leave the tickets revoked instead of restoring what the next sweep
     * would immediately re-revoke.
     */
    @Test
    void counts_a_lost_sibling_on_the_same_order() {
        Order o = newOrder();
        Dispute won = newDispute(o, DisputeStatus.WON);
        newDispute(o, DisputeStatus.LOST);

        assertThat(disputes.countOtherOpenOrLostByOrderId(o.getId(), won.getId()))
                .as("an OPEN-only gate would read 0 here and hand back working tickets")
                .isEqualTo(1L);
    }

    /** The closing row can still read OPEN in the DB when the gate runs; it must not gate itself. */
    @Test
    void excludes_the_dispute_that_just_closed_from_its_own_gate() {
        Order o = newOrder();
        Dispute self = newDispute(o, DisputeStatus.OPEN);

        assertThat(disputes.countOtherOpenOrLostByOrderId(o.getId(), self.getId())).isZero();
    }

    /** Legacy rows carry {@code pre} as a synonym for {@code issued}; they must still be found. */
    @Test
    void finds_an_attributed_withholding_dispute_whose_tickets_are_still_live() {
        Order o = newOrder();
        newTicket(o, "pre");
        newDispute(o, DisputeStatus.OPEN);

        assertThat(disputes.findAttributedWithLiveTickets(DisputeWithholding.STATUSES,
                Instant.now().minus(3, ChronoUnit.DAYS), PageRequest.of(0, 200)))
                .as("a legacy pre-state ticket is scannable and must converge like an issued one")
                .hasSize(1);
    }

    /** The converged case: the sweep's second pass must do no work at all on it. */
    @Test
    void skips_an_order_whose_tickets_are_all_revoked_or_refunded() {
        Order o = newOrder();
        newTicket(o, Ticket.STATE_REVOKED);
        newTicket(o, Ticket.STATE_REFUNDED);
        newDispute(o, DisputeStatus.OPEN);

        assertThat(disputes.findAttributedWithLiveTickets(DisputeWithholding.STATUSES,
                Instant.now().minus(3, ChronoUnit.DAYS), PageRequest.of(0, 200)))
                .isEmpty();
    }

    @Test
    void skips_a_won_attributed_dispute() {
        Order o = newOrder();
        newTicket(o, Ticket.STATE_ISSUED);
        newDispute(o, DisputeStatus.WON);

        assertThat(disputes.findAttributedWithLiveTickets(DisputeWithholding.STATUSES,
                Instant.now().minus(3, ChronoUnit.DAYS), PageRequest.of(0, 200)))
                .as("a win gave the money back — nothing to revoke")
                .isEmpty();
    }

    @Test
    void an_open_dispute_withholds_the_order() {
        Order o = newOrder();
        newDispute(o, DisputeStatus.OPEN);

        assertThat(disputes.hasOpenOrLostByOrderId(o.getId())).isTrue();
    }

    @Test
    void a_lost_dispute_withholds_the_order_too() {
        Order o = newOrder();
        newDispute(o, DisputeStatus.LOST);

        assertThat(disputes.hasOpenOrLostByOrderId(o.getId())).isTrue();
    }

    @Test
    void a_won_dispute_does_not_withhold_the_order() {
        Order o = newOrder();
        newDispute(o, DisputeStatus.WON);

        assertThat(disputes.hasOpenOrLostByOrderId(o.getId()))
                .as("a win gave the money back — the refund gate must open again")
                .isFalse();
    }

    @Test
    void an_order_with_no_dispute_is_not_withheld() {
        Order o = newOrder();

        assertThat(disputes.hasOpenOrLostByOrderId(o.getId())).isFalse();
    }
}
