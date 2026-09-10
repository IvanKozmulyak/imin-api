package com.imin.iminapi.service.auth;

/**
 * The terms version an organizer is recorded as having accepted at signup.
 *
 * <h2>Server-canonical, like the buyer side</h2>
 *
 * <p>{@code SignupRequest.termsVersion} is accepted so the dashboard can send
 * what it displayed, and is deliberately <b>not</b> written: a client-supplied
 * string that becomes an audit fact is a record that says whatever the client
 * says. The same argument as {@code BuyerTerms}, and the same resolution.
 *
 * <h2>Why acceptance is not required</h2>
 *
 * <p>The dashboard has no terms or privacy pages to link to yet, so a mandatory
 * unticked checkbox would gate signup on a link that 404s — worse than no
 * checkbox, because it manufactures an acceptance of nothing. The column is here
 * so the acceptance can be recorded the moment the dashboard is ready to ask for
 * it; enforcing it, and re-accepting on a version bump, is the follow-up.
 */
public final class OrganizerTerms {

    private OrganizerTerms() {}

    /** Bump when the organizer terms change. */
    public static final String CURRENT_VERSION = "2026-09-08";
}
