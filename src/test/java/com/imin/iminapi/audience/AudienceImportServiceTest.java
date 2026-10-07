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
import com.imin.iminapi.audience.service.ImportAttestation;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.audience.service.CsvContactParser;
import com.imin.iminapi.audienceplan.model.AudienceImport;
import com.imin.iminapi.audienceplan.model.ImportRowProvenance;
import com.imin.iminapi.audienceplan.repository.AudienceImportRepository;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail + counting tests for {@link AudienceImportService} on the shared Postgres.
 *
 * <p>Load-bearing: an organizer CSV must never resurrect a suppressed contact
 * ({@link #suppressed_email_stays_unsubscribed_or_never()}), and only a row with its own
 * proof becomes explicit consent ({@link #attestation_only_row_gets_no_basis()}).
 *
 * <p>Every address is unique per test: consumers, deliverability suppressions and platform-wide erasure
 * entries are keyed by email across orgs.
 */
@IminIntegrationTest
class AudienceImportServiceTest {

    @Autowired AudienceImportService importService;
    @Autowired ConsentService consentService;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired SuppressionRepository suppressionRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired DsarService dsarService;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired AudienceImportRepository importRepo;
    @Autowired ImportRowProvenanceRepository provenanceRepo;

    private final Map<String, String> addresses = new HashMap<>();
    private UUID orgId;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        principal = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    /** Own rows only: memberships cascade consent and provenance; imports, org suppressions, org ledger. */
    @AfterEach
    void tearDown() {
        jdbc.update("delete from memberships where org_id = ?", orgId);
        jdbc.update("delete from audience_imports where org_id = ?", orgId);
        jdbc.update("delete from suppression_entries where org_id = ?", orgId);
        jdbc.update("delete from erased_addresses where org_id = ?", orgId);
    }

    /** The same unique, normalized address for a tag within one test. */
    private String a(String tag) {
        return addresses.computeIfAbsent(tag, fx::email);
    }

    /** The address as an organizer's CSV might spell it: capitalised, domain upper-cased. */
    private static String mixed(String address) {
        int at = address.indexOf('@');
        return Character.toUpperCase(address.charAt(0)) + address.substring(1, at)
                + address.substring(at).toUpperCase(Locale.ROOT);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<CsvContactParser.RawContact> rows(String... emails) {
        List<CsvContactParser.RawContact> out = new ArrayList<>();
        int line = 2;
        for (String e : emails) {
            out.add(new CsvContactParser.RawContact(line++, e, null, null));
        }
        return out;
    }

    /** A row carrying its own proof of explicit consent. */
    private static CsvContactParser.RawContact proven(int line, String email) {
        return new CsvContactParser.RawContact(line, email, null, null, "shotgun", "2026-09-01",
                "Night A 2026-08-01", "2026-08-01", "opted_in", "optin-screenshot-" + line);
    }

    private static CsvContactParser.RawContact withStatus(int line, String email, String status) {
        return new CsvContactParser.RawContact(line, email, null, null, "shotgun", "2026-09-01",
                null, null, status, "proof-" + line);
    }

    private Membership membershipFor(String normalizedEmail) {
        Consumer c = consumerRepo.findByNormalizedEmail(normalizedEmail).orElseThrow();
        return membershipRepo.findByOrgIdAndConsumerId(orgId, c.getConsumerId()).orElseThrow();
    }

    /** Seed an existing membership at a given consent state via the real import path, then mutate. */
    private Membership seedMember(String email) {
        importService.importContacts(List.of(proven(2, email)), false, principal);
        return membershipFor(email);
    }

    // ── attestation alone → member without basis ────────────────────────────

    @Test
    void attestation_only_row_gets_no_basis() {
        ImportResultResponse r = importService.importContacts(rows(mixed(a("alice"))), false, principal);

        assertThat(r.total()).isEqualTo(1);
        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.rowsExplicit()).isZero();
        assertThat(r.rowsNoBasis()).isEqualTo(1);

        Membership m = membershipFor(a("alice"));
        assertThat(m.getConsentStatus()).isEqualTo("never");
        assertThat(m.getConsentBasis()).isNull();
        assertThat(consentRepo.findByMembershipId(m.getMembershipId())).isEmpty();
        List<ImportRowProvenance> prov = provenanceRepo.findByMembershipIdOrderByCreatedAtAsc(m.getMembershipId());
        assertThat(prov).singleElement().satisfies(p -> {
            assertThat(p.isAccepted()).isFalse();
            assertThat(p.getRejectReason()).isEqualTo("not_opted_in");
            assertThat(p.getMarketingStatus()).isEqualTo("none");
        });
    }

    // ── opted_in with its own proof → explicit + organizer_import_row ─────────

    @Test
    void opted_in_row_with_full_provenance_becomes_explicit_with_a_provenance_row() {
        ImportResultResponse r = importService.importContacts(List.of(proven(2, mixed(a("alice")))), false,
                principal, new AudienceImportService.ImportOptions("v2-2026-09-27", "ab".repeat(32), "cd".repeat(32), null));

        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.rowsExplicit()).isEqualTo(1);
        assertThat(r.rowsNoBasis()).isZero();
        assertThat(r.importId()).isNotNull();

        Membership m = membershipFor(a("alice"));
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("explicit");

        ConsentRecord rec = consentRepo.findByMembershipId(m.getMembershipId()).get(0);
        assertThat(rec.getSource()).isEqualTo("organizer_import_row");
        assertThat(rec.getLawfulBasis()).isEqualTo("explicit");
        assertThat(rec.getTextVersion()).isEqualTo("v2-2026-09-27");
        assertThat(rec.getProofText())
                .contains(ImportAttestation.STATEMENT)
                .contains("v2-2026-09-27")
                .contains(principal.userId().toString())
                .contains("optin-screenshot-2")
                .contains(r.importId().toString());

        ImportRowProvenance p = provenanceRepo.findByMembershipIdOrderByCreatedAtAsc(m.getMembershipId()).get(0);
        assertThat(p.isAccepted()).isTrue();
        assertThat(p.getRejectReason()).isNull();
        assertThat(p.getImportId()).isEqualTo(r.importId());
        assertThat(p.getRowNumber()).isEqualTo(2);
        assertThat(p.getSourcePlatform()).isEqualTo("shotgun");
        assertThat(p.getExportDate()).isEqualTo(java.time.LocalDate.parse("2026-09-01"));
        assertThat(p.getLastPurchaseDate()).isEqualTo(java.time.LocalDate.parse("2026-08-01"));
        assertThat(p.getEvents()).isEqualTo("Night A 2026-08-01");
        assertThat(p.getMarketingStatus()).isEqualTo("opted_in");
        assertThat(p.getProofRef()).isEqualTo("optin-screenshot-2");

        AudienceImport header = importRepo.findById(r.importId()).orElseThrow();
        assertThat(header.getOrgId()).isEqualTo(orgId);
        assertThat(header.getCreatedBy()).isEqualTo(principal.userId());
        assertThat(header.getUploadedFileSha256()).isEqualTo("ab".repeat(32));
        assertThat(header.getOriginalFileSha256()).isEqualTo("cd".repeat(32));
        assertThat(header.getAttestationVersion()).isEqualTo("v2-2026-09-27");
        assertThat(header.getSourcePlatform()).isEqualTo("shotgun");
        assertThat(header.getExportDate()).isEqualTo(java.time.LocalDate.parse("2026-09-01"));
        assertThat(header.getRowsTotal()).isEqualTo(1);
        assertThat(header.getRowsExplicit()).isEqualTo(1);
        assertThat(header.getRowsNoBasis()).isZero();
        assertThat(header.getRowsRejected()).isZero();
    }

    @Test
    void opted_in_row_missing_proof_ref_gets_no_basis() {
        CsvContactParser.RawContact row = new CsvContactParser.RawContact(2, a("a"), null, null,
                "shotgun", "2026-09-01", null, null, "opted_in", null);

        ImportResultResponse r = importService.importContacts(List.of(row), false, principal);

        assertThat(r.rowsExplicit()).isZero();
        assertThat(r.rowsNoBasis()).isEqualTo(1);
        Membership m = membershipFor(a("a"));
        assertThat(m.getConsentStatus()).isEqualTo("never");
        assertThat(provenanceRepo.findByMembershipIdOrderByCreatedAtAsc(m.getMembershipId()).get(0).getRejectReason())
                .isEqualTo("missing_proof");
    }

    @Test
    void unsubscribed_row_is_put_on_the_marketing_suppression_list() {
        ImportResultResponse r = importService.importContacts(
                List.of(withStatus(2, a("gone-unsub"), "unsubscribed")), false, principal);

        assertThat(r.rowsUnsubscribed()).isEqualTo(1);
        assertThat(r.rowsExplicit()).isZero();
        Membership m = membershipFor(a("gone-unsub"));
        assertThat(m.getConsentStatus()).isNotEqualTo("subscribed");
        assertThat(suppressionRepo.findMarketingByOrgAndMembership(orgId, m.getMembershipId())).isPresent();
        ImportRowProvenance p = provenanceRepo.findByMembershipIdOrderByCreatedAtAsc(m.getMembershipId()).get(0);
        assertThat(p.isAccepted()).isFalse();
        assertThat(p.getRejectReason()).isEqualTo("unsubscribed");
        assertThat(importRepo.findById(r.importId()).orElseThrow().getRowsRejected()).isEqualTo(1);
    }

    /** An older dashboard sends no version; the record says so rather than guessing. */
    @Test
    void an_unversioned_attestation_is_recorded_as_unversioned() {
        importService.importContacts(List.of(proven(2, mixed(a("alice")))), false, principal);

        Membership m = membershipFor(a("alice"));
        ConsentRecord rec = consentRepo.findByMembershipId(m.getMembershipId()).get(0);
        assertThat(rec.getProofText()).contains(ImportAttestation.UNVERSIONED);
        assertThat(rec.getTextVersion()).isEqualTo(ImportAttestation.UNVERSIONED);
    }

    @Test
    void one_provenance_row_per_imported_row_and_the_split_adds_up() {
        ImportResultResponse r = importService.importContacts(List.of(
                proven(2, a("one")),
                new CsvContactParser.RawContact(3, a("two"), null, null),
                withStatus(4, a("three"), "unsubscribed")), false, principal);

        assertThat(r.rowsExplicit() + r.rowsNoBasis() + r.rowsUnsubscribed())
                .isEqualTo(r.imported() + r.updated())
                .isEqualTo(3);
        List<ImportRowProvenance> all = provenanceRepo.findByImportId(r.importId());
        assertThat(all).hasSize(3);
        for (String email : List.of(a("one"), a("two"), a("three"))) {
            assertThat(provenanceRepo.findByMembershipIdOrderByCreatedAtAsc(
                    membershipFor(email).getMembershipId())).hasSize(1);
        }
        AudienceImport header = importRepo.findById(r.importId()).orElseThrow();
        assertThat(header.getRowsTotal()).isEqualTo(3);
        assertThat(header.getRowsExplicit()).isEqualTo(1);
        assertThat(header.getRowsNoBasis()).isEqualTo(1);
        assertThat(header.getRowsRejected()).isEqualTo(1);
    }

    @Test
    void source_platform_and_export_date_stay_null_when_rows_disagree() {
        CsvContactParser.RawContact other = new CsvContactParser.RawContact(3, a("b"), null, null,
                "dice", "2026-08-15", null, null, "opted_in", "p");
        ImportResultResponse r = importService.importContacts(
                List.of(proven(2, a("a")), other), false, principal);

        AudienceImport header = importRepo.findById(r.importId()).orElseThrow();
        assertThat(header.getRowsExplicit()).isEqualTo(2);
        assertThat(header.getSourcePlatform()).isNull();
        assertThat(header.getExportDate()).isNull();
    }

    // ── subscribed cap ─────────────────────────────────────────────────────────

    private List<CsvContactParser.RawContact> provenRows(int n) {
        List<CsvContactParser.RawContact> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(proven(i + 2, fx.email("cap" + i)));
        return out;
    }

    @Test
    void the_2001st_opted_in_row_without_import_level_proof_is_downgraded() {
        ImportResultResponse r = importService.importContacts(provenRows(2_001), true, principal);

        assertThat(r.rowsExplicit()).isEqualTo(2_000);
        assertThat(r.rowsNoBasis()).isEqualTo(1);
        assertThat(r.rowsCapped()).isEqualTo(1);
    }

    @Test
    void import_level_proof_lifts_the_subscribed_cap() {
        ImportResultResponse r = importService.importContacts(provenRows(2_001), true, principal,
                new AudienceImportService.ImportOptions(null, null, null, "https://platform.example/consent-export"));

        assertThat(r.rowsExplicit()).isEqualTo(2_001);
        assertThat(r.rowsCapped()).isZero();
    }

    // ── CRITICAL: suppressed email stays unsubscribed / never ──────────────────

    @Test
    void suppressed_email_stays_unsubscribed_or_never() {
        // (a) deliverability-suppressed brand-new email → imported as member, NOT subscribed
        SuppressionEntry deliv = new SuppressionEntry();
        deliv.setScope(SuppressionEntry.SCOPE_DELIVERABILITY);
        deliv.setNormalizedEmail(a("bounced"));
        deliv.setReason(SuppressionEntry.REASON_HARD_BOUNCE);
        deliv.setSystemOwned(true);
        suppressionRepo.save(deliv);

        // (b) marketing-suppressed EXISTING member (org-scoped) → stays as-is
        Membership existing = seedMember(a("marketing-suppressed"));
        // move it to a clean 'never'/unsub baseline: unsubscribe it, then org-suppress it
        consentService.unsubscribe(orgId, existing.getMembershipId(), "test", "email",
                ConsentOrigin.OPERATOR, null);
        SuppressionEntry mkt = new SuppressionEntry();
        mkt.setScope(SuppressionEntry.SCOPE_MARKETING);
        mkt.setOrgId(orgId);
        mkt.setMembershipId(existing.getMembershipId());
        mkt.setReason(SuppressionEntry.REASON_MANUAL);
        mkt.setSystemOwned(false);
        suppressionRepo.save(mkt);

        ImportResultResponse r = importService.importContacts(
                rows(a("bounced"), a("marketing-suppressed")), false, principal);

        assertThat(r.suppressed()).isEqualTo(2);
        assertThat(r.imported()).isZero();
        assertThat(r.updated()).isZero();

        // deliverability contact: member row created, consent NEVER
        Membership bounced = membershipFor(a("bounced"));
        assertThat(bounced.getConsentStatus()).isEqualTo("never");
        assertThat(bounced.getConsentBasis()).isNull();
        assertThat(consentRepo.findByMembershipId(bounced.getMembershipId())).isEmpty();

        // marketing contact: stays unsubscribed, NOT flipped by the import
        Membership mkSup = membershipFor(a("marketing-suppressed"));
        assertThat(mkSup.getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(provenanceRepo.findByImportId(r.importId()))
                .extracting(ImportRowProvenance::getRejectReason)
                .containsOnly("suppressed");
    }

    // ── unsubscribed member is never re-subscribed ─────────────────────────────

    @Test
    void unsubscribed_member_is_not_resubscribed() {
        Membership m = seedMember(a("optout"));
        consentService.unsubscribe(orgId, m.getMembershipId(), "test", "email",
                ConsentOrigin.OPERATOR, null);
        assertThat(membershipFor(a("optout")).getConsentStatus()).isEqualTo("unsubscribed");

        ImportResultResponse r = importService.importContacts(
                List.of(proven(2, a("optout"))), false, principal);

        assertThat(r.skippedUnsubscribed()).isEqualTo(1);
        assertThat(r.imported()).isZero();
        assertThat(r.updated()).isZero();
        assertThat(r.rowsExplicit()).isZero();
        assertThat(membershipFor(a("optout")).getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(provenanceRepo.findByImportId(r.importId()))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.isAccepted()).isFalse();
                    assertThat(p.getRejectReason()).isEqualTo("previously_unsubscribed");
                });
    }

    // ── existing subscribed member re-confirmed → updated ──────────────────────

    @Test
    void existing_subscribed_member_keeps_its_consent_on_a_row_without_basis() {
        seedMember(a("already")); // first import → imported + subscribed on its own proof
        ImportResultResponse r = importService.importContacts(rows(a("already")), false, principal);
        assertThat(r.imported()).isZero();
        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.rowsNoBasis()).isEqualTo(1);
        Membership m = membershipFor(a("already"));
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(consentRepo.findByMembershipId(m.getMembershipId())).hasSize(1);
    }

    // ── dedup within file (last wins) ──────────────────────────────────────────

    @Test
    void dedup_within_file_collapses_duplicates() {
        ImportResultResponse r = importService.importContacts(
                rows(a("dup"), a("dup").toUpperCase(Locale.ROOT), mixed(a("dup"))), false, principal);
        assertThat(r.total()).isEqualTo(3);
        assertThat(r.imported()).isEqualTo(1); // collapsed to one unique contact
        assertThat(membershipRepo.countByOrgId(orgId)).isEqualTo(1);
    }

    // ── invalid emails counted, not written ────────────────────────────────────

    @Test
    void invalid_emails_are_counted_and_reported() {
        ImportResultResponse r = importService.importContacts(
                rows(a("good"), "no-at-sign", "no-dot@domain", "  "), false, principal);
        assertThat(r.total()).isEqualTo(4);
        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.invalidEmails()).isEqualTo(3);
        assertThat(r.errors()).hasSize(3);
    }

    // ── dryRun writes nothing ──────────────────────────────────────────────────

    @Test
    void dry_run_classifies_but_writes_nothing() {
        ImportResultResponse r = importService.importContacts(
                List.of(proven(2, a("preview")), withStatus(3, a("preview2"), "unsubscribed")),
                true, principal);
        assertThat(r.imported()).isEqualTo(2);
        assertThat(r.rowsExplicit()).isEqualTo(1);
        assertThat(r.rowsUnsubscribed()).isEqualTo(1);
        assertThat(r.importId()).isNull();
        assertThat(membershipRepo.countByOrgId(orgId)).isZero();
        assertThat(consumerRepo.findByNormalizedEmail(a("preview"))).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from audience_imports where org_id = ?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from import_row_provenance p join memberships m "
                + "on m.membership_id = p.membership_id where m.org_id = ?", Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from suppression_entries where org_id = ? "
                + "or normalized_email in (?, ?)", Long.class, orgId, a("preview"), a("preview2"))).isZero();
        assertThat(consentRecordCount()).isZero();
    }

    @Test
    void dry_run_matches_real_counts() {
        List<CsvContactParser.RawContact> in = rows(a("a"), a("b"));
        ImportResultResponse preview = importService.importContacts(in, true, principal);
        ImportResultResponse real = importService.importContacts(in, false, principal);
        assertThat(preview.imported()).isEqualTo(real.imported());
        assertThat(preview.total()).isEqualTo(real.total());
    }

    // ── phone best-effort normalization ────────────────────────────────────────

    @Test
    void phone_is_normalized_when_parseable_and_skipped_otherwise() {
        List<CsvContactParser.RawContact> in = List.of(
                new CsvContactParser.RawContact(2, a("phone"), "P", "+1 (555) 123-4567"),
                new CsvContactParser.RawContact(3, a("nophone"), "N", "not-a-phone"));
        ImportResultResponse r = importService.importContacts(in, false, principal);
        assertThat(r.imported()).isEqualTo(2); // bad phone does NOT reject the row

        assertThat(membershipFor(a("phone")).getPhoneE164()).isEqualTo("+15551234567");
        assertThat(membershipFor(a("nophone")).getPhoneE164()).isNull();
    }

    // ── erasure ledger: an erased address is never rebuilt by an import ────────

    @Test
    void platformWideErasedAddress_isSkippedAsOther_andNoProfileIsRebuilt() {
        dsarService.recordErasure(null, a("gone"));

        ImportResultResponse r = importService.importContacts(rows(mixed(a("gone"))), false, principal);

        assertThat(r.skippedOther()).isEqualTo(1);
        assertThat(r.skippedErased()).isZero();
        assertThat(r.imported()).isZero();
        assertThat(r.suppressed()).isZero();
        assertThat(r.errors()).isEmpty();
        assertThat(consumerRepo.findByNormalizedEmail(a("gone"))).isEmpty();
        assertThat(consentRecordCount()).isZero();
    }

    @Test
    void orgErasedAddress_isSkipped() {
        dsarService.recordErasure(orgId, a("gone"));

        ImportResultResponse r = importService.importContacts(List.of(proven(2, mixed(a("gone")))), false, principal);

        assertThat(r.rowsExplicit()).isZero();
        assertThat(provenanceRepo.findByImportId(r.importId())).isEmpty();
        assertThat(importRepo.findById(r.importId()).orElseThrow().getRowsRejected()).isEqualTo(1);

        assertThat(r.skippedErased()).isEqualTo(1);
        assertThat(r.skippedOther()).isZero();
        assertThat(r.imported()).isZero();
        assertThat(r.suppressed()).isZero();
        assertThat(r.errors()).isEmpty();
        assertThat(consumerRepo.findByNormalizedEmail(a("gone"))).isEmpty();
        assertThat(consentRecordCount()).isZero();
    }

    /** Scope pin: another org's erasure does not bind this org. */
    @Test
    void addressErasedByAnotherOrgOnly_importsNormally() {
        dsarService.recordErasure(UUID.randomUUID(), a("other"));

        ImportResultResponse r = importService.importContacts(List.of(proven(2, a("other"))), false, principal);

        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.skippedErased()).isZero();
        Membership m = membershipFor(a("other"));
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("explicit");
    }

    /** An address on both ledgers is counted once, under this org's label. */
    @Test
    void addressOnBothLedgers_isCountedOnceAsErased() {
        dsarService.recordErasure(null, a("gone"));
        dsarService.recordErasure(orgId, a("gone"));

        ImportResultResponse r = importService.importContacts(rows(a("gone")), false, principal);

        assertThat(r.skippedErased()).isEqualTo(1);
        assertThat(r.skippedOther()).isZero();
        assertThat(r.errors()).isEmpty();
        assertThat(consumerRepo.findByNormalizedEmail(a("gone"))).isEmpty();
    }

    @Test
    void dryRun_countsPlatformWideErasedAddressAsOther_withoutWriting() {
        dsarService.recordErasure(null, a("gone"));

        ImportResultResponse r = importService.importContacts(rows(a("gone")), true, principal);

        assertThat(r.skippedOther()).isEqualTo(1);
        assertThat(r.skippedErased()).isZero();
        assertThat(r.errors()).isEmpty();
        assertThat(consumerRepo.findByNormalizedEmail(a("gone"))).isEmpty();
        assertThat(consentRecordCount()).isZero();
    }

    /** Consent rows of this test's org; a rebuilt profile would land here. */
    private long consentRecordCount() {
        return jdbc.queryForObject("select count(*) from consent_records c join memberships m "
                + "on m.membership_id = c.membership_id where m.org_id = ?", Long.class, orgId);
    }
}
