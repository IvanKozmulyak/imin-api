package com.imin.iminapi.buyer;

import com.imin.iminapi.email.EmailLocale;

/**
 * The terms version and wording a buyer is recorded as having accepted.
 *
 * <h2>Why the version is not taken from the request</h2>
 *
 * <p>{@code termsVersion} arrived as a client-supplied string. Whatever the
 * browser sent was written into {@code buyer_accounts.terms_version} and then
 * treated as evidence — so the record said whatever the client said, which is
 * the one property a consent record must not have. A client can be old, wrong,
 * or hostile, and none of those should be able to author an audit fact. The
 * field is still accepted on the wire (removing it would break the buyer site)
 * and is now ignored.
 *
 * <p>The wording is stored alongside, because a version string is only evidence
 * if the text it names can be produced later. Keeping both in one constant is
 * what makes that true: bumping {@link #CURRENT_VERSION} without changing the
 * text here, or the reverse, is a visible edit in one file.
 */
public final class BuyerTerms {

    private BuyerTerms() {}

    /**
     * Server-canonical. Bump this <b>and</b> the wording below together when the
     * terms change; existing acceptances keep the version they were taken under,
     * because {@code completeOnboarding} stamps once and never rewrites.
     */
    public static final String CURRENT_VERSION = "2026-09-08";

    /**
     * The sentence shown next to the mandatory checkbox on the finish-registration
     * step, in the language the buyer read it in. Same contract as
     * {@code BuyerPreferencesService.proofText}: these strings mirror
     * {@code onboarding.terms.label} in {@code imin-public/lib/i18n/{en,es,fr,uk}.ts};
     * if you edit the screen, edit these.
     */
    public static String acceptanceText(String locale) {
        return EmailLocale.choose(locale,
                "I accept the imin terms of use and privacy policy.",
                "Acepto las condiciones de uso y la política de privacidad de imin.",
                "J’accepte les conditions d’utilisation et la politique de confidentialité d’imin.",
                "Я приймаю умови користування та політику конфіденційності imin.");
    }
}
