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
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Load-bearing trio tests: CONSENT, SUPPRESSION, SEND GATE.
 *
 * <p>Covers the full FR-SND-1 send-gate matrix:
 * <ul>
 *   <li>(a) marketing-unsubscribed excluded</li>
 *   <li>(b) marketing-suppressed (this org) excluded</li>
 *   <li>(c) deliverability-suppressed (shared email) excluded even though this org never suppressed</li>
 *   <li>(d) no-lawful-basis excluded</li>
 *   <li>(e) cross-org candidate IDs under org A → not sendable, not leaked</li>
 *   <li>(f) only subscribed+consented+unsuppressed is sendable</li>
 *   <li>(f2) re-subscriber [subscribed→unsub→subscribed] is SENDABLE (M3 regression)</li>
 * </ul>
 *
 * <p>Consent tests: append-only proofs; synchronous unsubscribe; prior rows untouched.
 *
 * <p>Suppression tests: marketing (org-scoped) vs deliverability (shared); two-scope independence.
 *
 * <p>Audit: ArgumentCaptor asserts the correct AuditActions constant per mutation.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceSendGateConsentSuppressionTest {

    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired SuppressionRepository suppressionRepo;
    @Autowired OrganizationRepository orgRepo;

    @Autowired SendGateService sendGateService;
    @Autowired ConsentService consentService;
    @Autowired SuppressionService suppressionService;
    @Autowired AudienceOrderProjector orderProjector;

    @MockitoBean AuditLogger auditLogger;

    private UUID orgA;
    private UUID orgB;
    private AuthPrincipal principalA;
    private AuthPrincipal principalB;

    @Autowired DataSource dataSource;

    @BeforeEach
    void setUp() {
        wipe();
        orgA = org("orgA").getId();
        orgB = org("orgB").getId();
        principalA = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
        principalB = new AuthPrincipal(UUID.randomUUID(), orgB, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    // ══════════════════════════════════════════════════════════════════════════
    // SEND GATE FR-SND-1 — full matrix
    // ══════════════════════════════════════════════════════════════════════════

    /** (a) marketing-unsubscribed → excluded with reason=marketing_unsubscribed */
    @Test
    void sendgate_a_unsubscribed_excluded() {
        UUID mid = seedSubscribed(orgA, "unsub@gate.com", "explicit", principalA);

        consentService.unsubscribe(orgA, mid, "test", principalA);

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).hasSize(1);
        assertThat(r.excluded().get(0).reason()).isEqualTo("marketing_unsubscribed");
        assertThat(r.excluded().get(0).membershipId()).isEqualTo(mid);
    }

    /** (b) marketing-suppressed (this org) → excluded with reason=marketing_suppressed */
    @Test
    void sendgate_b_marketing_suppressed_excluded() {
        UUID mid = seedSubscribed(orgA, "mktsupp@gate.com", "explicit", principalA);

        suppressionService.addMarketing(orgA, mid, SuppressionEntry.REASON_MANUAL, principalA);

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).hasSize(1);
        assertThat(r.excluded().get(0).reason()).isEqualTo("marketing_suppressed");
    }

    /**
     * (c) deliverability-suppressed (shared by email across all orgs) →
     *     excluded even though THIS org never suppressed it.
     */
    @Test
    void sendgate_c_deliverability_suppressed_excluded_even_without_org_suppression() {
        String email = "bounce@gate.com";
        UUID mid = seedSubscribed(orgA, email, "explicit", principalA);

        // System writes deliverability suppression — no org_id
        suppressionService.addDeliverability(email, SuppressionEntry.REASON_HARD_BOUNCE);

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).hasSize(1);
        assertThat(r.excluded().get(0).reason()).isEqualTo("deliverability_suppressed");
    }

    /** (d) no-lawful-basis (S5: ingestion writes no consent row) → excluded */
    @Test
    void sendgate_d_no_lawful_basis_excluded() {
        orderProjector.upsertMembership(orgA, "nolawful@gate.com", "NoLawful");
        Consumer c = consumerRepo.findByNormalizedEmail("nolawful@gate.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        assertThat(m.getConsentStatus()).isEqualTo("never");
        assertThat(m.getConsentBasis()).isNull();

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(m.getMembershipId()));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).hasSize(1);
        assertThat(r.excluded().get(0).reason()).isEqualTo("no_lawful_basis");
    }

    /**
     * (e) cross-org candidate IDs under org A → not sendable AND not leaked
     *     (they must not appear in excludedReasons either — no existence leak).
     */
    @Test
    void sendgate_e_cross_org_ids_silently_dropped_not_leaked() {
        // Seed a fully sendable membership in orgB
        UUID midB = seedSubscribed(orgB, "legit@orgb.com", "explicit", principalB);

        // orgA evaluates using orgB's membership ID
        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(midB));

        assertThat(r.sendable()).isEmpty();
        // Critically: cross-org IDs must NOT appear in excluded (no existence leak)
        assertThat(r.excluded()).isEmpty();
    }

    /** (f) only subscribed + consented + unsuppressed is sendable */
    @Test
    void sendgate_f_subscribed_consented_unsuppressed_sendable() {
        UUID mid = seedSubscribed(orgA, "ok@gate.com", "explicit", principalA);

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).containsExactly(mid);
        assertThat(r.excluded()).isEmpty();
    }

    /**
     * (f2) M3 regression: re-subscriber [subscribed→unsub→subscribed] is SENDABLE.
     * The gate reads the CURRENT denormalized consent_status column — not whether
     * any unsubscribe row exists in consent_records.
     */
    @Test
    void sendgate_f2_resubscriber_is_sendable_m3_regression() {
        UUID mid = seedSubscribed(orgA, "resub@gate.com", "explicit", principalA);

        // Unsubscribe
        consentService.unsubscribe(orgA, mid, "user-request", principalA);
        assertThat(sendGateService.evaluate(orgA, List.of(mid)).sendable()).isEmpty();

        // Re-subscribe
        consentService.capture(orgA, mid, "explicit", "re-signup-form", "proof-v2", principalA);

        // Current state: subscribed — must be sendable
        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("explicit");

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).containsExactly(mid);
        assertThat(r.excluded()).isEmpty();
    }

    /** Empty candidate list → empty result, no error */
    @Test
    void sendgate_empty_input_returns_empty_result() {
        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of());
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).isEmpty();
    }

    /** Mixed batch: one sendable, one unsubscribed, one no-basis */
    @Test
    void sendgate_mixed_batch_correct_partition() {
        UUID sendable = seedSubscribed(orgA, "s@gate.com", "explicit", principalA);
        UUID unsubbed = seedSubscribed(orgA, "u@gate.com", "explicit", principalA);
        consentService.unsubscribe(orgA, unsubbed, "test", principalA);

        orderProjector.upsertMembership(orgA, "nb@gate.com", "NB");
        Consumer nbC = consumerRepo.findByNormalizedEmail("nb@gate.com").orElseThrow();
        UUID noBasis = membershipRepo.findByOrgIdAndConsumerId(orgA, nbC.getConsumerId())
                .orElseThrow().getMembershipId();

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(sendable, unsubbed, noBasis));
        assertThat(r.sendable()).containsExactly(sendable);
        assertThat(r.excluded()).extracting("reason")
                .containsExactlyInAnyOrder("marketing_unsubscribed", "no_lawful_basis");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONSENT — append-only proofs, synchronous unsubscribe, prior rows intact
    // ══════════════════════════════════════════════════════════════════════════

    /** Unchecked default: ingested member has consent_status='never', basis=null */
    @Test
    void consent_unchecked_default_never() {
        orderProjector.upsertMembership(orgA, "default@consent.com", "D");
        Consumer c = consumerRepo.findByNormalizedEmail("default@consent.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        assertThat(m.getConsentStatus()).isEqualTo("never");
        assertThat(m.getConsentBasis()).isNull();
    }

    /** Capture appends immutable proof row and updates denormalized membership columns */
    @Test
    void consent_capture_appends_row_updates_membership() {
        orderProjector.upsertMembership(orgA, "cap@consent.com", "C");
        Consumer c = consumerRepo.findByNormalizedEmail("cap@consent.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        consentService.capture(orgA, m.getMembershipId(), "explicit", "signup-form",
                "checkbox checked at 2026-06-01", principalA);

        List<ConsentRecord> records = consentRepo.findByMembershipId(m.getMembershipId());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo("subscribed");
        assertThat(records.get(0).getLawfulBasis()).isEqualTo("explicit");
        assertThat(records.get(0).getSource()).isEqualTo("signup-form");
        assertThat(records.get(0).getProofText()).isEqualTo("checkbox checked at 2026-06-01");

        // Denormalized on membership
        Membership updated = membershipRepo.findByIdAndOrgId(m.getMembershipId(), orgA).orElseThrow();
        assertThat(updated.getConsentStatus()).isEqualTo("subscribed");
        assertThat(updated.getConsentBasis()).isEqualTo("explicit");
    }

    /** Prior consent rows are untouched when unsubscribing — append-only invariant */
    @Test
    void consent_unsubscribe_prior_rows_untouched() {
        UUID mid = seedSubscribed(orgA, "unsub@consent.com", "explicit", principalA);

        consentService.unsubscribe(orgA, mid, "user-unsubscribe-link", principalA);

        List<ConsentRecord> records = consentRepo.findByMembershipId(mid);
        assertThat(records).hasSize(2);
        // First row still 'subscribed'
        assertThat(records.get(0).getStatus()).isEqualTo("subscribed");
        assertThat(records.get(0).getLawfulBasis()).isEqualTo("explicit");
        // Second row is the unsubscribe
        assertThat(records.get(1).getStatus()).isEqualTo("unsubscribed");
        assertThat(records.get(1).getLawfulBasis()).isNull();
        assertThat(records.get(1).getSource()).isEqualTo("user-unsubscribe-link");
    }

    /** Unsubscribe is SYNCHRONOUS — gate sees the new state in the same logical transaction */
    @Test
    void consent_unsubscribe_synchronous_gate_sees_it_immediately() {
        UUID mid = seedSubscribed(orgA, "sync@consent.com", "explicit", principalA);
        assertThat(sendGateService.evaluate(orgA, List.of(mid)).sendable()).containsExactly(mid);

        consentService.unsubscribe(orgA, mid, "sync-test", principalA);

        // Gate immediately reflects the change
        assertThat(sendGateService.evaluate(orgA, List.of(mid)).sendable()).isEmpty();
        assertThat(sendGateService.evaluate(orgA, List.of(mid)).excluded())
                .extracting("reason").containsExactly("marketing_unsubscribed");
    }

    /** soft_opt_in basis is also valid lawful basis for send gate */
    @Test
    void consent_soft_opt_in_is_valid_lawful_basis() {
        orderProjector.upsertMembership(orgA, "soft@consent.com", "S");
        Consumer c = consumerRepo.findByNormalizedEmail("soft@consent.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        consentService.capture(orgA, m.getMembershipId(), "soft_opt_in", "purchase-flow",
                "implicit consent via purchase", principalA);

        Membership updated = membershipRepo.findByIdAndOrgId(m.getMembershipId(), orgA).orElseThrow();
        assertThat(updated.getConsentBasis()).isEqualTo("soft_opt_in");
        assertThat(updated.getConsentStatus()).isEqualTo("subscribed");

        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(m.getMembershipId()));
        assertThat(r.sendable()).containsExactly(m.getMembershipId());
    }

    /** Multiple capture calls each append a row; membership reflects the latest */
    @Test
    void consent_multiple_captures_all_append() {
        UUID mid = seedSubscribed(orgA, "multi@consent.com", "soft_opt_in", principalA);
        // Upgrade to explicit
        consentService.capture(orgA, mid, "explicit", "gdpr-double-opt-in", "email link clicked", principalA);

        List<ConsentRecord> records = consentRepo.findByMembershipId(mid);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getLawfulBasis()).isEqualTo("soft_opt_in");
        assertThat(records.get(1).getLawfulBasis()).isEqualTo("explicit");

        Membership m = membershipRepo.findByIdAndOrgId(mid, orgA).orElseThrow();
        assertThat(m.getConsentBasis()).isEqualTo("explicit"); // latest wins on denorm
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SUPPRESSION — two-scope independence
    // ══════════════════════════════════════════════════════════════════════════

    /** Marketing suppression added via SuppressionService is idempotent */
    @Test
    void suppression_marketing_add_idempotent() {
        UUID mid = seedSubscribed(orgA, "idem@supp.com", "explicit", principalA);

        SuppressionEntry s1 = suppressionService.addMarketing(orgA, mid, SuppressionEntry.REASON_MANUAL, principalA);
        SuppressionEntry s2 = suppressionService.addMarketing(orgA, mid, SuppressionEntry.REASON_MANUAL, principalA);

        assertThat(s1.getId()).isEqualTo(s2.getId());
        assertThat(suppressionRepo.findMarketingByOrg(orgA)).hasSize(1);
    }

    /** Marketing suppression is org-scoped — removing for orgA does not affect orgB */
    @Test
    void suppression_marketing_scoped_to_org() {
        String email = "scope@supp.com";
        orderProjector.upsertMembership(orgA, email, email);
        orderProjector.upsertMembership(orgB, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership mA = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();
        Membership mB = membershipRepo.findByOrgIdAndConsumerId(orgB, c.getConsumerId()).orElseThrow();

        consentService.capture(orgA, mA.getMembershipId(), "explicit", "t", null, principalA);
        consentService.capture(orgB, mB.getMembershipId(), "explicit", "t", null, principalB);

        // Suppress only in orgA
        suppressionService.addMarketing(orgA, mA.getMembershipId(), SuppressionEntry.REASON_MANUAL, principalA);

        // orgA: excluded
        assertThat(sendGateService.evaluate(orgA, List.of(mA.getMembershipId())).sendable()).isEmpty();
        // orgB: still sendable (different scope)
        assertThat(sendGateService.evaluate(orgB, List.of(mB.getMembershipId())).sendable())
                .containsExactly(mB.getMembershipId());
    }

    /** Deliverability suppression blocks across ALL orgs sharing the same email */
    @Test
    void suppression_deliverability_shared_blocks_all_orgs() {
        String email = "shared-bounce@supp.com";
        orderProjector.upsertMembership(orgA, email, email);
        orderProjector.upsertMembership(orgB, email, email);

        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership mA = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();
        Membership mB = membershipRepo.findByOrgIdAndConsumerId(orgB, c.getConsumerId()).orElseThrow();

        consentService.capture(orgA, mA.getMembershipId(), "explicit", "t", null, principalA);
        consentService.capture(orgB, mB.getMembershipId(), "explicit", "t", null, principalB);

        // One deliverability suppression keyed on email
        suppressionService.addDeliverability(email, SuppressionEntry.REASON_HARD_BOUNCE);

        // Both orgs blocked
        assertThat(sendGateService.evaluate(orgA, List.of(mA.getMembershipId())).sendable()).isEmpty();
        assertThat(sendGateService.evaluate(orgB, List.of(mB.getMembershipId())).sendable()).isEmpty();
        assertThat(sendGateService.evaluate(orgA, List.of(mA.getMembershipId())).excluded())
                .extracting("reason").containsExactly("deliverability_suppressed");
        assertThat(sendGateService.evaluate(orgB, List.of(mB.getMembershipId())).excluded())
                .extracting("reason").containsExactly("deliverability_suppressed");
    }

    /** Deliverability suppression is idempotent (same email twice → one entry) */
    @Test
    void suppression_deliverability_add_idempotent() {
        String email = "idembounce@supp.com";
        SuppressionEntry s1 = suppressionService.addDeliverability(email, SuppressionEntry.REASON_HARD_BOUNCE);
        SuppressionEntry s2 = suppressionService.addDeliverability(email, SuppressionEntry.REASON_HARD_BOUNCE);

        assertThat(s1.getId()).isEqualTo(s2.getId());
        assertThat(suppressionRepo.findDeliverabilityByEmail(email)).isPresent();
    }

    /** Both suppressions independent: marketing suppression does not affect deliverability state */
    @Test
    void suppression_two_scopes_are_independent() {
        String email = "dual@supp.com";
        UUID mid = seedSubscribed(orgA, email, "explicit", principalA);

        // Add marketing suppression
        suppressionService.addMarketing(orgA, mid, SuppressionEntry.REASON_SPAM, principalA);

        // Deliverability is NOT blocked
        assertThat(suppressionService.isDeliverabilityBlocked(email)).isFalse();

        // Add deliverability suppression
        suppressionService.addDeliverability(email, SuppressionEntry.REASON_HARD_BOUNCE);
        assertThat(suppressionService.isDeliverabilityBlocked(email)).isTrue();

        // Both suppress independently — gate excludes on marketing_suppressed (checked first)
        SendGateService.GateResult r = sendGateService.evaluate(orgA, List.of(mid));
        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded().get(0).reason()).isEqualTo("marketing_suppressed");
    }

    /** Cross-org membership ID rejected by SuppressionService with 404 (tenant isolation) */
    @Test
    void suppression_marketing_cross_org_rejected_404() {
        orderProjector.upsertMembership(orgB, "xorg@supp.com", "X");
        Consumer c = consumerRepo.findByNormalizedEmail("xorg@supp.com").orElseThrow();
        Membership mB = membershipRepo.findByOrgIdAndConsumerId(orgB, c.getConsumerId()).orElseThrow();

        // orgA tries to add marketing suppression for orgB's member → 404
        assertThatThrownBy(() ->
                suppressionService.addMarketing(orgA, mB.getMembershipId(),
                        SuppressionEntry.REASON_MANUAL, principalA))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUDIT — ArgumentCaptor assertions
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void audit_consent_captured_correct_action() {
        orderProjector.upsertMembership(orgA, "audit-cap@t.com", "A");
        Consumer c = consumerRepo.findByNormalizedEmail("audit-cap@t.com").orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgA, c.getConsumerId()).orElseThrow();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        consentService.capture(orgA, m.getMembershipId(), "explicit", "test", null, principalA);

        verify(auditLogger).record(any(), captor.capture(), any(), any(), any());
        assertThat(captor.getValue()).isEqualTo(AuditActions.CONSENT_CAPTURED);
    }

    @Test
    void audit_consent_unsubscribed_correct_action() {
        UUID mid = seedSubscribed(orgA, "audit-unsub@t.com", "explicit", principalA);
        reset(auditLogger);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        consentService.unsubscribe(orgA, mid, "link", principalA);

        verify(auditLogger).record(any(), captor.capture(), any(), any(), any());
        assertThat(captor.getValue()).isEqualTo(AuditActions.CONSENT_UNSUBSCRIBED);
    }

    @Test
    void audit_suppression_added_correct_action() {
        UUID mid = seedSubscribed(orgA, "audit-supp@t.com", "explicit", principalA);
        reset(auditLogger);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        suppressionService.addMarketing(orgA, mid, SuppressionEntry.REASON_MANUAL, principalA);

        verify(auditLogger).record(any(), captor.capture(), any(), any(), any());
        assertThat(captor.getValue()).isEqualTo(AuditActions.SUPPRESSION_ADDED);
    }

    @Test
    void audit_handoff_correct_action() {
        UUID mid = seedSubscribed(orgA, "audit-handoff@t.com", "explicit", principalA);
        reset(auditLogger);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        sendGateService.handoff(orgA, List.of(mid), principalA);

        verify(auditLogger).record(any(), captor.capture(), any(), any(), any());
        assertThat(captor.getValue()).isEqualTo(AuditActions.AUDIENCE_HANDOFF);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HANDOFF response shape
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void handoff_response_counts_and_urls_correct() {
        UUID mid1 = seedSubscribed(orgA, "h1@gate.com", "explicit", principalA);
        UUID mid2 = seedSubscribed(orgA, "h2@gate.com", "explicit", principalA);
        UUID mid3 = seedSubscribed(orgA, "h3@gate.com", "explicit", principalA);
        consentService.unsubscribe(orgA, mid3, "test", principalA);

        var response = sendGateService.handoff(orgA, List.of(mid1, mid2, mid3), principalA);

        assertThat(response.recipientCount()).isEqualTo(2);
        assertThat(response.selectedMembershipIds()).hasSize(2);
        assertThat(response.excludedReasons()).hasSize(1);
        assertThat(response.excludedReasons().get(0).reason()).isEqualTo("marketing_unsubscribed");
        assertThat(response.campaignHubUrl()).isNotBlank();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** Seed: ingest + capture consent → membership in 'subscribed' state */
    private UUID seedSubscribed(UUID orgId, String email, String basis, AuthPrincipal principal) {
        orderProjector.upsertMembership(orgId, email, email);
        Consumer c = consumerRepo.findByNormalizedEmail(email).orElseThrow();
        Membership m = membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
        consentService.capture(orgId, m.getMembershipId(), basis, "seed", null, principal);
        return m.getMembershipId();
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
            s.execute("delete from orders");
            s.execute("delete from events");
            s.execute("delete from users");
            s.execute("delete from organizations");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}
