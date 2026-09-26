package com.imin.iminapi.audience.service;

/**
 * What an organizer is asserting when they tick the CSV-import attestation box,
 * and which revision of that assertion they were shown.
 *
 * <p>The attestation no longer makes any row explicit: consent comes only from each
 * row's own provenance ({@code marketing_status=opted_in} with a proof reference, a
 * source platform and an export date). What the organizer attests to is that this
 * per-row evidence is genuine. The statement and its version are frozen into each
 * explicit row's proof text, and the version is stored as the row's
 * {@code consent_records.text_version}, so a later edit to the dashboard copy cannot
 * rewrite what a past importer is on file as having claimed.
 */
public final class ImportAttestation {

    private ImportAttestation() {}

    /** Recorded when the client sends no version — an older dashboard, not a guess. */
    public static final String UNVERSIONED = "unversioned";

    /** Bump when the substance of what the organizer is asserting changes. */
    public static final String CURRENT_VERSION = "2026-09-27";

    /** The assertion the attestation flag stands for. */
    public static final String STATEMENT =
            "The organizer attested that every row marked opted_in carries that person's own proof "
            + "of explicit consent to be contacted by email about their events, that the list was "
            + "not bought or rented, and that they can evidence each row on request.";

    /** {@code attestationVersion} as sent by the client, or {@link #UNVERSIONED}. */
    public static String version(String supplied) {
        if (supplied == null || supplied.isBlank()) return UNVERSIONED;
        String trimmed = supplied.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }
}
