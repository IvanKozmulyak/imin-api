package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.*;
import com.imin.iminapi.audience.repository.*;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests covering COMPUTE (pure functions) + IDEMPOTENT INGESTION + BACKFILL.
 *
 * Pure-fn unit tests: LifecycleClassifier, RfmBander, EmailNormalizer.
 * Integration tests: double-order counts once; double-scan counts once;
 * replay == backfill produces identical rows; email casing identity;
 * two orgs = two memberships one consumer.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceComputeIngestionTest {

    // ── Audience repos
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;

    // ── Source repos
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TicketRepository ticketRepo;

    // ── Services under test
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired MembershipProjector membershipProjector;
    @Autowired AudienceBackfillJob backfillJob;

    // AuditLogger is wired into several services — stub it so it doesn't throw
    @MockitoBean AuditLogger auditLogger;

    @Autowired DataSource dataSource;

    private UUID orgId;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        wipe();
        Organization org = new Organization();
        org.setName("Compute Test Org");
        org.setSlug("compute-test-" + UUID.randomUUID().toString().substring(0, 6));
        org.setContactEmail("compute@test.com");
        org.setCountry("DE");
        org = orgRepo.save(org);
        orgId = org.getId();

        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@test.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        owner = userRepo.save(owner);

        Event ev = new Event();
        ev.setOrgId(orgId);
        ev.setName("Test Event");
        ev.setSlug("test-event-" + UUID.randomUUID().toString().substring(0, 6));
        ev.setGenre("techno");
        ev.setType("club");
        ev.setCreatedBy(owner.getId());
        ev = eventRepo.save(ev);
        eventId = ev.getId();
    }

    @AfterEach
    void tearDown() { wipe(); }

    // ─────────────────────────────────────────────────────────────────────────
    // PURE FUNCTION: EmailNormalizer
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void normalizer_lower_trims_whitespace() {
        assertThat(EmailNormalizer.normalize("  Ann@X.com ")).isEqualTo("ann@x.com");
        assertThat(EmailNormalizer.normalize("ANN@X.COM")).isEqualTo("ann@x.com");
        assertThat(EmailNormalizer.normalize("ann@x.com")).isEqualTo("ann@x.com");
    }

    @Test
    void normalizer_null_returns_null() {
        assertThat(EmailNormalizer.normalize(null)).isNull();
    }

    @Test
    void normalizer_is_idempotent() {
        String raw = "  User@Example.COM  ";
        String once = EmailNormalizer.normalize(raw);
        String twice = EmailNormalizer.normalize(once);
        assertThat(once).isEqualTo(twice);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PURE FUNCTION: RfmBander
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void rfm_recency_bands() {
        assertThat(RfmBander.recency(null)).isEqualTo((short) 0);
        assertThat(RfmBander.recency(200)).isEqualTo((short) 0); // >180
        assertThat(RfmBander.recency(150)).isEqualTo((short) 1); // 91–180
        assertThat(RfmBander.recency(80)).isEqualTo((short)  2); // 61–90
        assertThat(RfmBander.recency(45)).isEqualTo((short)  3); // 31–60
        assertThat(RfmBander.recency(20)).isEqualTo((short)  4); // 15–30
        assertThat(RfmBander.recency(7)).isEqualTo((short)   5); // 0–14
    }

    @Test
    void rfm_frequency_bands() {
        assertThat(RfmBander.frequency(0)).isEqualTo((short) 0);
        assertThat(RfmBander.frequency(1)).isEqualTo((short) 1);
        assertThat(RfmBander.frequency(2)).isEqualTo((short) 2);
        assertThat(RfmBander.frequency(4)).isEqualTo((short) 3); // 3–5
        assertThat(RfmBander.frequency(7)).isEqualTo((short) 4); // 6–9
        assertThat(RfmBander.frequency(10)).isEqualTo((short) 5);
        assertThat(RfmBander.frequency(99)).isEqualTo((short) 5);
    }

    @Test
    void rfm_monetary_bands() {
        assertThat(RfmBander.monetary(0)).isEqualTo((short) 0);
        assertThat(RfmBander.monetary(500)).isEqualTo((short) 1);    // 1–4999
        assertThat(RfmBander.monetary(7000)).isEqualTo((short) 2);   // 5000–9999
        assertThat(RfmBander.monetary(15000)).isEqualTo((short) 3);  // 10000–19999
        assertThat(RfmBander.monetary(25000)).isEqualTo((short) 4);  // 20000–49999
        assertThat(RfmBander.monetary(55000)).isEqualTo((short) 5);  // >=50000
    }

    @Test
    void rfm_boundary_values() {
        // Exact boundary — 14 is band 5, 15 is band 4
        assertThat(RfmBander.recency(14)).isEqualTo((short) 5);
        assertThat(RfmBander.recency(15)).isEqualTo((short) 4);
        // Spend boundary — 50000 is band 5, 49999 is band 4
        assertThat(RfmBander.monetary(50_000)).isEqualTo((short) 5);
        assertThat(RfmBander.monetary(49_999)).isEqualTo((short) 4);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PURE FUNCTION: LifecycleClassifier
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void lifecycle_prospect_zero_events() {
        Membership m = membershipWithCounts(0, 0, null);
        assertThat(LifecycleClassifier.classify(m)).isEqualTo(LifecycleClassifier.PROSPECT);
    }

    @Test
    void lifecycle_firsttime_one_event() {
        Membership m = membershipWithCounts(1, 500, 30);
        assertThat(LifecycleClassifier.classify(m)).isEqualTo(LifecycleClassifier.FIRSTTIME);
    }

    @Test
    void lifecycle_repeat_two_or_more_events() {
        Membership m = membershipWithCounts(2, 1000, 20);
        assertThat(LifecycleClassifier.classify(m)).isEqualTo(LifecycleClassifier.REPEAT);
    }

    @Test
    void lifecycle_vip_high_spend_and_events() {
        Membership m = membershipWithCounts(4, 20_000, 10);
        assertThat(LifecycleClassifier.classify(m)).isEqualTo(LifecycleClassifier.VIP);
    }

    @Test
    void lifecycle_vip_requires_both_spend_and_event_threshold() {
        // Spend OK but only 3 events → repeat, not VIP
        Membership m = membershipWithCounts(3, 25_000, 10);
        assertThat(LifecycleClassifier.classify(m)).isNotEqualTo(LifecycleClassifier.VIP);
        // Events OK but spend too low → repeat, not VIP
        Membership m2 = membershipWithCounts(4, 19_999, 10);
        assertThat(LifecycleClassifier.classify(m2)).isNotEqualTo(LifecycleClassifier.VIP);
    }

    @Test
    void lifecycle_lapsing_recency_90_to_179() {
        Membership m = membershipWithCounts(1, 500, 90);
        assertThat(LifecycleClassifier.classify(m)).isEqualTo(LifecycleClassifier.LAPSING);
    }

    @Test
    void lifecycle_dormant_recency_180_plus() {
        Membership m = membershipWithCounts(1, 500, 180);
        assertThat(LifecycleClassifier.classify(m)).isEqualTo(LifecycleClassifier.DORMANT);
    }

    @Test
    void lifecycle_wonback_recent_after_prior_dormancy() {
        // Member was dormant, then re-purchased (recency < 90, events >= 2)
        Membership m = membershipWithCounts(2, 1000, 10);
        m.setLifecycle(LifecycleClassifier.DORMANT); // prior state
        assertThat(LifecycleClassifier.classify(m)).isEqualTo(LifecycleClassifier.WONBACK);
    }

    @Test
    void lifecycle_vip_beats_dormant() {
        // VIP takes priority even with high recency
        Membership m = membershipWithCounts(5, 25_000, 200);
        assertThat(LifecycleClassifier.classify(m)).isEqualTo(LifecycleClassifier.VIP);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IDEMPOTENCY: double order event counts once (S1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void idempotency_double_order_event_counts_once() {
        // Simulate the same TicketsIssuedEvent arriving twice for the same order
        com.imin.iminapi.model.Order order = saveOrder("double@x.com", 1000L);

        // First projection
        orderProjector.upsertMembership(orgId, EmailNormalizer.normalize(order.getEmail()), order.getEmail());

        Consumer c = consumerRepo.findByNormalizedEmail("double@x.com").orElseThrow();
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
        com.imin.iminapi.model.Order o1 = saveOrder("two@x.com", 1000L);
        com.imin.iminapi.model.Order o2 = saveOrder("two@x.com", 2000L);

        orderProjector.upsertMembership(orgId, "two@x.com", "Two");
        Consumer c = consumerRepo.findByNormalizedEmail("two@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(m.getOrders()).isEqualTo(2);
        assertThat(m.getSpendMinor()).isEqualTo(3000L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IDEMPOTENCY: double scan (same ticket) counts attended once
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void idempotency_double_scan_attended_counts_once() {
        com.imin.iminapi.model.Order order = saveOrder("scan@x.com", 1500L);
        // Issue a ticket in REDEEMED state (simulate: ticket was redeemed)
        Ticket ticket = saveTicket(order, Ticket.STATE_REDEEMED);

        // First projection (after first scan)
        orderProjector.upsertMembership(orgId, "scan@x.com", "Scan");
        Consumer c = consumerRepo.findByNormalizedEmail("scan@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(m.getAttended()).isEqualTo(1);

        // Second projection (duplicate event for same scan)
        orderProjector.upsertMembership(orgId, "scan@x.com", "Scan");
        Membership m2 = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        // attended still 1 — derived from source tickets, not incremented
        assertThat(m2.getAttended()).isEqualTo(1);
    }

    @Test
    void idempotency_no_show_counted_correctly() {
        com.imin.iminapi.model.Order order = saveOrder("noshow@x.com", 1200L);
        saveTicket(order, Ticket.STATE_ISSUED); // issued but never redeemed

        orderProjector.upsertMembership(orgId, "noshow@x.com", "NoShow");
        Consumer c = consumerRepo.findByNormalizedEmail("noshow@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(m.getAttended()).isEqualTo(0);
        assertThat(m.getNoShow()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REPLAY == BACKFILL: identical membership rows
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void replay_equals_backfill_identical_membership_rows() {
        // Seed source data: 2 orders, 1 redeemed ticket
        com.imin.iminapi.model.Order o1 = saveOrder("replay@x.com", 1000L);
        com.imin.iminapi.model.Order o2 = saveOrder("replay@x.com", 2000L);
        saveTicket(o1, Ticket.STATE_REDEEMED);
        saveTicket(o2, Ticket.STATE_ISSUED);

        // Live ingestion path: two calls to upsertMembership (as if two TicketsIssuedEvents)
        orderProjector.upsertMembership(orgId, "replay@x.com", "Replay");
        orderProjector.upsertMembership(orgId, "replay@x.com", "Replay");

        Consumer c = consumerRepo.findByNormalizedEmail("replay@x.com").orElseThrow();
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

        // Now wipe ONLY the audience projection (leave source data intact)
        wipeMemberships();

        // Backfill path: runs through the same upsertMembership
        backfillJob.run();

        Consumer c2 = consumerRepo.findByNormalizedEmail("replay@x.com").orElseThrow();
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
        orderProjector.upsertMembership(orgId, EmailNormalizer.normalize("Ann@X.com "), "Ann");
        orderProjector.upsertMembership(orgId, EmailNormalizer.normalize("ann@x.com"), "ann");
        orderProjector.upsertMembership(orgId, EmailNormalizer.normalize(" ANN@x.com"), "ANN");

        Optional<Consumer> consumer = consumerRepo.findByNormalizedEmail("ann@x.com");
        assertThat(consumer).isPresent();
        // Exactly one consumer in the platform
        // (We can verify by checking only one membership exists for this org)
        assertThat(membershipRepo.countByOrgId(orgId)).isEqualTo(1);
    }

    @Test
    void identity_same_consumer_two_orgs_two_memberships_one_consumer() {
        // Second org
        Organization orgB = new Organization();
        orgB.setName("Org B");
        orgB.setSlug("org-b-" + UUID.randomUUID().toString().substring(0, 6));
        orgB.setContactEmail("b@test.com");
        orgB.setCountry("DE");
        orgB = orgRepo.save(orgB);
        UUID orgBId = orgB.getId();

        String email = "cross@orgs.com";
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
        saveOrder("organic@x.com", 800L);
        orderProjector.upsertMembership(orgId, "organic@x.com", "O");
        Consumer c = consumerRepo.findByNormalizedEmail("organic@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(m.getFirstTouchSrc()).isEqualTo("organic");
    }

    @Test
    void ingestion_no_consent_row_written_gate_ships_closed() {
        saveOrder("noconsent@x.com", 500L);
        orderProjector.upsertMembership(orgId, "noconsent@x.com", "NC");
        Consumer c = consumerRepo.findByNormalizedEmail("noconsent@x.com").orElseThrow();
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
        // One recent order of 25000 minor → R=high (recent), F=1, M=4
        com.imin.iminapi.model.Order o = saveOrder("rfm@x.com", 25_000L);
        orderProjector.upsertMembership(orgId, "rfm@x.com", "RFM");
        Consumer c = consumerRepo.findByNormalizedEmail("rfm@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();

        // Orders were just created → recency very small → R should be 5
        assertThat(m.getRfmR()).isEqualTo((short) 5);
        assertThat(m.getRfmF()).isEqualTo((short) 1);   // 1 order
        assertThat(m.getRfmM()).isEqualTo((short) 4);   // 20000–49999
    }

    @Test
    void ingestion_lifecycle_firsttime_after_one_event() {
        saveOrder("ft@x.com", 1000L);
        orderProjector.upsertMembership(orgId, "ft@x.com", "FT");
        Consumer c = consumerRepo.findByNormalizedEmail("ft@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        assertThat(m.getLifecycle()).isEqualTo(LifecycleClassifier.FIRSTTIME);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Create a membership stub for pure classifier testing (no DB). */
    private Membership membershipWithCounts(int events, long spendMinor, Integer recencyDays) {
        Membership m = new Membership();
        m.setOrgId(UUID.randomUUID());
        m.setConsumerId(UUID.randomUUID());
        m.setEvents(events);
        m.setSpendMinor(spendMinor);
        m.setRecencyDays(recencyDays);
        return m;
    }

    private com.imin.iminapi.model.Order saveOrder(String email, long totalMinor) {
        com.imin.iminapi.model.Order o = new com.imin.iminapi.model.Order();
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setEmail(email);
        o.setTotalMinor(totalMinor);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setToken(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setStripePaymentIntentId("pi_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        return orderRepo.save(o);
    }

    private Ticket saveTicket(com.imin.iminapi.model.Order order, String state) {
        Ticket t = new Ticket();
        t.setOrderId(order.getId());
        t.setEventId(order.getEventId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor((int) order.getTotalMinor());
        t.setState(state);
        t.setToken(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        if (Ticket.STATE_REDEEMED.equals(state)) {
            t.setRedeemedAt(Instant.now());
        }
        return ticketRepo.save(t);
    }

    private void wipeMemberships() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from consent_records");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
        } catch (Exception e) {
            throw new RuntimeException("wipeMemberships() failed: " + e.getMessage(), e);
        }
    }

    private void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from suppression_entries");
            s.execute("delete from consent_records");
            s.execute("delete from segments");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
            s.execute("delete from tickets");
            s.execute("delete from orders");
            s.execute("delete from events");
            s.execute("delete from users");
            s.execute("delete from organizations");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}
