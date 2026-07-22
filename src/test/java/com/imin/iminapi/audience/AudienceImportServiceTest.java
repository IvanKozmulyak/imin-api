package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.ImportResultResponse;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.AudienceImportService;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.CsvContactParser;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail + counting tests for {@link AudienceImportService} against H2.
 *
 * <p>The load-bearing test is {@link #suppressed_email_stays_unsubscribed_or_never()} —
 * an organizer CSV must never resurrect a suppressed contact.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceImportServiceTest {

    @Autowired AudienceImportService importService;
    @Autowired ConsentService consentService;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired SuppressionRepository suppressionRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired DataSource dataSource;

    // AuditLogger is best-effort; stub it so consent-capture audit writes don't hit UserRepository.
    @MockitoBean AuditLogger auditLogger;

    private UUID orgId;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        wipe();
        orgId = UUID.randomUUID();
        principal = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<CsvContactParser.RawContact> rows(String... emails) {
        List<CsvContactParser.RawContact> out = new ArrayList<>();
        int line = 2;
        for (String e : emails) {
            out.add(new CsvContactParser.RawContact(line++, e, null, null));
        }
        return out;
    }

    private Membership membershipFor(String normalizedEmail) {
        Consumer c = consumerRepo.findByNormalizedEmail(normalizedEmail).orElseThrow();
        return membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
    }

    /** Seed an existing membership at a given consent state via the real import path, then mutate. */
    private Membership seedMember(String email) {
        importService.importContacts(rows(email), false, principal);
        return membershipFor(email);
    }

    // ── new contact → subscribed + explicit + organizer_import ─────────────────

    @Test
    void new_contact_becomes_subscribed_explicit_with_organizer_import_source() {
        ImportResultResponse r = importService.importContacts(rows("Alice@Example.com"), false, principal);

        assertThat(r.total()).isEqualTo(1);
        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.updated()).isZero();
        assertThat(r.suppressed()).isZero();
        assertThat(r.skippedUnsubscribed()).isZero();

        Membership m = membershipFor("alice@example.com");
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("explicit");

        List<ConsentRecord> records = consentRepo.findByMembershipId(m.getMembershipId());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getSource()).isEqualTo("organizer_import");
        assertThat(records.get(0).getLawfulBasis()).isEqualTo("explicit");
        assertThat(records.get(0).getStatus()).isEqualTo("subscribed");
        // proof records who + when
        assertThat(records.get(0).getProofText()).contains(principal.userId().toString());
    }

    // ── CRITICAL: suppressed email stays unsubscribed / never ──────────────────

    @Test
    void suppressed_email_stays_unsubscribed_or_never() {
        // (a) deliverability-suppressed brand-new email → imported as member, NOT subscribed
        SuppressionEntry deliv = new SuppressionEntry();
        deliv.setScope(SuppressionEntry.SCOPE_DELIVERABILITY);
        deliv.setNormalizedEmail("bounced@example.com");
        deliv.setReason(SuppressionEntry.REASON_HARD_BOUNCE);
        deliv.setSystemOwned(true);
        suppressionRepo.save(deliv);

        // (b) marketing-suppressed EXISTING member (org-scoped) → stays as-is
        Membership existing = seedMember("marketing-suppressed@example.com");
        // move it to a clean 'never'/unsub baseline: unsubscribe it, then org-suppress it
        consentService.unsubscribe(orgId, existing.getMembershipId(), "test", "email", null);
        SuppressionEntry mkt = new SuppressionEntry();
        mkt.setScope(SuppressionEntry.SCOPE_MARKETING);
        mkt.setOrgId(orgId);
        mkt.setMembershipId(existing.getMembershipId());
        mkt.setReason(SuppressionEntry.REASON_MANUAL);
        mkt.setSystemOwned(false);
        suppressionRepo.save(mkt);

        ImportResultResponse r = importService.importContacts(
                rows("bounced@example.com", "marketing-suppressed@example.com"), false, principal);

        assertThat(r.suppressed()).isEqualTo(2);
        assertThat(r.imported()).isZero();
        assertThat(r.updated()).isZero();

        // deliverability contact: member row created, consent NEVER
        Membership bounced = membershipFor("bounced@example.com");
        assertThat(bounced.getConsentStatus()).isEqualTo("never");
        assertThat(bounced.getConsentBasis()).isNull();
        assertThat(consentRepo.findByMembershipId(bounced.getMembershipId())).isEmpty();

        // marketing contact: stays unsubscribed, NOT flipped by the import
        Membership mkSup = membershipFor("marketing-suppressed@example.com");
        assertThat(mkSup.getConsentStatus()).isEqualTo("unsubscribed");
    }

    // ── unsubscribed member is never re-subscribed ─────────────────────────────

    @Test
    void unsubscribed_member_is_not_resubscribed() {
        Membership m = seedMember("optout@example.com");
        consentService.unsubscribe(orgId, m.getMembershipId(), "test", "email", null);
        assertThat(membershipFor("optout@example.com").getConsentStatus()).isEqualTo("unsubscribed");

        ImportResultResponse r = importService.importContacts(rows("optout@example.com"), false, principal);

        assertThat(r.skippedUnsubscribed()).isEqualTo(1);
        assertThat(r.imported()).isZero();
        assertThat(r.updated()).isZero();
        assertThat(membershipFor("optout@example.com").getConsentStatus()).isEqualTo("unsubscribed");
    }

    // ── existing subscribed member re-confirmed → updated ──────────────────────

    @Test
    void existing_member_reconfirmed_counts_as_updated() {
        seedMember("already@example.com"); // first import → imported + subscribed
        ImportResultResponse r = importService.importContacts(rows("already@example.com"), false, principal);
        assertThat(r.imported()).isZero();
        assertThat(r.updated()).isEqualTo(1);
        assertThat(membershipFor("already@example.com").getConsentStatus()).isEqualTo("subscribed");
    }

    // ── dedup within file (last wins) ──────────────────────────────────────────

    @Test
    void dedup_within_file_collapses_duplicates() {
        ImportResultResponse r = importService.importContacts(
                rows("dup@example.com", "DUP@example.com", "dup@EXAMPLE.com"), false, principal);
        assertThat(r.total()).isEqualTo(3);
        assertThat(r.imported()).isEqualTo(1); // collapsed to one unique contact
        assertThat(membershipRepo.countByOrgId(orgId)).isEqualTo(1);
    }

    // ── invalid emails counted, not written ────────────────────────────────────

    @Test
    void invalid_emails_are_counted_and_reported() {
        ImportResultResponse r = importService.importContacts(
                rows("good@example.com", "no-at-sign", "no-dot@domain", "  "), false, principal);
        assertThat(r.total()).isEqualTo(4);
        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.invalidEmails()).isEqualTo(3);
        assertThat(r.errors()).hasSize(3);
    }

    // ── dryRun writes nothing ──────────────────────────────────────────────────

    @Test
    void dry_run_classifies_but_writes_nothing() {
        ImportResultResponse r = importService.importContacts(
                rows("preview@example.com", "preview2@example.com"), true, principal);
        assertThat(r.imported()).isEqualTo(2);
        assertThat(membershipRepo.countByOrgId(orgId)).isZero();
        assertThat(consumerRepo.findByNormalizedEmail("preview@example.com")).isEmpty();
    }

    @Test
    void dry_run_matches_real_counts() {
        List<CsvContactParser.RawContact> in = rows("a@example.com", "b@example.com");
        ImportResultResponse preview = importService.importContacts(in, true, principal);
        ImportResultResponse real = importService.importContacts(in, false, principal);
        assertThat(preview.imported()).isEqualTo(real.imported());
        assertThat(preview.total()).isEqualTo(real.total());
    }

    // ── phone best-effort normalization ────────────────────────────────────────

    @Test
    void phone_is_normalized_when_parseable_and_skipped_otherwise() {
        List<CsvContactParser.RawContact> in = List.of(
                new CsvContactParser.RawContact(2, "phone@example.com", "P", "+1 (555) 123-4567"),
                new CsvContactParser.RawContact(3, "nophone@example.com", "N", "not-a-phone"));
        ImportResultResponse r = importService.importContacts(in, false, principal);
        assertThat(r.imported()).isEqualTo(2); // bad phone does NOT reject the row

        assertThat(membershipFor("phone@example.com").getPhoneE164()).isEqualTo("+15551234567");
        assertThat(membershipFor("nophone@example.com").getPhoneE164()).isNull();
    }

    // ── wipe ───────────────────────────────────────────────────────────────────

    private void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from suppression_entries");
            s.execute("delete from consent_records");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}
