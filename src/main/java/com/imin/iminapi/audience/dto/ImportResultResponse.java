package com.imin.iminapi.audience.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of a POST /api/v1/audience/import (contact CSV upload).
 *
 * <p>Counts are honest and per-classification. For a non-dry-run:
 * {@code imported + updated + suppressed + skippedUnsubscribed + skippedErased + skippedOther}
 * equals the number of
 * unique, valid contacts that were processed (duplicates within the file are collapsed
 * last-wins and never double-counted). {@code invalidEmails} counts rows whose email
 * failed validation. {@code total} is the number of data rows in the file (header
 * excluded), so {@code total} minus the sum above equals the number of in-file
 * duplicates dropped.
 *
 * <p>Consent split of the rows that landed as members:
 * {@code rowsExplicit + rowsNoBasis + rowsUnsubscribed == imported + updated}.
 *
 * @param total               data rows in the file (header row excluded)
 * @param imported            NEW memberships created
 * @param updated             EXISTING memberships the file matched
 * @param suppressed          contacts on the org marketing OR global deliverability suppression
 *                            list — kept as members but NOT subscribed (guardrail)
 * @param skippedUnsubscribed existing members with an explicit unsubscribe — never re-subscribed
 * @param skippedErased       addresses on this org's erasure ledger — no write at all, and
 *                            deliberately no per-row error naming them
 * @param skippedOther        addresses skipped for a reason not disclosed to the organizer (an
 *                            erasure made outside this org) — no write, no per-row error
 * @param invalidEmails       rows whose email was blank or failed validation
 * @param errors              up to ~50 row-level problems for the organizer to fix
 * @param rowsExplicit        rows subscribed as explicit consent on their own proof
 *                            ({@code marketing_status=opted_in} + proof_ref + source_platform + export_date)
 * @param rowsNoBasis         rows kept as members without a consent basis — not mailable
 * @param rowsUnsubscribed    rows the file marks {@code unsubscribed} — put on the marketing suppression list
 * @param rowsCapped          the part of {@code rowsNoBasis} held back by the per-import cap on
 *                            subscribed rows (lifted by an import-level proof reference)
 * @param importId            the stored import record; null for a preview
 */
public record ImportResultResponse(
        int total,
        int imported,
        int updated,
        int suppressed,
        int skippedUnsubscribed,
        int skippedErased,
        int skippedOther,
        int invalidEmails,
        List<ImportError> errors,
        int rowsExplicit,
        int rowsNoBasis,
        int rowsUnsubscribed,
        int rowsCapped,
        UUID importId) {

    /** A single row-level problem. {@code row} is the 1-based file line number. */
    public record ImportError(int row, String email, String reason) {}
}
