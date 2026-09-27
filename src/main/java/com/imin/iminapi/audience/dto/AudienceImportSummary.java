package com.imin.iminapi.audience.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * One past contact import, as listed by {@code GET /api/v1/audience/imports}. Holds counts,
 * truncated file hashes, dates and the attestation version only: no address, no proof text,
 * no user id.
 *
 * @param id                     the import record
 * @param createdAt              when the import ran
 * @param originalFileHashPrefix first 12 hex characters of the SHA-256 of the organizer's own
 *                               file (computed in their browser), or null when not sent
 * @param uploadedFileHashPrefix first 12 hex characters of the SHA-256 of the bytes the API
 *                               received, or null
 * @param sourcePlatform         set only when every accepted row named the same platform
 * @param exportDate             set only when every accepted row named the same export date
 * @param proofRefPresent        whether an import-level evidence reference was given
 * @param attestationVersion     the attestation statement revision the dashboard displayed
 * @param rowsTotal              data rows in the file
 * @param rowsExplicit           rows subscribed on their own proof
 * @param rowsNoBasis            rows kept as members without a consent basis
 * @param rowsRejected           rows not subscribed (invalid, erased, suppressed, unsubscribed)
 * @param provenance             per-row provenance records still on file for this import
 */
public record AudienceImportSummary(
        UUID id,
        Instant createdAt,
        String originalFileHashPrefix,
        String uploadedFileHashPrefix,
        String sourcePlatform,
        LocalDate exportDate,
        boolean proofRefPresent,
        String attestationVersion,
        int rowsTotal,
        int rowsExplicit,
        int rowsNoBasis,
        int rowsRejected,
        ProvenanceSummary provenance) {

    /**
     * Counts of {@code import_row_provenance} rows for the import. Rows of a person erased
     * since the import are gone (membership cascade), so {@code rowsRecorded} can shrink.
     *
     * @param rowsRecorded        provenance rows still on file
     * @param rowsAccepted        of those, accepted as explicit consent
     * @param notAcceptedByReason of the rest, count per reject reason (e.g. {@code missing_proof})
     */
    public record ProvenanceSummary(int rowsRecorded, int rowsAccepted, Map<String, Integer> notAcceptedByReason) {}
}
