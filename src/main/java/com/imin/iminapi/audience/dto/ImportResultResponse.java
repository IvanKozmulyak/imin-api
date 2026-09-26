package com.imin.iminapi.audience.dto;

import java.util.List;

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
 * @param total               data rows in the file (header row excluded)
 * @param imported            NEW memberships created and subscribed (basis=explicit, source=organizer_import)
 * @param updated             EXISTING memberships re-confirmed to subscribed
 * @param suppressed          contacts on the org marketing OR global deliverability suppression
 *                            list — imported/kept as members but NOT subscribed (guardrail)
 * @param skippedUnsubscribed existing members with an explicit unsubscribe — never re-subscribed
 * @param skippedErased       addresses on this org's erasure ledger — no write at all, and
 *                            deliberately no per-row error naming them
 * @param skippedOther        addresses skipped for a reason not disclosed to the organizer (an
 *                            erasure made outside this org) — no write, no per-row error
 * @param invalidEmails       rows whose email was blank or failed validation
 * @param errors              up to ~50 row-level problems for the organizer to fix
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
        List<ImportError> errors) {

    /** A single row-level problem. {@code row} is the 1-based file line number. */
    public record ImportError(int row, String email, String reason) {}
}
