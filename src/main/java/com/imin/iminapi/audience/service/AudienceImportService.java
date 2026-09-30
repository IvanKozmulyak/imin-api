package com.imin.iminapi.audience.service;

import com.imin.iminapi.audienceplan.model.AudienceImport;
import com.imin.iminapi.audienceplan.repository.AudienceImportRepository;
import com.imin.iminapi.audienceplan.service.ImportProvenanceWriter;
import com.imin.iminapi.audienceplan.service.ImportValidator;
import com.imin.iminapi.util.LogSafe;
import com.imin.iminapi.audience.dto.ImportResultResponse;
import com.imin.iminapi.audience.dto.ImportResultResponse.ImportError;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.service.audience.PhoneNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Organizer contact import with per-row provenance.
 *
 * <p>An organizer uploads a CSV of contacts. Every valid, non-erased contact becomes a member,
 * but consent comes only from the row's own provenance ({@link ImportValidator}): a row marked
 * {@code opted_in} with a proof reference, a source platform and an export date is subscribed
 * with {@code consent_basis='explicit'} and {@code source='organizer_import_row'}, together with
 * an accepted {@code import_row_provenance} row. A row marked {@code unsubscribed} is put on the
 * org's marketing suppression list. Every other row is kept without a basis and is not mailable.
 * The whole-file attestation is recorded but never makes a row explicit.
 *
 * <p><b>Guardrails (non-negotiable):</b>
 * <ol>
 *   <li><b>Suppression is absolute.</b> A contact on the org's marketing suppression list OR
 *       the global deliverability suppression (hard bounce) is imported as a
 *       member but never subscribed — an organizer CSV cannot resurrect a suppressed
 *       contact.</li>
 *   <li><b>Explicit unsubscribes are never flipped.</b> A member who unsubscribed stays
 *       unsubscribed; the import counts them as {@code skippedUnsubscribed}.</li>
 *   <li><b>Erased addresses stay erased.</b> An address on this org's erasure ledger is skipped
 *       before any write and counted as {@code skippedErased}; one only on the platform-wide
 *       ledger is skipped the same way but counted as the neutral {@code skippedOther}, so the
 *       organizer never learns of an erasure made outside their org.</li>
 * </ol>
 */
@Service
public class AudienceImportService {

    private static final Logger log = LoggerFactory.getLogger(AudienceImportService.class);

    /** Basic RFC-ish: non-empty local part, an @, a domain containing a dot, no whitespace. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final int MAX_ERRORS = 50;
    private static final int MAX_IMPORT_PROOF_REF = 500;

    private final ConsumerRepository consumerRepo;
    private final MembershipRepository membershipRepo;
    private final SuppressionRepository suppressionRepo;
    private final SuppressionService suppressionService;
    private final AudienceOrderProjector projector;
    private final AuditLogger auditLogger;
    private final ErasedAddressRepository erasedAddressRepo;
    private final AudienceImportRepository importRepo;
    private final ImportProvenanceWriter provenanceWriter;
    private final Clock clock;

    public AudienceImportService(ConsumerRepository consumerRepo,
                                 MembershipRepository membershipRepo,
                                 SuppressionRepository suppressionRepo,
                                 SuppressionService suppressionService,
                                 AudienceOrderProjector projector,
                                 AuditLogger auditLogger,
                                 ErasedAddressRepository erasedAddressRepo,
                                 AudienceImportRepository importRepo,
                                 ImportProvenanceWriter provenanceWriter,
                                 Clock clock) {
        this.consumerRepo = consumerRepo;
        this.membershipRepo = membershipRepo;
        this.suppressionRepo = suppressionRepo;
        this.suppressionService = suppressionService;
        this.projector = projector;
        this.auditLogger = auditLogger;
        this.erasedAddressRepo = erasedAddressRepo;
        this.importRepo = importRepo;
        this.provenanceWriter = provenanceWriter;
        this.clock = clock;
    }

    /**
     * What the caller knows about the import as a whole.
     *
     * @param attestationVersion the revision of the attestation statement the dashboard
     *        displayed, or null (recorded as {@link ImportAttestation#UNVERSIONED})
     * @param uploadedFileSha256 hex SHA-256 of the bytes the API received, or null
     * @param originalFileSha256 hex SHA-256 of the organizer's original file, as sent by the
     *        client, or null
     * @param proofRef import-level evidence; lifts the cap on subscribed rows
     */
    public record ImportOptions(String attestationVersion, String uploadedFileSha256,
                                String originalFileSha256, String proofRef) {
        public static ImportOptions none() {
            return new ImportOptions(null, null, null, null);
        }
    }

