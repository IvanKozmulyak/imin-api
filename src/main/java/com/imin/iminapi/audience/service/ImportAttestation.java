package com.imin.iminapi.audience.service;

/**
 * What an organizer is asserting when they tick the CSV-import attestation box,
 * and which revision of that assertion they were shown.
 *
 * <h2>Why the wording is stored and not just the flag</h2>
 *
 * <p>{@code attestation=true} is the entire lawful basis for auto-subscribing a
 * list imin has never seen: the consent record says {@code explicit}, and the
 * send gate then treats it as real consent, on nothing but an organizer's word.
 * The proof text recorded who asserted it and when, but not <b>what they
 * asserted</b> — so a later edit to the dashboard's dialog copy would silently
 * rewrite what every past importer is on file as having claimed. Storing the
 * statement and its version freezes each assertion to the words that were on
 * screen when it was made.
 *
 * <p>{@link #STATEMENT} is the API's own statement of what the flag means, and
 * it is deliberately phrased in terms of the assertion rather than as a copy of
 * the dashboard's sentence: the dialog's exact wording lives in
 * {@code imin-webapp} and this class must not pretend to quote text it cannot
 * see. When the dashboard starts sending its own version string, that value is
 * recorded alongside — which is what makes the two reconcilable later.
 *
 * <p><b>Not changed here:</b> {@code consent_basis} stays {@code explicit} and
 * the send gate is untouched. Whether an organizer import should carry a
 * distinct basis and require re-permission before the first send is a product
 * decision, and it is the open half of this card.
 */
public final class ImportAttestation {

    private ImportAttestation() {}

    /** Recorded when the client sends no version — an older dashboard, not a guess. */
    public static final String UNVERSIONED = "unversioned";

    /** Bump when the substance of what the organizer is asserting changes. */
    public static final String CURRENT_VERSION = "2026-09-08";

    /** The assertion the attestation flag stands for. */
    public static final String STATEMENT =
            "The organizer attested that every contact in this file gave them consent to be "
            + "contacted by email about their events, and that they can evidence it on request.";

    /** {@code attestationVersion} as sent by the client, or {@link #UNVERSIONED}. */
    public static String version(String supplied) {
        if (supplied == null || supplied.isBlank()) return UNVERSIONED;
        String trimmed = supplied.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }
}
