package com.imin.iminapi.dispute;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DisputeRepository#hasOpenOrLostByOrderId(UUID)} is the refund money gate, and it runs
 * a derived {@code … StatusIn} query over a converter-mapped enum — the status is stored in its
 * lowercase wire form, so a mapping that regressed to the constant name would match nothing and
 * silently let a charged-back order be refunded. Mocks cannot see that; this executes the query.
 * The sweep finder reads every org, so its assertions name this test's own dispute.
 */
@IminIntegrationTest
class DisputeRepositoryWithholdingTest {

    @Autowired DisputeRepository disputes;
    @Autowired TicketRepository tickets;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private Event event;

    @BeforeEach
    void setUp() {
        org = fx.org();
        User owner = fx.owner(org);
        event = fx.event(org, owner, EventStatus.LIVE, null);
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, List.of(org.getId()));
    }

    private Order newOrder() {
        return fx.order(event, fx.email("buyer"));
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
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", ""));
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
        Dispute d = newDispute(o, DisputeStatus.OPEN);

        assertThat(attributedWithLiveTickets())
                .as("a legacy pre-state ticket is scannable and must converge like an issued one")
                .contains(d.getId());
    }

    /** The converged case: the sweep's second pass must do no work at all on it. */
    @Test
    void skips_an_order_whose_tickets_are_all_revoked_or_refunded() {
        Order o = newOrder();
        newTicket(o, Ticket.STATE_REVOKED);
        newTicket(o, Ticket.STATE_REFUNDED);
        Dispute d = newDispute(o, DisputeStatus.OPEN);

        assertThat(attributedWithLiveTickets()).doesNotContain(d.getId());
    }

    @Test
    void skips_a_won_attributed_dispute() {
        Order o = newOrder();
        newTicket(o, Ticket.STATE_ISSUED);
        Dispute d = newDispute(o, DisputeStatus.WON);

        assertThat(attributedWithLiveTickets())
                .as("a win gave the money back — nothing to revoke")
                .doesNotContain(d.getId());
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

    /** The finder's ids over a page large enough for every test's rows in the shared database. */
    private List<UUID> attributedWithLiveTickets() {
        return disputes.findAttributedWithLiveTickets(DisputeWithholding.STATUSES,
                        Instant.now().minus(3, ChronoUnit.DAYS), PageRequest.of(0, 10_000))
                .stream().map(Dispute::getId).toList();
    }
}