    private enum Classification { IMPORTED, UPDATED, SUPPRESSED, SKIPPED_UNSUBSCRIBED, SKIPPED_ERASED, SKIPPED_OTHER }

    /** A row's membership outcome and, for rows that land as members, its consent decision. */
    private record RowResult(Classification classification, ImportValidator.Decision decision) {}

    public ImportResultResponse importContacts(List<CsvContactParser.RawContact> rawRows,
                                               boolean dryRun,
                                               AuthPrincipal principal) {
        return importContacts(rawRows, dryRun, principal, ImportOptions.none());
    }

    public ImportResultResponse importContacts(List<CsvContactParser.RawContact> rawRows,
                                               boolean dryRun,
                                               AuthPrincipal principal,
                                               String attestationVersion) {
        return importContacts(rawRows, dryRun, principal,
                new ImportOptions(attestationVersion, null, null, null));
    }

    /**
     * Import (or, when {@code dryRun}, preview) the parsed contacts. A preview writes nothing,
     * not even the import record.
     *
     * <p>Not {@code @Transactional} at this level: each contact is written in its own
     * transaction, so one bad row can never roll back the whole batch.
     */
    public ImportResultResponse importContacts(List<CsvContactParser.RawContact> rawRows,
                                               boolean dryRun,
                                               AuthPrincipal principal,
                                               ImportOptions options) {
        String version = ImportAttestation.version(options.attestationVersion());
        String importProof = clip(options.proofRef(), MAX_IMPORT_PROOF_REF);
        int total = rawRows.size();
        int invalidEmails = 0;
        List<ImportError> errors = new java.util.ArrayList<>();

        // 1. Validate + dedup within the file (last wins), keyed on normalized email.
        Map<String, CsvContactParser.RawContact> unique = new LinkedHashMap<>();
        for (CsvContactParser.RawContact row : rawRows) {
            String raw = row.rawEmail();
            if (raw == null || raw.isBlank() || !EMAIL.matcher(raw.trim()).matches()) {
                invalidEmails++;
                addError(errors, row.rowNumber(), raw, "invalid or missing email");
                continue;
            }
            String normalized = EmailNormalizer.normalize(raw);
            unique.put(normalized, row); // last occurrence wins
        }

        AudienceImport header = null;
        if (!dryRun) {
            header = new AudienceImport();
            header.setOrgId(principal.orgId());
            header.setCreatedBy(principal.userId());
            header.setUploadedFileSha256(options.uploadedFileSha256());
            header.setOriginalFileSha256(options.originalFileSha256());
            header.setProofRef(importProof);
            header.setAttestationVersion(version);
            header = importRepo.save(header);
        }
        UUID importId = header == null ? null : header.getId();

        ImportValidator validator = ImportValidator.forImport(importProof != null, LocalDate.now(clock));
        int imported = 0, updated = 0, suppressed = 0, skippedUnsubscribed = 0, skippedErased = 0,
                skippedOther = 0, rowsExplicit = 0, rowsNoBasis = 0, rowsUnsubscribed = 0,
                rowsCapped = 0;
        Set<String> platforms = new HashSet<>();
        Set<LocalDate> exportDates = new HashSet<>();

        // 2. Classify + (unless dryRun) apply each unique contact.
        for (Map.Entry<String, CsvContactParser.RawContact> e : unique.entrySet()) {
            String email = e.getKey();
            CsvContactParser.RawContact row = e.getValue();
            try {
                RowResult r = process(principal.orgId(), email, row, dryRun, principal, version,
                        importId, validator);
                switch (r.classification()) {
                    case IMPORTED -> imported++;
                    case UPDATED -> updated++;
                    case SUPPRESSED -> suppressed++;
                    case SKIPPED_UNSUBSCRIBED -> skippedUnsubscribed++;
                    case SKIPPED_ERASED -> skippedErased++;
                    case SKIPPED_OTHER -> skippedOther++;
                }
                ImportValidator.Decision d = r.decision();
                if (d != null && isMember(r.classification())) {
                    switch (d.outcome()) {
                        case EXPLICIT -> {
                            rowsExplicit++;
                            platforms.add(d.sourcePlatform());
                            exportDates.add(d.exportDate());
                        }
                        case UNSUBSCRIBE -> rowsUnsubscribed++;
                        case NO_BASIS -> {
                            rowsNoBasis++;
                            if (ImportValidator.REASON_SUBSCRIBED_CAP.equals(d.reason())) rowsCapped++;
                        }
                    }
                }
            } catch (RuntimeException ex) {
                log.error("Import row {} ({}) failed: {}", row.rowNumber(), LogSafe.email(email),
                        LogSafe.redact(ex.getMessage()), ex);
                addError(errors, row.rowNumber(), email, "processing error");
            }
        }

        if (header != null) {
            header.setRowsTotal(total);
            header.setRowsExplicit(rowsExplicit);
            header.setRowsNoBasis(rowsNoBasis);
            header.setRowsRejected(invalidEmails + skippedErased + skippedOther + suppressed
                    + skippedUnsubscribed + rowsUnsubscribed);
            header.setSourcePlatform(platforms.size() == 1 ? platforms.iterator().next() : null);
            header.setExportDate(exportDates.size() == 1 ? exportDates.iterator().next() : null);
            importRepo.save(header);

            auditLogger.record(principal, AuditActions.AUDIENCE_IMPORTED, "audience", importId,
                    "CSV import: total=" + total + " imported=" + imported + " updated=" + updated
                            + " suppressed=" + suppressed + " skippedUnsubscribed=" + skippedUnsubscribed
                            + " skippedErased=" + skippedErased
                            + " skippedOther=" + skippedOther
                            + " invalidEmails=" + invalidEmails
                            + " rowsExplicit=" + rowsExplicit
                            + " rowsNoBasis=" + rowsNoBasis
                            + " rowsUnsubscribed=" + rowsUnsubscribed
                            + " rowsCapped=" + rowsCapped
                            + " attestationVersion=" + version);
        }

        return new ImportResultResponse(total, imported, updated, suppressed,
                skippedUnsubscribed, skippedErased, skippedOther, invalidEmails, errors,
                rowsExplicit, rowsNoBasis, rowsUnsubscribed, rowsCapped, importId);
    }

