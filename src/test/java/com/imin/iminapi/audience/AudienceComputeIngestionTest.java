package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.*;
import com.imin.iminapi.audience.repository.*;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.model.*;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Idempotent ingestion and backfill: replays count once, replay == backfill, one consumer per address across
 * orgs. The pure banding functions are covered by {@link AudienceBandingTest}.
 */
@IminIntegrationTest
class AudienceComputeIngestionTest {

    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TicketRepository ticketRepo;

    @Autowired AudienceOrderProjector orderProjector;
    @Autowired AudienceBackfillJob backfillJob;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;

    private final List<UUID> ownOrgs = new ArrayList<>();
    private UUID orgId;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgId = org.getId();
        ownOrgs.add(orgId);
        User owner = fx.owner(org);
        // The event is in the PAST: an unscanned ticket only becomes a no-show once the
        // event it was bought for has ended (audience-3). An undated fixture would now
        // (correctly) project no_show = 0 and say nothing about the no-show rule.
        eventId = fx.event(org, owner, EventStatus.DRAFT, clock.instant().minus(30, ChronoUnit.DAYS)).getId();
    }

    /** Own memberships, then own orgs (events cascade orders and tickets). */
    @AfterEach
    void tearDown() {
        try {
            for (UUID org : ownOrgs) jdbc.update("delete from memberships where org_id = ?", org);
        } finally {
            OrgRows.delete(jdbc, ownOrgs);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IDEMPOTENCY: double order event counts once (S1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void idempotency_double_order_event_counts_once() {
        // Simulate the same TicketsIssuedEvent arriving twice for the same order
        Order order = saveOrder(fx.email("double"), 1000L);

        // First projection
        orderProjector.upsertMembership(orgId, EmailNormalizer.normalize(order.getEmail()), order.getEmail());

        Consumer c = consumerRepo.findByNormalizedEmail(order.getEmail()).orElseThrow();
        Membership after1 = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(after1.getOrders()).isEqualTo(1);
        assertThat(after1.getSpendMinor()).isEqualTo(1000L);

        // Second projection (replay/duplicate event)
        orderProjector.upsertMembership(orgId, EmailNormalizer.normalize(order.getEmail()), order.getEmail());

        Membership after2 = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        // Counts must still be 1 — derived from source, not incremented
        assertThat(after2.getOrders()).isEqualTo(1);
        assertThat(after2.getSpendMinor()).isEqualTo(1000L);
    }

    @Test
    void idempotency_two_orders_distinct_correctly_counted() {
        String email = fx.email("two");
        saveOrder(email, 1000L);
        saveOrder(email, 2000L);

        orderProjector.upsertMembership(orgId, email, "Two");
        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(m.getOrders()).isEqualTo(2);
        assertThat(m.getSpendMinor()).isEqualTo(3000L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IDEMPOTENCY: double scan (same ticket) counts attended once
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void idempotency_double_scan_attended_counts_once() {
        String email = fx.email("scan");
        Order order = saveOrder(email, 1500L);
        // Issue a ticket in REDEEMED state (simulate: ticket was redeemed)
        saveTicket(order, Ticket.STATE_REDEEMED);

        // First projection (after first scan)
        orderProjector.upsertMembership(orgId, email, "Scan");
        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(m.getAttended()).isEqualTo(1);

        // Second projection (duplicate event for same scan)
        orderProjector.upsertMembership(orgId, email, "Scan");
        Membership m2 = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        // attended still 1 — derived from source tickets, not incremented
        assertThat(m2.getAttended()).isEqualTo(1);
    }

    @Test
    void idempotency_no_show_counted_correctly() {
        String email = fx.email("noshow");
        Order order = saveOrder(email, 1200L);
        saveTicket(order, Ticket.STATE_ISSUED); // issued but never redeemed

        orderProjector.upsertMembership(orgId, email, "NoShow");
        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(m.getAttended()).isEqualTo(0);
        assertThat(m.getNoShow()).isEqualTo(1);
    }

    /**
     * audience-9: the INSERT-first Consumer upsert catches DataIntegrityViolationException,
     * but Consumer ids are assigned in memory so a plain save() issued no statement and the
     * catch could never fire — the violation arrived at the next auto-flush, outside the
     * try, and took the whole projection transaction with it. The flushing variant is what
     * makes the documented guard reachable.
     */
    @Test
    void a_duplicate_consumer_insert_fails_inside_the_flushing_save() {
        String email = fx.email("raced");
        orderProjector.upsertMembership(orgId, email, "Raced");

        Consumer duplicate = new Consumer();
        duplicate.setNormalizedEmail(email);
        duplicate.setDisplayName("Raced again");

        assertThatThrownBy(() -> consumerRepo.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** One membership per (org, consumer): a second row for the same pair is refused by the schema. */
    @Test
    void schema_unique_org_consumer() {
        Consumer c = new Consumer();
        c.setNormalizedEmail(fx.email("bob"));
        c.setDisplayName("Bob");
        Consumer saved = consumerRepo.save(c);
        membershipRepo.save(membership(orgId, saved));

        assertThatThrownBy(() -> membershipRepo.save(membership(orgId, saved)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REPLAY == BACKFILL: identical membership rows
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void replay_equals_backfill_identical_membership_rows() {
        String email = fx.email("replay");
        // Seed source data: 2 orders, 1 redeemed ticket
        Order o1 = saveOrder(email, 1000L);
        Order o2 = saveOrder(email, 2000L);
        saveTicket(o1, Ticket.STATE_REDEEMED);
        saveTicket(o2, Ticket.STATE_ISSUED);

        // Live ingestion path: two calls to upsertMembership (as if two TicketsIssuedEvents)
        orderProjector.upsertMembership(orgId, email, "Replay");
        orderProjector.upsertMembership(orgId, email, "Replay");

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership live = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();

        // Snapshot live projection
        int liveOrders = live.getOrders();
        long liveSpend = live.getSpendMinor();
        int liveAttended = live.getAttended();
        int liveNoShow = live.getNoShow();
        short liveRfmR = live.getRfmR();
        short liveRfmF = live.getRfmF();
        short liveRfmM = live.getRfmM();
        String liveLifecycle = live.getLifecycle();

        // Now drop ONLY this person's projection (leave source data intact)
        jdbc.update("delete from memberships where org_id = ?", orgId);
        jdbc.update("delete from consumers where normalized_email = ?", email);

        // Backfill path: runs through the same upsertMembership.
        // onStartup already ran the job THROUGH the ShedLock proxy (lockAtLeastFor=PT1M),
        // so a direct run() here would be a silent no-op while that lock is held. Hand the
        // lock back (expire, never delete: a deleted row makes every later acquire fail).
        jdbc.update("update shedlock set lock_until = locked_at where name = 'audience_backfill'");
        backfillJob.run();

        Consumer c2 = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership backfilled = membershipRepo.findByOrgIdAndConsumerId(orgId, c2.getConsumerId()).orElseThrow();

        // Aggregates must match
        assertThat(backfilled.getOrders()).as("orders").isEqualTo(liveOrders);
        assertThat(backfilled.getSpendMinor()).as("spendMinor").isEqualTo(liveSpend);
        assertThat(backfilled.getAttended()).as("attended").isEqualTo(liveAttended);
        assertThat(backfilled.getNoShow()).as("noShow").isEqualTo(liveNoShow);
        assertThat(backfilled.getRfmR()).as("rfmR").isEqualTo(liveRfmR);
        assertThat(backfilled.getRfmF()).as("rfmF").isEqualTo(liveRfmF);
        assertThat(backfilled.getRfmM()).as("rfmM").isEqualTo(liveRfmM);
        assertThat(backfilled.getLifecycle()).as("lifecycle").isEqualTo(liveLifecycle);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IDENTITY: email casing/whitespace = one consumer
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void identity_email_casing_variants_resolve_to_one_consumer() {
        String email = fx.email("ann");
        String capitalised = Character.toUpperCase(email.charAt(0)) + email.substring(1);
        orderProjector.upsertMembership(orgId, EmailNormalizer.normalize(capitalised + " "), "Ann");
        orderProjector.upsertMembership(orgId, EmailNormalizer.normalize(email), "ann");
        orderProjector.upsertMembership(orgId, EmailNormalizer.normalize(" " + email.toUpperCase()), "ANN");

        Optional<Consumer> consumer = consumerRepo.findByNormalizedEmail(email);
        assertThat(consumer).isPresent();
        // Exactly one membership exists for this org, so the three variants are one consumer
        assertThat(membershipRepo.countByOrgId(orgId)).isEqualTo(1);
    }

    @Test
    void identity_same_consumer_two_orgs_two_memberships_one_consumer() {
        UUID orgBId = fx.org().getId();
        ownOrgs.add(orgBId);

        String email = fx.email("cross");
        orderProjector.upsertMembership(orgId, email, email);
        orderProjector.upsertMembership(orgBId, email, email);

        Consumer consumer = consumerRepo.findByNormalizedEmail(email).orElseThrow();

        // Two memberships, one consumer
        assertThat(membershipRepo.countByOrgId(orgId)).isEqualTo(1);
        assertThat(membershipRepo.countByOrgId(orgBId)).isEqualTo(1);
        assertThat(membershipRepo.findByOrgIdAndConsumerId(orgId, consumer.getConsumerId())).isPresent();
        assertThat(membershipRepo.findByOrgIdAndConsumerId(orgBId, consumer.getConsumerId())).isPresent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INGESTION: first_touch_src = 'organic' (S3), no consent written (S5)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void ingestion_first_touch_is_organic() {
        String email = fx.email("organic");
        saveOrder(email, 800L);
        orderProjector.upsertMembership(orgId, email, "O");
        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(m.getFirstTouchSrc()).isEqualTo("organic");
    }

    @Test
    void ingestion_no_consent_row_written_gate_ships_closed() {
        String email = fx.email("noconsent");
        saveOrder(email, 500L);
        orderProjector.upsertMembership(orgId, email, "NC");
        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        // S5: gate ships CLOSED — no consent means basis=null, status=never
        assertThat(m.getConsentStatus()).isEqualTo("never");
        assertThat(m.getConsentBasis()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RFM + LIFECYCLE COMPUTED CORRECTLY FROM ORDERS
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void ingestion_rfm_bands_computed_from_orders() {
        String email = fx.email("rfm");
        // One recent order of 25000 minor → R=high (recent), F=1, M=4
        saveOrder(email, 25_000L);
        orderProjector.upsertMembership(orgId, email, "RFM");
        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();

        // Orders were just created → recency very small → R should be 5
        assertThat(m.getRfmR()).isEqualTo((short) 5);
        assertThat(m.getRfmF()).isEqualTo((short) 1);   // 1 order
        assertThat(m.getRfmM()).isEqualTo((short) 4);   // 20000–49999
    }

    @Test
    void ingestion_lifecycle_firsttime_after_one_event() {
        String email = fx.email("ft");
        saveOrder(email, 1000L);
        orderProjector.upsertMembership(orgId, email, "FT");
        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(m.getLifecycle()).isEqualTo(LifecycleClassifier.FIRSTTIME);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static Membership membership(UUID orgId, Consumer c) {
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        return m;
    }

    private Order saveOrder(String email, long totalMinor) {
        Order o = new Order();
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setEmail(email);
        o.setTotalMinor(totalMinor);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setToken(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setStripePaymentIntentId("pi_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        o.setCreatedAt(clock.instant());
        return orderRepo.save(o);
    }

    private Ticket saveTicket(Order order, String state) {
        Ticket t = new Ticket();
        t.setOrderId(order.getId());
        t.setEventId(order.getEventId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor((int) order.getTotalMinor());
        t.setState(state);
        t.setToken(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        if (Ticket.STATE_REDEEMED.equals(state)) {
            t.setRedeemedAt(clock.instant());
        }
        return ticketRepo.save(t);
    }
}
