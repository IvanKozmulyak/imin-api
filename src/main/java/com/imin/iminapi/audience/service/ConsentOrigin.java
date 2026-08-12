package com.imin.iminapi.audience.service;

/**
 * Who is really acting when {@link ConsentService#unsubscribe} is called.
 *
 * <p><b>Why this is a parameter and not something inferred from {@code source}.</b>
 * The sticky {@code marketing_optouts} record (buyer-accounts epic §6.3) must be
 * written for a decision the person made, and never for an organizer tidying their
 * own list. Deriving that from the {@code source} string fails both ways:
 *
 * <ul>
 *   <li>An <b>allow-list</b> over {@code source} silently drops the legally mandated
 *       SMS STOP path — {@code SmsStopService} passes the inbound webhook's source
 *       through — and fails open on any source added later.</li>
 *   <li>A <b>deny-list</b> lets an organizer POST an arbitrary string to
 *       {@code /audience/consent/unsubscribe} ({@code AudienceController}, where
 *       {@code source} is unvalidated request-body text) and write a sticky row that
 *       permanently poisons that buyer's re-subscribe path — on a record that is
 *       address-keyed and therefore outlives the account.</li>
 * </ul>
 *
 * <p>Two properties a required enum parameter buys that a string cannot:
 * <ol>
 *   <li><b>A new call site cannot forget.</b> Every caller, including ones written
 *       next year, must decide, and the build fails until they do. A config list or
 *       a {@code switch} over {@code source} fails open by default; this fails closed
 *       by construction.</li>
 *   <li><b>An organizer can no longer write a sticky row at all</b>, whatever they
 *       put in {@code source}, because they do not choose the origin — the endpoint
 *       does.</li>
 * </ol>
 *
 * <p>{@code source} is unchanged by this: it remains the human-readable audit string
 * on {@code consent_records}, and it stops being load-bearing.
 *
 * @see ConsentService#unsubscribe(java.util.UUID, java.util.UUID, String, String, ConsentOrigin, com.imin.iminapi.security.AuthPrincipal)
 */
public enum ConsentOrigin {

    /**
     * The person themselves: footer link, RFC 8058 one-click, SMS STOP, or a DSAR
     * objection recorded on their behalf. Writes a sticky {@code marketing_optouts}
     * row.
     *
     * <p>A DSAR objection is {@code DATA_SUBJECT} deliberately: an Art. 21 objection
     * is the buyer's decision even though an operator keyed it in, and it is exactly
     * the record that must never be reversed by a preference-centre toggle.
     */
    DATA_SUBJECT,

    /**
     * An operator removing someone from their own list. Writes no sticky row — an
     * organizer tidying their audience must not bar that buyer from ever opting
     * back in.
     */
    OPERATOR
}