    private static boolean isMember(Classification c) {
        return c == Classification.IMPORTED || c == Classification.UPDATED;
    }

    /**
     * Classify one contact against current DB state and, unless {@code dryRun}, apply the write.
     */
    private RowResult process(UUID orgId, String email, CsvContactParser.RawContact row, boolean dryRun,
                              AuthPrincipal principal, String attestationVersion, UUID importId,
                              ImportValidator validator) {
        // Erased addresses are skipped before any write, including a preview; only this org's
        // erasures are labelled as such, a platform-wide one lands in the neutral bucket.
        if (erasedAddressRepo.existsForOrg(orgId, email)) {
            return new RowResult(Classification.SKIPPED_ERASED, null);
        }
        if (erasedAddressRepo.existsPlatformWide(email)) {
            return new RowResult(Classification.SKIPPED_OTHER, null);
        }

        // ---- read current state (before any write) ----
        Consumer consumer = consumerRepo.findByNormalizedEmail(email).orElse(null);
        Membership existing = (consumer == null) ? null
                : membershipRepo.findByOrgIdAndConsumerId(orgId, consumer.getConsumerId()).orElse(null);
        boolean wasExisting = existing != null;
        String priorStatus = wasExisting ? existing.getConsentStatus() : "never";

        boolean deliverabilityBlocked = suppressionService.isDeliverabilityBlocked(email);
        boolean marketingBlocked = wasExisting
                && suppressionRepo.findMarketingByOrgAndMembership(orgId, existing.getMembershipId()).isPresent();
        boolean suppressedContact = deliverabilityBlocked || marketingBlocked;

        // ---- classify (suppression is absolute, then explicit unsubscribe) ----
        Classification c;
        if (suppressedContact) {
            c = Classification.SUPPRESSED;
        } else if ("unsubscribed".equals(priorStatus)) {
            c = Classification.SKIPPED_UNSUBSCRIBED;
        } else if (wasExisting) {
            c = Classification.UPDATED;
        } else {
            c = Classification.IMPORTED;
        }
        // Only rows that can still be subscribed count towards the per-import cap.
        ImportValidator.Decision d = isMember(c) ? validator.decide(row) : validator.inspect(row);

        if (dryRun) {
            return new RowResult(c, d); // preview: no writes at all
        }

        // ---- apply ----
        String phoneE164 = row.rawPhone() == null ? null
                : PhoneNormalizer.normalize(row.rawPhone()).orElse(null);

        // Upsert Consumer + Membership (idempotent). emailOptIn=false / smsOptIn=false so the
        // projector NEVER captures consent here; the row's own provenance decides below.
        projector.upsertMembership(orgId, email, row.name(), phoneE164, false, false, null);
        Membership m = requireMembership(orgId, email);

        if (isMember(c)) {
            switch (d.outcome()) {
                case EXPLICIT -> provenanceWriter.recordExplicit(orgId, m.getMembershipId(), importId,
                        row.rowNumber(), d, proofText(principal, attestationVersion, importId, row, d),
                        attestationVersion, principal);
                case UNSUBSCRIBE -> provenanceWriter.recordUnsubscribed(orgId, m.getMembershipId(),
                        importId, row.rowNumber(), d, principal);
                case NO_BASIS -> provenanceWriter.recordRejected(importId, m.getMembershipId(),
                        row.rowNumber(), d, d.reason());
            }
        } else {
            // SUPPRESSED / SKIPPED_UNSUBSCRIBED: consent is left untouched — the guardrail.
            provenanceWriter.recordRejected(importId, m.getMembershipId(), row.rowNumber(), d,
                    c == Classification.SUPPRESSED ? "suppressed" : "previously_unsubscribed");
        }
        return new RowResult(c, d);
    }

