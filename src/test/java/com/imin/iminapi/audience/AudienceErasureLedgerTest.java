package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceBackfillJob;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The erasure ledger (V99) — an Art.17 erasure must survive the nightly replay.
 *
 * <p>{@code AudienceErasureJob} runs at 02:00 and {@code AudienceBackfillJob} at
 * 03:00 (and on every application start), walking
 * {@code findDistinctOrgAndEmailPairs()} over ALL orders. Orders are retained
 * under the invoicing exemption, so before V99 the backfill rebuilt the erased
 * person's Consumer + Membership an hour after they were erased.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceErasureLedgerTest {

    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ErasedAddressRepository erasedAddressRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired AudienceBackfillJob backfillJob;
    @Autowired DsarService dsarService;
    @Autowired DataSource dataSource;

    @MockitoBean AuditLogger auditLogger;

    private UUID orgId;
    private UUID orgBId;
    private UUID eventId;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        wipe();
        Organization o = org("LedgerOrgA");
        orgId = o.getId();
        orgBId = org("LedgerOrgB").getId();
        User u = new User();
        u.setOrgId(orgId);
        String userEmail = "ledger-" + UUID.randomUUID() + "@x.com";
        u.setEmail(userEmail);
        u.setEmailLower(userEmail);
        u.setRole(UserRole.OWNER);
        u = userRepo.save(u);
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("Ledger Event");
        e.setSlug("ledger-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        e.setCreatedBy(u.getId());
        e.setCurrency("EUR");
        eventId = eventRepo.save(e).getId();
        principal = new AuthPrincipal(u.getId(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    @Test
    void backfill_does_not_resurrect_an_erased_member() {
        String email = "erased-ledger@x.com";
        saveOrder(orgId, email, 2500);
        orderProjector.upsertMembership(orgId, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();

        dsarService.executeErase(orgId, m.getMembershipId(), principal);
        assertThat(consumerRepo.findByNormalizedEmail(email)).isEmpty();

        // The order still exists — it is retained under the invoicing exemption —
        // so the backfill will see this (org, email) pair.
        assertThat(orderRepo.findDistinctOrgAndEmailPairs()).isNotEmpty();

        runBackfill();

        assertThat(consumerRepo.findByNormalizedEmail(email))
                .as("erased consumer must not be rebuilt from retained orders")
                .isEmpty();
        assertThat(membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()))
                .as("erased membership must not be rebuilt from retained orders")
                .isEmpty();
    }

    @Test
    void execute_erase_writes_one_ledger_entry_scoped_to_the_erasing_org() {
        String email = "ledger-entry@x.com";
        saveOrder(orgId, email, 1000);
        orderProjector.upsertMembership(orgId, email, email);
        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();

        dsarService.executeErase(orgId, m.getMembershipId(), principal);

        assertThat(erasedAddressRepo.existsForOrg(orgId, email)).isTrue();
        assertThat(erasedAddressRepo.existsForOrg(orgBId, email))
                .as("a DSAR is org-scoped — another org's record is that org's data")
                .isFalse();
        assertThat(erasedAddressRepo.existsPlatformWide(email)).isFalse();
    }

    @Test
    void backfill_still_rebuilds_another_orgs_membership_for_the_same_address() {
        String email = "shared-ledger@x.com";
        saveOrder(orgId, email, 1500);
        saveOrder(orgBId, email, 1500);
        orderProjector.upsertMembership(orgId, email, email);
        orderProjector.upsertMembership(orgBId, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership mA = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        dsarService.executeErase(orgId, mA.getMembershipId(), principal);

        wipeProjection();
        runBackfill();

        Consumer rebuilt = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        assertThat(membershipRepo.findByOrgIdAndConsumerId(orgBId, rebuilt.getConsumerId()))
                .as("orgB never erased this person")
                .isPresent();
        assertThat(membershipRepo.findByOrgIdAndConsumerId(orgId, rebuilt.getConsumerId()))
                .as("orgA erased them")
                .isEmpty();
    }

    @Test
    void a_platform_wide_ledger_entry_blocks_every_org() {
        String email = "platform-erased@x.com";
        saveOrder(orgId, email, 900);
        saveOrder(orgBId, email, 900);

        dsarService.recordErasure(null, email);

        runBackfill();

        assertThat(consumerRepo.findByNormalizedEmail(email)).isEmpty();
    }

    @Test
    void record_erasure_is_idempotent() {
        dsarService.recordErasure(orgId, "idem-ledger@x.com");
        dsarService.recordErasure(orgId, "idem-ledger@x.com");
        assertThat(erasedAddressRepo.findAllEntries()).hasSize(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Organization org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail(name + "@test.com");
        o.setCountry("DE");
        return orgRepo.save(o);
    }

    private com.imin.iminapi.model.Order saveOrder(UUID org, String email, long totalMinor) {
        com.imin.iminapi.model.Order o = new com.imin.iminapi.model.Order();
        o.setEventId(eventId);
        o.setOrgId(org);
        o.setEmail(email);
        o.setTotalMinor(totalMinor);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setToken(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setStripePaymentIntentId("pi_t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        return orderRepo.save(o);
    }

    /**
     * {@code AudienceBackfillJob.run()} carries {@code @SchedulerLock}, and
     * ShedLock's default intercept mode proxies the bean method itself — so a
     * direct call from a test is silently skipped (no log, no work) while the
     * lock from the previous run is held, and {@code lockAtLeastFor = PT1M}
     * guarantees it is, since the ApplicationReadyEvent run takes it seconds
     * before the first test. A test that does not release it asserts nothing.
     *
     * <p>Released by expiring the row, not deleting it: ShedLock's
     * {@code StorageBasedLockProvider} remembers that the row exists and only
     * ever issues {@code UPDATE ... WHERE lock_until <= now()} afterwards, so a
     * deleted row makes every later acquisition fail instead of succeed.
     */
    private void runBackfill() {
        exec("update shedlock set lock_until = locked_at");
        backfillJob.run();
    }

    private void wipeProjection() {
        exec("delete from consent_records", "delete from memberships", "delete from consumers");
    }

    private void wipe() {
        exec("delete from suppression_entries", "delete from consent_records", "delete from segments",
                "delete from memberships", "delete from consumers", "delete from erased_addresses",
                "delete from tickets", "delete from orders", "delete from notify_subscriptions",
                "delete from events", "delete from users", "delete from organizations",
                // Hand the backfill lock back: this class runs the job repeatedly and each
                // run re-takes it for a minute (lockAtLeastFor), which would otherwise make
                // every later test class calling run() a silent no-op in the same JVM.
                "update shedlock set lock_until = locked_at");
    }

    private void exec(String... statements) {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            for (String sql : statements) s.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("sql failed: " + e.getMessage(), e);
        }
    }
}
