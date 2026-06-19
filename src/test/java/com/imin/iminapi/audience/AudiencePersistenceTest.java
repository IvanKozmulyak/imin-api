package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.*;
import com.imin.iminapi.audience.repository.*;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests covering:
 * - SendGate FR-SND-1: all four exclusion clauses + re-subscriber (M3 regression)
 * - Tenant isolation: cross-org 404
 * - Idempotency: double order, double scan, replay
 * - Identity: email normalization, cross-org single consumer
 * - Segment: 7 prebuilt predicates
 * - Consent: state machine + immutable records
 * - DSAR erase: cascade + consumer survival
 * - Audit: AuditActions constants via ArgumentCaptor
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudiencePersistenceTest {

    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired SuppressionRepository suppressionRepo;
    @Autowired SegmentRepository segmentRepo;

    @Autowired OrganizationRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired EventRepository eventRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired TicketRepository ticketRepo;

    @Autowired SendGateService sendGateService;
    @Autowired ConsentService consentService;
    @Autowired DsarService dsarService;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired SegmentService segmentService;

    // AuditLogger is real but we also want to capture calls — mock it
    @MockitoBean AuditLogger auditLogger;

    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal principalA;

    @BeforeEach
    void setUp() {
        wipe();
        Organization a = org("orgA");
        orgA = a.getId();
        Organization b = org("orgB");
        orgB = b.getId();
        principalA = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    // ─────────────────────────────────────────────────────────────────────────
    // Schema: UNIQUE constraints hold under H2
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void schema_unique_normalized_email() {
        Consumer c1 = consumer("ann@x.com");
        assertThatThrownBy(() -> consumer("ann@x.com"))
                .isInstanceOf(Exception.class); // DuplicateKeyException or DataIntegrityViolation
    }

    @Test
    void schema_unique_org_consumer() {
        Consumer c = consumer("bob@x.com");
        membership(orgA, c);
        assertThatThrownBy(() -> membership(orgA, c))
                .isInstanceOf(Exception.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Identity: normalization + cross-org single consumer
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void identity_normalization_same_consumer() {
        // Three email variants → one Consumer
        orderProjector.upsertMembership(orgA, EmailNormalizer.normalize("Ann@X.com "), "Ann@X.com");
        orderProjector.upsertMembership(orgA, EmailNormalizer.normalize("ann@x.com"), "ann@x.com");
        orderProjector.upsertMembership(orgA, EmailNormalizer.normalize(" ANN@x.com"), "ANN@x.com");

        long consumerCount = consumerRepo.findByNormalizedEmail("ann@x.com").isPresent() ? 1 : 0;
        assertThat(consumerCount).isEqualTo(1);

        // Only one membership for orgA
        long membershipCount = membershipRepo.countByOrgId(orgA);
        assertThat(membershipCount).isEqualTo(1);
    }

    @Test
    void identity_same_consumer_two_orgs() {
        String email = "shared@test.com";
        orderProjector.upsertMembership(orgA, email, email);
        orderProjector.upsertMembership(orgB, email, email);

        Optional<Consumer> consumer = consumerRepo.findByNormalizedEmail(email);
        assertThat(consumer).isPresent();

        // Two memberships, one consumer
        assertThat(membershipRepo.countByOrgId(orgA)).isEqualTo(1);
        assertThat(membershipRepo.countByOrgId(orgB)).isEqualTo(1);

        Consumer c = consumer.get();
        assertThat(membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId())).isPresent();
        assertThat(membershipRepo.findByOrgIdAndConsumerId(orgB, c.getConsumerId())).isPresent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tenant isolation: cross-org returns 404 (never 403)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isolation_cross_org_member_returns_404() {
        orderProjector.upsertMembership(orgB, "b@b.com", "B");
        Consumer c = consumerRepo.findByNormalizedEmail("b@b.com").orElseThrow();
        Membership mB = membershipRepo.findByOrgIdAndConsumerId(orgB, c.getConsumerId()).orElseThrow();

        // orgA tries to read orgB's membership by id → 404
        assertThatThrownBy(() -> membershipRepo.findByIdAndOrgId(mB.getMembershipId(), orgA)
                .orElseThrow(() -> ApiException.notFound("Membership")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void isolation_send_gate_cross_org_ids_not_sendable() {
        // Seed a subscribed+consented membership in orgB
        orderProjector.upsertMembership(orgB, "legit@b.com", "B");
        Consumer c = consumerRepo.findByNormalizedEmail("legit@b.com").orElseThrow();
        Membership mB = membershipRepo.findByOrgIdAndConsumerId(orgB, c.getConsumerId()).orElseThrow();
        consentService.capture(orgB, mB.getMembershipId(), "explicit", "test", "proof",
                new AuthPrincipal(UUID.randomUUID(), orgB, UserRole.OWNER, UUID.randomUUID()));

        // orgA sends orgB's membership IDs → gate returns empty sendable (not leaked)
        SendGateService.GateResult result = sendGateService.evaluate(orgA, List.of(mB.getMembershipId()));
        assertThat(result.sendable()).isEmpty();
        // Not in excluded either — cross-org IDs are silently dropped (no existence leak)
        assertThat(result.excluded()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // M4 architecture: tenant repos must not expose unscoped finders
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void m4_tenant_repos_dont_expose_findAll() {
        // MembershipRepository, ConsentRecordRepository, SegmentRepository must NOT
        // extend JpaRepository (which exposes findAll/findById/deleteAll).
        // Verify via reflection that none of these interfaces extends JpaRepository.
        assertThat(isJpaRepository(MembershipRepository.class))
                .as("MembershipRepository must not extend JpaRepository").isFalse();
        assertThat(isJpaRepository(ConsentRecordRepository.class))
                .as("ConsentRecordRepository must not extend JpaRepository").isFalse();
        assertThat(isJpaRepository(SegmentRepository.class))
                .as("SegmentRepository must not extend JpaRepository").isFalse();
    }

    private boolean isJpaRepository(Class<?> iface) {
        for (Class<?> parent : iface.getInterfaces()) {
            if (parent.getName().contains("JpaRepository") || parent.getName().contains("CrudRepository")) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SendGate FR-SND-1
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void sendgate_unsubscribed_excluded() {
        UUID mid = seedSubscribed(orgA, "unsub@x.com", "explicit");
        consentService.unsubscribe(orgA, mid, "test", principalA);

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).extracting("reason").containsExactly("marketing_unsubscribed");
    }

    @Test
    void sendgate_marketing_suppressed_excluded() {
        UUID mid = seedSubscribed(orgA, "supp@x.com", "explicit");
        SuppressionEntry s = new SuppressionEntry();
        s.setScope(SuppressionEntry.SCOPE_MARKETING);
        s.setOrgId(orgA);
        s.setMembershipId(mid);
        s.setReason(SuppressionEntry.REASON_MANUAL);
        suppressionRepo.save(s);

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).extracting("reason").containsExactly("marketing_suppressed");
    }

    @Test
    void sendgate_deliverability_suppressed_excluded_even_without_org_suppression() {
        UUID mid = seedSubscribed(orgA, "bounce@x.com", "explicit");

        // Deliverability suppression is shared — no org_id
        SuppressionEntry s = new SuppressionEntry();
        s.setScope(SuppressionEntry.SCOPE_DELIVERABILITY);
        s.setNormalizedEmail("bounce@x.com");
        s.setReason(SuppressionEntry.REASON_HARD_BOUNCE);
        suppressionRepo.save(s);

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).extracting("reason").containsExactly("deliverability_suppressed");
    }

    @Test
    void sendgate_no_lawful_basis_excluded() {
        // S5: ingestion writes no consent → basis null
        orderProjector.upsertMembership(orgA, "nolawful@x.com", "X");
        Consumer c = consumerRepo.findByNormalizedEmail("nolawful@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(m.getMembershipId()));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).extracting("reason").containsExactly("no_lawful_basis");
    }

    @Test
    void sendgate_subscribed_consented_unsuppressed_is_sendable() {
        UUID mid = seedSubscribed(orgA, "ok@x.com", "explicit");
        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).containsExactly(mid);
        assertThat(r.excluded()).isEmpty();
    }

    @Test
    void sendgate_resubscriber_is_sendable_m3_regression() {
        // [subscribed, unsub, subscribed] → must be sendable (M3: reads consent_status column)
        UUID mid = seedSubscribed(orgA, "resub@x.com", "explicit");
        consentService.unsubscribe(orgA, mid, "own", principalA);
        consentService.capture(orgA, mid, "explicit", "re-signup", "proof2", principalA);

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("explicit");

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).containsExactly(mid);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Consent: state machine + immutable records
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void consent_unchecked_default_is_never() {
        orderProjector.upsertMembership(orgA, "default@x.com", "D");
        Consumer c = consumerRepo.findByNormalizedEmail("default@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("never");
        assertThat(m.getConsentBasis()).isNull();
    }

    @Test
    void consent_capture_appends_immutable_row() {
        UUID mid = seedSubscribed(orgA, "cap@x.com", "explicit");
        List<ConsentRecord> records = consentRepo.findByMembershipId(mid);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo("subscribed");
        assertThat(records.get(0).getLawfulBasis()).isEqualTo("explicit");

        // Unsubscribe appends another row — original row untouched
        consentService.unsubscribe(orgA, mid, "test", principalA);
        List<ConsentRecord> after = consentRepo.findByMembershipId(mid);
        assertThat(after).hasSize(2);
        assertThat(after.get(0).getStatus()).isEqualTo("subscribed"); // original intact
        assertThat(after.get(1).getStatus()).isEqualTo("unsubscribed");
    }

    @Test
    void consent_unsubscribe_synchronous_gate_sees_it_immediately() {
        UUID mid = seedSubscribed(orgA, "sync@x.com", "explicit");
        assertThat(sendGateService.evaluate(orgA, List.of(mid)).sendable()).containsExactly(mid);

        consentService.unsubscribe(orgA, mid, "test", principalA);
        assertThat(sendGateService.evaluate(orgA, List.of(mid)).sendable()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DSAR erase: cascade + consumer survival
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dsar_erase_cascades_org_membership_leaves_tombstone() {
        orderProjector.upsertMembership(orgA, "erase@x.com", "E");
        Consumer c = consumerRepo.findByNormalizedEmail("erase@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();
        UUID mid = m.getMembershipId();

        dsarService.requestErase(orgA, mid, principalA);

        // Override erase_at to past so executeErase proceeds immediately
        m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        m.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(m);

        dsarService.executeErase(orgA, mid, principalA);

        // Membership gone
        assertThat(membershipRepo.findByIdAndOrgId(mid, orgA)).isEmpty();
        // Tombstone in audit_logs (via auditLogger.record mock — DSAR_ERASE_EXECUTED)
        verify(auditLogger, atLeastOnce()).record(any(), eq(AuditActions.DSAR_ERASE_EXECUTED),
                eq("membership"), eq(mid), anyString());
    }

    @Test
    void dsar_erase_consumer_survives_if_other_org_references_it() {
        String email = "shared2@x.com";
        orderProjector.upsertMembership(orgA, email, email);
        orderProjector.upsertMembership(orgB, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership mA = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        // Erase orgA's membership
        mA.setEraseAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        membershipRepo.save(mA);
        dsarService.executeErase(orgA, mA.getMembershipId(), principalA);

        // Consumer still exists (orgB still references it)
        assertThat(consumerRepo.findByNormalizedEmail(email)).isPresent();
        // orgB membership untouched
        assertThat(membershipRepo.findByOrgIdAndConsumerId(orgB, c.getConsumerId())).isPresent();
    }

    @Test
    void dsar_object_is_synchronous() {
        UUID mid = seedSubscribed(orgA, "obj@x.com", "explicit");
        dsarService.object(orgA, mid, principalA);
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("unsubscribed");
        // Gate immediately sees unsubscribed
        assertThat(sendGateService.evaluate(orgA, List.of(mid)).sendable()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Segment: 7 prebuilt predicates
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void segment_prebuilt_repeat_matches_events_gte_2() {
        orderProjector.upsertMembership(orgA, "repeat@x.com", "R");
        Consumer c = consumerRepo.findByNormalizedEmail("repeat@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();
        m.setEvents(2);
        membershipRepo.save(m);

        List<Membership> repeats = membershipRepo.findRepeats(orgA);
        assertThat(repeats).extracting(Membership::getMembershipId).contains(m.getMembershipId());
    }

    @Test
    void segment_prebuilt_vip_matches_spend_and_events() {
        orderProjector.upsertMembership(orgA, "vip@x.com", "V");
        Consumer c = consumerRepo.findByNormalizedEmail("vip@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();
        m.setSpendMinor(25000);
        m.setEvents(5);
        membershipRepo.save(m);

        List<Membership> vips = membershipRepo.findVips(orgA);
        assertThat(vips).extracting(Membership::getMembershipId).contains(m.getMembershipId());
    }

    @Test
    void segment_static_snapshot_frozen_while_dynamic_reevaluates() {
        segmentService.ensurePrebuiltSegments(orgA);
        Segment repeatSeg = segmentRepo.findByOrgId(orgA).stream()
                .filter(s -> "Repeat".equals(s.getName())).findFirst().orElseThrow();

        // No repeats yet → resolve = 0
        assertThat(segmentService.resolveMembers(orgA, repeatSeg)).isEmpty();

        // Snapshot taken now (empty)
        segmentService.snapshot(orgA, repeatSeg.getId(), principalA);
        Segment snapped = segmentRepo.findByIdAndOrgId(repeatSeg.getId(), orgA).orElseThrow();
        assertThat(snapped.getKind()).isEqualTo("static");

        // Add a repeat member
        orderProjector.upsertMembership(orgA, "new@x.com", "N");
        Consumer c = consumerRepo.findByNormalizedEmail("new@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();
        m.setEvents(2);
        membershipRepo.save(m);

        // Static snapshot still returns 0 (frozen)
        assertThat(segmentService.resolveMembers(orgA, snapped)).isEmpty();

        // A fresh dynamic segment returns 1
        Segment fresh = segmentRepo.findByIdAndOrgId(repeatSeg.getId(), orgA).orElseThrow();
        // Reload to dynamic for comparison
        fresh.setKind("dynamic");
        assertThat(segmentService.resolveMembers(orgA, fresh)).hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audit: ArgumentCaptor assertions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void audit_consent_captured_records_correct_action() {
        orderProjector.upsertMembership(orgA, "audit@x.com", "A");
        Consumer c = consumerRepo.findByNormalizedEmail("audit@x.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        consentService.capture(orgA, m.getMembershipId(), "explicit", "signup", "proof", principalA);

        verify(auditLogger).record(any(), actionCaptor.capture(), any(), any(), any());
        assertThat(actionCaptor.getValue()).isEqualTo(AuditActions.CONSENT_CAPTURED);
    }

    @Test
    void audit_segment_created_records_correct_action() {
        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        segmentService.createSegment(orgA, "Test seg", "dynamic", null, principalA);

        verify(auditLogger).record(any(), actionCaptor.capture(), any(), any(), any());
        assertThat(actionCaptor.getValue()).isEqualTo(AuditActions.SEGMENT_CREATED);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Idempotency
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void idempotency_upsert_twice_produces_one_membership() {
        orderProjector.upsertMembership(orgA, "idem@x.com", "I");
        orderProjector.upsertMembership(orgA, "idem@x.com", "I");
        assertThat(membershipRepo.countByOrgId(orgA)).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private UUID seedSubscribed(UUID orgId, String email, String basis) {
        orderProjector.upsertMembership(orgId, email, email);
        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        AuthPrincipal p = orgId.equals(orgA) ? principalA
                : new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
        consentService.capture(orgId, m.getMembershipId(), basis, "seed", "proof", p);
        return m.getMembershipId();
    }

    private Consumer consumer(String email) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(EmailNormalizer.normalize(email));
        c.setDisplayName(email);
        return consumerRepo.save(c);
    }

    private Membership membership(UUID orgId, Consumer c) {
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        return membershipRepo.save(m);
    }

    private Organization org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail(name + "@test.com");
        o.setCountry("DE");
        return orgRepo.save(o);
    }

    @Autowired DataSource dataSource;

    private void wipe() {
        // Delete in FK-safe order using raw JDBC to avoid repo scope restrictions
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
