package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.dto.ImportResultResponse;
import com.imin.iminapi.audience.dto.ImportResultResponse.ImportError;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.service.audience.PhoneNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Organizer-asserted contact import.
 *
 * <p>An organizer uploads a CSV of contacts they assert they have consent to email. Each
 * valid, non-suppressed, non-unsubscribed contact is upserted as a member and subscribed
 * with {@code consent_basis='explicit'} and {@code source='organizer_import'} — the audit
 * value that distinguishes an organizer assertion from a real double-opt-in.
 *
 * <p><b>Guardrails (non-negotiable):</b>
 * <ol>
 *   <li><b>Suppression is absolute.</b> A contact on the org's marketing suppression list OR
 *       the global deliverability suppression (hard bounce / complaint) is imported as a
 *       member but never subscribed — an organizer CSV cannot resurrect a suppressed
 *       contact.</li>
 *   <li><b>Explicit unsubscribes are never flipped.</b> A member who unsubscribed stays
 *       unsubscribed; the import counts them as {@code skippedUnsubscribed}.</li>
 * </ol>
 */
@Service
public class AudienceImportService {

    private static final Logger log = LoggerFactory.getLogger(AudienceImportService.class);

    /** Basic RFC-ish: non-empty local part, an @, a domain containing a dot, no whitespace. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final int MAX_ERRORS = 50;
    static final String SOURCE = "organizer_import";

    private final ConsumerRepository consumerRepo;
    private final MembershipRepository membershipRepo;
    private final SuppressionRepository suppressionRepo;
    private final SuppressionService suppressionService;
    private final AudienceOrderProjector projector;
    private final ConsentService consentService;
    private final AuditLogger auditLogger;

    public AudienceImportService(ConsumerRepository consumerRepo,
                                 MembershipRepository membershipRepo,
                                 SuppressionRepository suppressionRepo,
                                 SuppressionService suppressionService,
                                 AudienceOrderProjector projector,
                                 ConsentService consentService,
                                 AuditLogger auditLogger) {
        this.consumerRepo = consumerRepo;
        this.membershipRepo = membershipRepo;
        this.suppressionRepo = suppressionRepo;
        this.suppressionService = suppressionService;
        this.projector = projector;
        this.consentService = consentService;
        this.auditLogger = auditLogger;
    }

    private enum Classification { IMPORTED, UPDATED, SUPPRESSED, SKIPPED_UNSUBSCRIBED }

    /**
     * Import (or, when {@code dryRun}, preview) the parsed contacts.
     *
     * <p>Not {@code @Transactional} at this level: each contact is written through the
     * already-transactional {@link AudienceOrderProjector#upsertMembership} and
     * {@link ConsentService#capture}, so one bad row can never roll back the whole batch.
     */
    public ImportResultResponse importContacts(List<CsvContactParser.RawContact> rawRows,
                                               boolean dryRun,
                                               AuthPrincipal principal) {
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

        int imported = 0, updated = 0, suppressed = 0, skippedUnsubscribed = 0;

        // 2. Classify + (unless dryRun) apply each unique contact.
        for (Map.Entry<String, CsvContactParser.RawContact> e : unique.entrySet()) {
            String email = e.getKey();
            CsvContactParser.RawContact row = e.getValue();
            try {
                Classification c = process(principal.orgId(), email, row, dryRun, principal);
                switch (c) {
                    case IMPORTED -> imported++;
                    case UPDATED -> updated++;
                    case SUPPRESSED -> suppressed++;
                    case SKIPPED_UNSUBSCRIBED -> skippedUnsubscribed++;
                }
            } catch (RuntimeException ex) {
                log.error("Import row {} ({}) failed: {}", row.rowNumber(), email, ex.getMessage(), ex);
                addError(errors, row.rowNumber(), email, "processing error");
            }
        }

        if (!dryRun) {
            auditLogger.record(principal, AuditActions.AUDIENCE_IMPORTED, "audience", null,
                    "CSV import: total=" + total + " imported=" + imported + " updated=" + updated
                            + " suppressed=" + suppressed + " skippedUnsubscribed=" + skippedUnsubscribed
                            + " invalidEmails=" + invalidEmails);
        }

        return new ImportResultResponse(total, imported, updated, suppressed,
                skippedUnsubscribed, invalidEmails, errors);
    }

    /**
     * Classify one contact against current DB state and, unless {@code dryRun}, apply the write.
     */
    private Classification process(java.util.UUID orgId, String email,
                                   CsvContactParser.RawContact row, boolean dryRun,
                                   AuthPrincipal principal) {
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

        if (dryRun) {
            return c; // preview: no writes at all
        }

        // ---- apply ----
        String phoneE164 = row.rawPhone() == null ? null
                : PhoneNormalizer.normalize(row.rawPhone()).orElse(null);

        // Upsert Consumer + Membership (idempotent). emailOptIn=false / smsOptIn=false so the
        // projector NEVER captures consent here — organizer-import consent is written below with
        // the distinct source, and the projector's checkout soft-opt-in path must not fire.
        projector.upsertMembership(orgId, email, row.name(), phoneE164, false, false, null);

        if (c == Classification.IMPORTED || c == Classification.UPDATED) {
            Membership m = requireMembership(orgId, email);
            consentService.capture(orgId, m.getMembershipId(), "explicit", SOURCE,
                    proofText(principal), "email", principal);
        }
        // SUPPRESSED / SKIPPED_UNSUBSCRIBED: member row exists (or was just created for a new
        // suppressed contact) but consent_status is left untouched — the guardrail.
        return c;
    }

    /** Proof text records WHO asserted the consent and WHEN, alongside source=organizer_import. */
    private static String proofText(AuthPrincipal principal) {
        return "Organizer-asserted consent via CSV import (attestation=true), imported by user "
                + principal.userId() + " at " + Instant.now();
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
