package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.*;
import com.imin.iminapi.audience.repository.*;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DSAR (Data Subject Access Request) tests:
 * - Art.15 access returns membership
 * - Art.15 export returns membership
 * - Art.16 rectify updates mutable fields
 * - Art.21 object is synchronous (immediately unsubscribed + gate blocked)
 * - Art.17 erase schedules 30-day grace → erase_pending
 * - Art.17 execute: cascade (membership + consent + marketing-supp deleted), tombstone written
 * - Art.17 execute: shared consumer survives when another org still references it
 * - Art.17 execute: consumer deleted when last membership erased
 * - Cross-org DSAR → 404 (not 403)
 * - Audit: correct AuditActions constants
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceDsarTest {

    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired SuppressionRepository suppressionRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired DsarService dsarService;
    @Autowired ConsentService consentService;
    @Autowired SendGateService sendGateService;
    @Autowired DataSource dataSource;

    @MockitoBean AuditLogger auditLogger;

    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal principalA;

    @BeforeEach
    void setUp() {
        wipe();
        orgA = org("DsarOrgA").getId();
        orgB = org("DsarOrgB").getId();
        principalA = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.15: access
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void access_returns_membership_and_audits() {
        UUID mid = seedMembership(orgA, "access@d.com");

        Membership m = dsarService.access(orgA, mid, principalA);
        assertThat(m.getMembershipId()).isEqualTo(mid);

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditLogger, atLeastOnce()).record(any(), action.capture(), any(), any(), any());
        assertThat(action.getAllValues()).contains(AuditActions.DSAR_ACCESS);
    }

    @Test
    void access_cross_org_returns_404() {
        UUID mid = seedMembership(orgB, "accessb@d.com");
        assertThatThrownBy(() -> dsarService.access(orgA, mid, principalA))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.15: export
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void export_returns_membership_and_audits() {
        UUID mid = seedMembership(orgA, "export@d.com");

        Membership m = dsarService.export(orgA, mid, principalA);
        assertThat(m.getMembershipId()).isEqualTo(mid);

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditLogger, atLeastOnce()).record(any(), action.capture(), any(), any(), any());
        assertThat(action.getAllValues()).contains(AuditActions.DSAR_EXPORT);
    }

    @Test
    void export_cross_org_returns_404() {
        UUID mid = seedMembership(orgB, "exportb@d.com");
        assertThatThrownBy(() -> dsarService.export(orgA, mid, principalA))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.16: rectify
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void rectify_updates_display_name_city_notes() {
        UUID mid = seedMembership(orgA, "rectify@d.com");

        dsarService.rectify(orgA, mid, "New Name", "Berlin", "corrected notes", principalA);

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getDisplayName()).isEqualTo("New Name");
        assertThat(m.getCity()).isEqualTo("Berlin");
        assertThat(m.getNotes()).isEqualTo("corrected notes");
    }

    @Test
    void rectify_partial_update_only_sets_non_null_fields() {
        UUID mid = seedMembership(orgA, "rectify2@d.com");
        Membership before = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        String originalCity = before.getCity();

        // Only update displayName — city should remain unchanged
        dsarService.rectify(orgA, mid, "Only Name", null, null, principalA);

        Membership after = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(after.getDisplayName()).isEqualTo("Only Name");
        assertThat(after.getCity()).isEqualTo(originalCity);
    }

    @Test
    void rectify_audits_with_correct_action() {
        UUID mid = seedMembership(orgA, "recta@d.com");
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);

        dsarService.rectify(orgA, mid, "X", null, null, principalA);

        verify(auditLogger, atLeastOnce()).record(any(), action.capture(), any(), any(), any());
        assertThat(action.getAllValues()).contains(AuditActions.DSAR_RECTIFY);
    }

    @Test
    void rectify_cross_org_returns_404() {
        UUID mid = seedMembership(orgB, "rectifyb@d.com");
        assertThatThrownBy(() -> dsarService.rectify(orgA, mid, "X", null, null, principalA))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.21: object (synchronous unsubscribe)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void object_unsubscribes_synchronously_and_blocks_gate() {
        UUID mid = seedSubscribed(orgA, "object@d.com", "explicit");

        dsarService.object(orgA, mid, principalA);

        // Immediately reflected in DB
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("unsubscribed");

        // Gate immediately blocks
        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).extracting("reason").containsExactly("marketing_unsubscribed");
    }

    @Test
    void object_appends_consent_record() {
        UUID mid = seedSubscribed(orgA, "objrec@d.com", "explicit");
        long before = consentRepo.findByMembershipId(mid).size();

        dsarService.object(orgA, mid, principalA);

        List<ConsentRecord> records = consentRepo.findByMembershipId(mid);
        assertThat(records.size()).isGreaterThan((int) before);
        // Latest record should be unsubscribed
        ConsentRecord last = records.get(records.size() - 1);
        assertThat(last.getStatus()).isEqualTo("unsubscribed");
    }

    @Test
    void object_audits_with_correct_action() {
        UUID mid = seedSubscribed(orgA, "objaudit@d.com", "explicit");
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);

        dsarService.object(orgA, mid, principalA);

        verify(auditLogger, atLeastOnce()).record(any(), action.capture(), any(), any(), any());
        assertThat(action.getAllValues()).contains(AuditActions.DSAR_OBJECT);
    }

    @Test
    void object_cross_org_returns_404() {
        UUID mid = seedMembership(orgB, "objb@d.com");
        assertThatThrownBy(() -> dsarService.object(orgA, mid, principalA))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.17: requestErase — schedules 30-day grace
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void request_erase_sets_status_erase_pending_and_erase_at() {
        UUID mid = seedMembership(orgA, "erasereq@d.com");

        dsarService.requestErase(orgA, mid, principalA);

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getStatus()).isEqualTo("erase_pending");
        assertThat(m.getEraseAt()).isNotNull();
        // eraseAt should be approximately 30 days from now
        assertThat(m.getEraseAt()).isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
        assertThat(m.getEraseAt()).isBefore(Instant.now().plus(31, ChronoUnit.DAYS));
    }

    @Test
    void request_erase_audits_with_correct_action() {
        UUID mid = seedMembership(orgA, "eraseaudit@d.com");
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);

        dsarService.requestErase(orgA, mid, principalA);

        verify(auditLogger, atLeastOnce()).record(any(), action.capture(), any(), any(), any());
        assertThat(action.getAllValues()).contains(AuditActions.DSAR_ERASE_REQUESTED);
    }

    @Test
    void request_erase_cross_org_returns_404() {
        UUID mid = seedMembership(orgB, "eraseb@d.com");
        assertThatThrownBy(() -> dsarService.requestErase(orgA, mid, principalA))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Art.17: executeErase — destructive cascade
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void execute_erase_deletes_membership_and_writes_tombstone() {
        UUID mid = seedSubscribed(orgA, "execerase@d.com", "explicit");

        // Force eraseAt to the past so job would pick it up
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(m);

        dsarService.executeErase(orgA, mid, principalA);

        // Membership gone
        assertThat(membershipRepo.findByIdAndOrgId(mid, orgA)).isEmpty();

        // Tombstone written (DSAR_ERASE_EXECUTED audit call)
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditLogger, atLeastOnce()).record(any(), action.capture(), any(), any(), any());
        assertThat(action.getAllValues()).contains(AuditActions.DSAR_ERASE_EXECUTED);
    }

    @Test
    void execute_erase_cascades_marketing_suppression() {
        UUID mid = seedSubscribed(orgA, "cascsup@d.com", "explicit");

        // Add a marketing suppression
        SuppressionEntry se = new SuppressionEntry();
        se.setScope(SuppressionEntry.SCOPE_MARKETING);
        se.setOrgId(orgA);
        se.setMembershipId(mid);
        se.setReason(SuppressionEntry.REASON_MANUAL);
        suppressionRepo.save(se);

        // Verify it's there
        assertThat(suppressionRepo.findMarketingByOrgAndMembership(orgA, mid)).isPresent();

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(m);

        dsarService.executeErase(orgA, mid, principalA);

        // Suppression gone
        assertThat(suppressionRepo.findMarketingByOrgAndMembership(orgA, mid)).isEmpty();
    }

    @Test
    void execute_erase_shared_consumer_survives_when_other_org_references_it() {
        String email = "sharedconsumer@d.com";
        orderProjector.upsertMembership(orgA, email, email);
        orderProjector.upsertMembership(orgB, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership mA = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        mA.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(mA);

        dsarService.executeErase(orgA, mA.getMembershipId(), principalA);

        // orgA's membership gone
        assertThat(membershipRepo.findByIdAndOrgId(mA.getMembershipId(), orgA)).isEmpty();

        // Consumer still exists (orgB still references it)
        assertThat(consumerRepo.findByNormalizedEmail(email)).isPresent();

        // orgB's membership untouched
        assertThat(membershipRepo.findByOrgIdAndConsumerId(orgB, c.getConsumerId())).isPresent();
    }

    @Test
    void execute_erase_consumer_deleted_when_last_membership_erased() {
        String email = "lastconsumer@d.com";
        orderProjector.upsertMembership(orgA, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(m);

        dsarService.executeErase(orgA, m.getMembershipId(), principalA);

        // Consumer should be gone (no memberships remain)
        assertThat(consumerRepo.findByNormalizedEmail(email)).isEmpty();
    }

    @Test
    void execute_erase_idempotent_already_erased_is_noop() {
        UUID mid = seedMembership(orgA, "idemerase@d.com");

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(m);

        dsarService.executeErase(orgA, mid, principalA);
        // Second call should be a no-op, not throw
        assertThatCode(() -> dsarService.executeErase(orgA, mid, principalA))
                .doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Erasure job candidate selection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void erasure_job_only_processes_past_due_erase_pending() {
        // One that is due
        UUID due = seedMembership(orgA, "due@d.com");
        Membership mDue = membershipRepo.findByIdAndOrgId(due, orgA).orElseThrow();
        mDue.setStatus("erase_pending");
        mDue.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(mDue);

        // One that is not yet due
        UUID notDue = seedMembership(orgA, "notdue@d.com");
        Membership mNotDue = membershipRepo.findByIdAndOrgId(notDue, orgA).orElseThrow();
        mNotDue.setStatus("erase_pending");
        mNotDue.setEraseAt(Instant.now().plus(29, ChronoUnit.DAYS));
        membershipRepo.save(mNotDue);

        // One that is active (no erase_pending)
        UUID active = seedMembership(orgA, "active@d.com");

        List<Membership> due_list = membershipRepo.findErasureDue(Instant.now());
        assertThat(due_list).extracting(Membership::getMembershipId).containsExactly(due);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private UUID seedMembership(UUID orgId, String email) {
        String normalized = EmailNormalizer.normalize(email);
        Consumer consumer = consumerRepo.findByNormalizedEmail(normalized).orElse(null);
        if (consumer == null) {
            consumer = new Consumer();
            consumer.setNormalizedEmail(normalized);
            consumer.setDisplayName(email);
            consumer = consumerRepo.save(consumer);
        }
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        return membershipRepo.save(m).getMembershipId();
    }

    private UUID seedSubscribed(UUID orgId, String email, String basis) {
        UUID mid = seedMembership(orgId, email);
        AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
        consentService.capture(orgId, mid, basis, "seed", "proof", p);
        return mid;
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
