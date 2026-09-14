package com.imin.iminapi.dispute;

import com.imin.iminapi.config.TestRateLimitConfig;
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
import com.stripe.StripeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The backstop half of the dispute-before-order race: a dispute ingested with no order attaches
 * on the next sweep, whatever order the two webhooks were delivered in and even when the
 * checkout call site never ran (a dispute whose charge was unreadable at ingest).
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class DisputeAttributionSweeperTest {

    @Autowired DisputeAttributionSweeper sweeper;
    @Autowired DisputeRepository disputes;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Spied, not stubbed: the passes must really run — this only counts which rows pass 2 got. */
    @MockitoSpyBean DisputeIngestService ingest;

    @MockitoBean StripeClient stripeClient;

    /** The sweep lock is rewound here before each tick; a real acquisition must move it past. */
    private static final Instant LOCK_REWOUND_TO = Instant.parse("2020-01-01T00:00:00Z");

    private Organization org;
    private Event event;

    @BeforeEach
    void setUp() {
        wipe();
        releaseSweepLock();

        org = new Organization();
        org.setName("Sweep Org");
        org.setSlug("sweep-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("sweep@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("sweep-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Sweep Night");
        event.setSlug("sweep-event-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setCurrency("EUR");
        event.setCreatedBy(owner.getId());
        event = events.save(event);
    }

    @AfterEach
    void tearDown() {
        wipe();
    }

    @Test
    void attachesAnOrphanWhoseOrderExistsNow() {
        Order order = order("pi_sweep_1", false);
        Ticket t = ticket(order);
        Dispute orphan = orphan("du_sweep_1", "pi_sweep_1", Instant.now(), DisputeStatus.OPEN);

        sweeper.sweep();

        Dispute attached = disputes.findById(orphan.getId()).orElseThrow();
        assertThat(attached.getOrderId()).isEqualTo(order.getId());
        assertThat(attached.getEventId()).isEqualTo(event.getId());
        assertThat(attached.isTestMode())
                .as("the order records whether the money was real, not the running key")
                .isEqualTo(order.isTestMode());
        assertThat(tickets.findById(t.getId()).orElseThrow().getState())
                .isEqualTo(Ticket.STATE_REVOKED);
        assertThat(sweepLockStamped()).isTrue();
    }

    /** A test-era order keeps its era when the sweep runs later under a live key. */
    @Test
    void aTestModeOrderStampsTheDisputeTestMode() {
        order("pi_sweep_test", true);
        Dispute orphan = orphan("du_sweep_test", "pi_sweep_test", Instant.now(), DisputeStatus.OPEN);

        sweeper.sweep();

        assertThat(disputes.findById(orphan.getId()).orElseThrow().isTestMode()).isTrue();
    }

    /**
     * The orphan's org was guessed from the charge's transfer destination at ingest; the order
     * is the precise answer, and the payout freeze is counted per org.
     */
    @Test
    void theSweepStampsTheOrgFromTheOrder() {
        Organization guessed = new Organization();
        guessed.setName("Guessed Org");
        guessed.setSlug("guessed-org-" + UUID.randomUUID().toString().substring(0, 8));
        guessed.setContactEmail("guessed@example.com");
        guessed.setCountry("DE");
        guessed = orgs.save(guessed);

        Order order = order("pi_sweep_org", false);
        Dispute orphan = orphan("du_sweep_org", "pi_sweep_org", Instant.now(), DisputeStatus.OPEN);
        orphan.setOrgId(guessed.getId());
        orphan = disputes.save(orphan);

        sweeper.sweep();

        assertThat(disputes.findById(orphan.getId()).orElseThrow().getOrgId())
                .isEqualTo(order.getOrgId());
    }

    /** Every five minutes forever: a second tick must not re-write or re-revoke anything. */
    @Test
    void aSecondRunChangesNothing() {
        order("pi_sweep_2", false);
        Dispute orphan = orphan("du_sweep_2", "pi_sweep_2", Instant.now(), DisputeStatus.OPEN);

        sweeper.sweep();
        Dispute afterFirst = disputes.findById(orphan.getId()).orElseThrow();

        releaseSweepLock();
        sweeper.sweep();

        Dispute afterSecond = disputes.findById(orphan.getId()).orElseThrow();
        assertThat(afterSecond.getOrderId()).isEqualTo(afterFirst.getOrderId());
        assertThat(afterSecond.getUpdatedAt())
                .as("the conditional update must not fire again on an attached row")
                .isEqualTo(afterFirst.getUpdatedAt());
    }

    /** Past the window the order is never going to turn up; this needs a human, not a retry. */
    @Test
    void ignoresAnOrphanOlderThanTheWindow() {
        order("pi_sweep_old", false);
        Dispute orphan = orphan("du_sweep_old", "pi_sweep_old",
                Instant.now().minus(4, ChronoUnit.DAYS), DisputeStatus.OPEN);

        sweeper.sweep();

        assertThat(disputes.findById(orphan.getId()).orElseThrow().getOrderId()).isNull();
    }

    /** A dispute whose charge was unreadable has no PI id — no query can match it to an order. */
    @Test
    void ignoresAnOrphanWithNoPaymentIntentId() {
        order("pi_sweep_3", false);
        Dispute orphan = orphan("du_sweep_3", null, Instant.now(), DisputeStatus.OPEN);

        sweeper.sweep();

        assertThat(disputes.findById(orphan.getId()).orElseThrow().getOrderId()).isNull();
    }

    /**
     * The convergence case: order_id is already set, so no attach path can ever reach this row
     * again and its ticket stayed scannable through the chargeback.
     */
    @Test
    void revokesTicketsOnADisputeAlreadyAttachedToItsOrder() {
        Order order = order("pi_sweep_attributed", false);
        Ticket t = ticket(order);
        attributed("du_sweep_attributed", order, Instant.now(), DisputeStatus.OPEN);

        sweeper.sweep();

        assertThat(tickets.findById(t.getId()).orElseThrow().getState())
                .isEqualTo(Ticket.STATE_REVOKED);
    }

    /** A win gave the money back; pass 2 must not kill a working ticket over it. */
    @Test
    void leavesAWonAttributedDisputesTicketsAlone() {
        Order order = order("pi_sweep_won", false);
        Ticket t = ticket(order);
        attributed("du_sweep_won", order, Instant.now(), DisputeStatus.WON);

        sweeper.sweep();

        assertThat(tickets.findById(t.getId()).orElseThrow().getState())
                .isEqualTo(Ticket.STATE_ISSUED);
    }

    /** Same bound as pass 1: past the window a row needs a human, not another five-minute retry. */
    @Test
    void ignoresAnAttributedDisputeOlderThanTheWindow() {
        Order order = order("pi_sweep_attr_old", false);
        Ticket t = ticket(order);
        attributed("du_sweep_attr_old", order, Instant.now().minus(4, ChronoUnit.DAYS),
                DisputeStatus.OPEN);

        sweeper.sweep();

        assertThat(tickets.findById(t.getId()).orElseThrow().getState())
                .isEqualTo(Ticket.STATE_ISSUED);
    }

    /**
     * Both halves of one tick, on two different orders: the orphan attaches and is revoked by
     * pass 1, the already-attributed row is revoked by pass 2, and pass 1's row is revoked
     * exactly once — pass 2 is handed the ids pass 1 attached rather than inferring them from
     * whatever the persistence context has flushed.
     */
    @Test
    void bothPassesConvergeInOneTickAndPassOnesRowIsRevokedOnce() {
        Order attachedLate = order("pi_sweep_mix_a", false);
        Ticket ticketA = ticket(attachedLate);
        Dispute orphanA = orphan("du_sweep_mix_a", "pi_sweep_mix_a", Instant.now(), DisputeStatus.OPEN);

        Order alreadyAttributed = order("pi_sweep_mix_b", false);
        Ticket ticketB = ticket(alreadyAttributed);
        Dispute attributedB = attributed("du_sweep_mix_b", alreadyAttributed, Instant.now(),
                DisputeStatus.OPEN);

        sweeper.sweep();

        assertThat(disputes.findById(orphanA.getId()).orElseThrow().getOrderId())
                .isEqualTo(attachedLate.getId());
        assertThat(tickets.findById(ticketA.getId()).orElseThrow().getState())
                .isEqualTo(Ticket.STATE_REVOKED);
        assertThat(tickets.findById(ticketB.getId()).orElseThrow().getState())
                .isEqualTo(Ticket.STATE_REVOKED);

        ArgumentCaptor<Dispute> passTwo = ArgumentCaptor.forClass(Dispute.class);
        verify(ingest, times(1)).revokeAttributed(passTwo.capture(), any());
        assertThat(passTwo.getValue().getId())
                .as("pass 1 already revoked the orphan; pass 2 must act only on the other order")
                .isEqualTo(attributedB.getId());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Order order(String paymentIntentId, boolean testMode) {
        Order o = new Order();
        o.setToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(1500);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setTestMode(testMode);
        o.setStripePaymentIntentId(paymentIntentId);
        return orders.save(o);
    }

    private Ticket ticket(Order order) {
        Ticket t = new Ticket();
        t.setToken("tkt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        t.setOrderId(order.getId());
        t.setEventId(event.getId());
        t.setTierId(UUID.randomUUID());   // tier_id is deliberately not a FK (V24)
        t.setTierName("GA");
        t.setPriceMinor(1500);
        t.setState(Ticket.STATE_ISSUED);
        return tickets.save(t);
    }

    /** A dispute that already carries its order — what pass 1 can never see. */
    private Dispute attributed(String disputeId, Order order, Instant createdAt,
                               DisputeStatus status) {
        Dispute d = orphan(disputeId, order.getStripePaymentIntentId(), createdAt, status);
        d.setOrderId(order.getId());
        d.setEventId(order.getEventId());
        return disputes.save(d);
    }

    private Dispute orphan(String disputeId, String paymentIntentId, Instant createdAt,
                           DisputeStatus status) {
        Dispute d = new Dispute();
        d.setStripeDisputeId(disputeId);
        d.setOrgId(org.getId());
        d.setStripePaymentIntentId(paymentIntentId);
        d.setAmountMinor(1500);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setCreatedAt(createdAt);
        d.setOpenedAt(createdAt);
        return disputes.save(d);
    }

    private void wipe() {
        disputes.deleteAll();
        tickets.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    /**
     * {@code lockAtLeastFor = "PT30S"} would hold the sweep lock for the rest of this shared
     * context and silently skip every later tick. Expired, not deleted: ShedLock remembers the
     * row exists and only ever UPDATEs it.
     */
    private void releaseSweepLock() {
        Timestamp rewound = Timestamp.from(LOCK_REWOUND_TO);
        jdbc.update("update shedlock set lock_until = ?, locked_at = ? where name = ?",
                rewound, rewound, "DisputeAttributionSweeper.sweep");
    }

    /** True once ShedLock has granted and stamped the sweep lock, i.e. the tick was not skipped. */
    private boolean sweepLockStamped() {
        Long stamped = jdbc.queryForObject(
                "select count(*) from shedlock where name = ? and locked_at > ?",
                Long.class, "DisputeAttributionSweeper.sweep", Timestamp.from(LOCK_REWOUND_TO));
        return stamped != null && stamped == 1L;
    }
}