    /**
     * Proof text records WHO imported the row, WHEN, the row's own evidence, and WHAT the
     * organizer attested to in which revision, so later copy edits cannot rewrite it.
     */
    private String proofText(AuthPrincipal principal, String attestationVersion, UUID importId,
                             CsvContactParser.RawContact row, ImportValidator.Decision d) {
        return "Per-row consent proof from CSV import " + importId + " row " + row.rowNumber()
                + ": marketing_status=opted_in, source_platform=" + d.sourcePlatform()
                + ", export_date=" + d.exportDate() + ", proof_ref=" + d.proofRef()
                + ". Attestation (version=" + attestationVersion + "): " + ImportAttestation.STATEMENT
                + " Imported by user " + principal.userId() + " at " + Instant.now(clock);
    }

    private static String clip(String raw, int max) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private Membership requireMembership(java.util.UUID orgId, String email) {
        Consumer consumer = consumerRepo.findByNormalizedEmail(email)
                .orElseThrow(() -> new IllegalStateException("Consumer missing after upsert: " + email));
        return membershipRepo.findByOrgIdAndConsumerId(orgId, consumer.getConsumerId())
                .orElseThrow(() -> new IllegalStateException("Membership missing after upsert: " + email));
    }

    private static void addError(List<ImportError> errors, int row, String email, String reason) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(new ImportError(row, email, reason));
        }
    }
}
