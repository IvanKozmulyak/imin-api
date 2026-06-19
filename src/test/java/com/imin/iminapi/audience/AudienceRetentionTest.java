package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.*;
import com.imin.iminapi.audience.repository.*;
import com.imin.iminapi.audience.service.*;
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
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Retention and Erasure tests:
 * - findErasureDue only returns past-due erase_pending memberships
 * - executeErase cascades correctly (membership + consent + marketing-supp)
 * - consumer deleted when last membership erased; survives when shared
 * - erasure idempotent (already-erased membership is noop)
 * - multi-org candidate selection
 *
 * NOTE: Tests that need the erasure cascade call dsarService.executeErase() directly,
 * not erasureJob.run(), to avoid ShedLock lockAtLeastFor blocking repeated job calls
 * within the same test suite JVM run. One dedicated test proves findErasureDue returns
 * the right candidates; the cascade/consumer tests prove what executeErase does when
 * the job calls it.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceRetentionTest {

    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired SuppressionRepository suppressionRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired DsarService dsarService;
    @Autowired DataSource dataSource;

    @MockitoBean AuditLogger auditLogger;

    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal system;

    @BeforeEach
    void setUp() {
        wipe();
        orgA = org("RetOrgA").getId();
        orgB = org("RetOrgB").getId();
        system = new AuthPrincipal(null, null, UserRole.MEMBER, null);
    }

    @AfterEach
    void tearDown() { wipe(); }

    // ─────────────────────────────────────────────────────────────────────────
    // findErasureDue candidate selection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void erasure_due_selects_only_past_due_erase_pending() {
        // Due: status=erase_pending, eraseAt in the past
        UUID due = seedMembership(orgA, "due@r.com");
        markErasePending(due, orgA, -1);

        // Not yet due: status=erase_pending, eraseAt in future
        UUID notDueYet = seedMembership(orgA, "notdueyet@r.com");
        markErasePending(notDueYet, orgA, +29 * 24 * 3600);

        // Active (normal, not erase_pending)
        UUID active = seedMembership(orgA, "active@r.com");

        List<Membership> candidates = membershipRepo.findErasureDue(Instant.now());

        assertThat(candidates).extracting(Membership::getMembershipId)
                .containsExactly(due)
                .doesNotContain(notDueYet, active);
    }

    @Test
    void erasure_due_includes_members_from_multiple_orgs() {
        UUID dueA = seedMembership(orgA, "dueA@r.com");
        markErasePending(dueA, orgA, -1);

        UUID dueB = seedMembership(orgB, "dueB@r.com");
        markErasePending(dueB, orgB, -1);

        UUID notDueA = seedMembership(orgA, "notdueA@r.com");

        List<Membership> candidates = membershipRepo.findErasureDue(Instant.now());

        assertThat(candidates).extracting(Membership::getMembershipId)
                .containsExactlyInAnyOrder(dueA, dueB);
    }

    @Test
    void erasure_due_empty_when_no_erase_pending() {
        seedMembership(orgA, "normal1@r.com");
        seedMembership(orgA, "normal2@r.com");

        List<Membership> candidates = membershipRepo.findErasureDue(Instant.now());
        assertThat(candidates).isEmpty();
    }

    @Test
    void erasure_due_boundary_past_by_100ms_is_due() {
        UUID mid = seedMembership(orgA, "boundary@r.com");
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        m.setStatus("erase_pending");
        m.setEraseAt(Instant.now().minus(100, ChronoUnit.MILLIS));
        membershipRepo.save(m);

        List<Membership> due = membershipRepo.findErasureDue(Instant.now());
        assertThat(due).extracting(Membership::getMembershipId).contains(mid);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // executeErase cascade (tested directly — avoids ShedLock re-acquisition issue)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void execute_erase_deletes_membership() {
        UUID mid = seedMembership(orgA, "execerase@r.com");
        dsarService.executeErase(orgA, mid, system);
        assertThat(countMembershipsById(mid)).isZero();
    }

    @Test
    void execute_erase_cascades_consent_records() {
        String email = "consent_cascade@r.com";
        String norm = EmailNormalizer.normalize(email);
        Consumer c = saveConsumer(norm, email);

        Membership m = new Membership();
        m.setOrgId(orgA);
        m.setConsumerId(c.getConsumerId());
        m.setStatus("erase_pending");
        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        m = membershipRepo.save(m);
        UUID mid = m.getMembershipId();

        ConsentRecord cr = new ConsentRecord();
        cr.setMembershipId(mid);
        cr.setChannel("email");
        cr.setStatus("subscribed");
        cr.setLawfulBasis("explicit");
        cr.setSource("test");
        consentRepo.save(cr);

        assertThat(consentRepo.findByMembershipId(mid)).hasSize(1);

        dsarService.executeErase(orgA, mid, system);

        assertThat(countMembershipsById(mid)).isZero();
        assertThat(consentRepo.findByMembershipId(mid)).isEmpty();
    }

    @Test
    void execute_erase_consumer_deleted_when_last_membership_erased() {
        String email = "lastref@r.com";
        String norm = EmailNormalizer.normalize(email);
        Consumer c = saveConsumer(norm, email);

        Membership m = new Membership();
        m.setOrgId(orgA);
        m.setConsumerId(c.getConsumerId());
        m.setStatus("erase_pending");
        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        m = membershipRepo.save(m);

        dsarService.executeErase(orgA, m.getMembershipId(), system);

        assertThat(countConsumersByEmail(norm)).isZero();
    }

    @Test
    void execute_erase_consumer_survives_when_shared() {
        String email = "sharedret@r.com";
        String norm = EmailNormalizer.normalize(email);
        Consumer c = saveConsumer(norm, email);

        Membership mA = new Membership();
        mA.setOrgId(orgA);
        mA.setConsumerId(c.getConsumerId());
        mA.setStatus("erase_pending");
        mA.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        mA = membershipRepo.save(mA);

        Membership mB = new Membership();
        mB.setOrgId(orgB);
        mB.setConsumerId(c.getConsumerId());
        mB = membershipRepo.save(mB);

        dsarService.executeErase(orgA, mA.getMembershipId(), system);

        assertThat(countMembershipsById(mA.getMembershipId())).isZero();
        assertThat(countConsumersByEmail(norm)).isEqualTo(1);
        assertThat(countMembershipsById(mB.getMembershipId())).isEqualTo(1);
    }

    @Test
    void execute_erase_idempotent_already_erased_is_noop() {
        UUID mid = seedMembership(orgA, "idem_erase@r.com");
        dsarService.executeErase(orgA, mid, system);
        assertThat(countMembershipsById(mid)).isZero();
        // Second call must not throw
        assertThatCode(() -> dsarService.executeErase(orgA, mid, system))
                .doesNotThrowAnyException();
    }

    @Test
    void execute_erase_cascades_marketing_suppression() {
        UUID mid = seedMembership(orgA, "supperase@r.com");

        SuppressionEntry se = new SuppressionEntry();
        se.setScope(SuppressionEntry.SCOPE_MARKETING);
        se.setOrgId(orgA);
        se.setMembershipId(mid);
        se.setReason(SuppressionEntry.REASON_MANUAL);
        suppressionRepo.save(se);

        assertThat(suppressionRepo.findMarketingByOrgAndMembership(orgA, mid)).isPresent();

        dsarService.executeErase(orgA, mid, system);

        assertThat(suppressionRepo.findMarketingByOrgAndMembership(orgA, mid)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private UUID seedMembership(UUID orgId, String email) {
        String normalized = EmailNormalizer.normalize(email);
        Consumer consumer = consumerRepo.findByNormalizedEmail(normalized).orElse(null);
        if (consumer == null) {
            consumer = saveConsumer(normalized, email);
        }
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        return membershipRepo.save(m).getMembershipId();
    }

    private Consumer saveConsumer(String normalizedEmail, String displayName) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(normalizedEmail);
        c.setDisplayName(displayName);
        return consumerRepo.save(c);
    }

    /** Set erase_pending + eraseAt via JPA. offsetSeconds<0 = past, >0 = future. */
    private void markErasePending(UUID membershipId, UUID orgId, long offsetSeconds) {
        Membership m = membershipRepo.findByIdAndOrgId(membershipId, orgId).orElseThrow();
        m.setStatus("erase_pending");
        m.setEraseAt(Instant.now().plusSeconds(offsetSeconds));
        membershipRepo.save(m);
    }

    /** Count memberships by id via JDBC — bypasses Hibernate first-level cache. */
    private int countMembershipsById(UUID membershipId) {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "select count(*) from memberships where membership_id = ?")) {
            ps.setObject(1, membershipId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException("countMembershipsById failed", e);
        }
    }

    /** Count consumers by normalized_email via JDBC — bypasses Hibernate first-level cache. */
    private int countConsumersByEmail(String normalizedEmail) {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "select count(*) from consumers where normalized_email = ?")) {
            ps.setString(1, normalizedEmail);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException("countConsumersByEmail failed", e);
        }
    }

    private Organization org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail(name + "@test.com");
        o.setCountry("DE");
        return orgRepo.save(o);
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
