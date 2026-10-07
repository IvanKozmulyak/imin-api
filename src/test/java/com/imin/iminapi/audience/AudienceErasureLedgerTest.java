package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceBackfillJob;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
@IminIntegrationTest
class AudienceErasureLedgerTest {

    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ErasedAddressRepository erasedAddressRepo;
    @Autowired IminFixtures fx;
    @Autowired EventRepository eventRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired AudienceBackfillJob backfillJob;
    @Autowired DsarService dsarService;
    @Autowired JdbcTemplate jdbc;

    private UUID orgId;
    private UUID orgBId;
    private UUID eventId;
    private AuthPrincipal principal;
    private final List<String> emails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Organization o = fx.org();
        orgId = o.getId();
        orgBId = fx.org().getId();
        User u = fx.owner(o);
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

    /** The backfill walks every org's orders, so own orders, projection and ledger rows go. */
    @AfterEach
    void tearDown() {
        try {
            jdbc.update("delete from memberships where org_id in (?, ?)", orgId, orgBId);
            for (String email : emails) {
                jdbc.update("delete from consumers where normalized_email = ?", email);
                jdbc.update("delete from erased_addresses where email_normalized = ?", email);
            }
            releaseBackfillLock();
        } finally {
            OrgRows.delete(jdbc, List.of(orgId, orgBId));
        }
    }

    /** Lowercase and unique: consumers and the platform-wide ledger are keyed by address. */
    private String address(String tag) {
        String email = fx.email(tag);
        emails.add(email);
        return email;
    }

    @Test
    void backfill_does_not_resurrect_an_erased_member() {
        String email = address("erased-ledger");
        saveOrder(orgId, email, 2500);
        orderProjector.upsertMembership(orgId, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();

        dsarService.executeErase(orgId, m.getMembershipId(), principal);
        assertThat(consumerRepo.findByNormalizedEmail(email)).isEmpty();

        // The order still exists — it is retained under the invoicing exemption —
        // so the backfill will see this (org, email) pair.
        assertThat(orderRepo.findDistinctOrgAndEmailPairs())
                .anySatisfy(pair -> {
                    assertThat(pair[0]).isEqualTo(orgId);
                    assertThat(pair[1]).isEqualTo(email);
                });

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
        String email = address("ledger-entry");
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
        String email = address("shared-ledger");
        saveOrder(orgId, email, 1500);
        saveOrder(orgBId, email, 1500);
        orderProjector.upsertMembership(orgId, email, email);
        orderProjector.upsertMembership(orgBId, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership mA = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        dsarService.executeErase(orgId, mA.getMembershipId(), principal);

        wipeProjection(email);
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
        String email = address("platform-erased");
        saveOrder(orgId, email, 900);
        saveOrder(orgBId, email, 900);

        dsarService.recordErasure(null, email);

        runBackfill();

        assertThat(consumerRepo.findByNormalizedEmail(email)).isEmpty();
    }

    @Test
    void record_erasure_is_idempotent() {
        String email = address("idem-ledger");
        dsarService.recordErasure(orgId, email);
        dsarService.recordErasure(orgId, email);
        assertThat(erasedAddressRepo.findAllEntries().stream()
                .filter(e -> orgId.equals(e.getOrgId()) && email.equals(e.getEmailNormalized())))
                .hasSize(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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
        releaseBackfillLock();
        backfillJob.run();
    }

    // Scoped to this job's lock: other jobs' ShedLock rows belong to other tests.
    private void releaseBackfillLock() {
        jdbc.update("update shedlock set lock_until = locked_at where name = 'audience_backfill'");
    }

    /** Own orgs' memberships and this address's consumer only; consent records cascade. */
    private void wipeProjection(String email) {
        jdbc.update("delete from memberships where org_id in (?, ?)", orgId, orgBId);
        jdbc.update("delete from consumers where normalized_email = ?", email);
    }
}
