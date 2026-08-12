package com.imin.iminapi.buyer.email;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The four transactional mails the buyer credential flows send.
 *
 * <p>Deliberately separate from {@code AccountEmailService}, which takes a
 * {@code User} — an organizer entity welded to an {@code Organization}. A buyer
 * has neither, so these take a bare address plus a locale.
 *
 * <p>Every link points at {@code imin.email.buyer-site-base-url}
 * ({@code https://app.imin.wtf}), never at {@code app-base-url}, which is the
 * organizer dashboard. Sending a buyer a dashboard link is a 404 at best and a
 * confusing sign-in page at worst.
 *
 * <p>Locale is the buyer's stored {@code buyer_accounts.locale}; a null or
 * unsupported value falls back to English inside
 * {@link EmailTemplateRenderer} and {@link EmailLocale}, so a missing preference
 * can never fail a send.
 */
@Service
public class BuyerAccountEmailer {

    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties props;

    public BuyerAccountEmailer(EmailService email, EmailTemplateRenderer renderer, EmailProperties props) {
        this.email = email;
        this.renderer = renderer;
        this.props = props;
    }

    /** Sign-in page on the buyer site. */
    public String signInUrl() {
        return props.getBuyerSiteBaseUrl() + "/auth/login";
    }

    /** Reset-password page on the buyer site, carrying the one-shot token. */
    public String resetUrl(String rawToken) {
        return props.getBuyerSiteBaseUrl() + "/auth/reset-password?token=" + rawToken;
    }

    /**
     * The six-digit address-verification code.
     *
     * <p>The copy is phishing-aware by requirement (§2.2): the mail goes to the
     * address being claimed, so an attacker cannot complete the flow for an
     * address they do not control — but they <i>can</i> cause the mail to be
     * sent. It therefore says who asked, what happens if it wasn't you, and that
     * imin will never ask for the code.
     */
    public void sendVerificationCode(String to, String locale, String code, int expiresInMinutes) {
        var r = renderer.render("buyer-verification-code", locale, Map.of(
                "code", code,
                "expiresInMinutes", String.valueOf(expiresInMinutes)));
        email.send(to, EmailLocale.choose(locale,
                "Your imin code: " + code,
                "Tu código de imin: " + code,
                "Votre code imin : " + code,
                "Ваш код imin: " + code), r.html(), r.text());
    }

    /**
     * Sent when someone tries to sign up with an address that is already
     * <b>verified</b> on an account.
     *
     * <p>Note the branch condition — verified, not merely present (§15 C-2).
     * Branching on "exists" would re-open the squatting lockout that
     * verified-scoped uniqueness exists to close: an attacker signs up as
     * {@code victim@x.com} and never verifies, and the victim's real signup then
     * gets this mail about an account that is not theirs, with no diagnosable
     * error because every response here is deliberately neutral.
     */
    public void sendAccountExistsNotice(String to, String locale) {
        var r = renderer.render("buyer-account-exists", locale, Map.of("signInUrl", signInUrl()));
        email.send(to, EmailLocale.choose(locale,
                "You already have an imin account",
                "Ya tienes una cuenta de imin",
                "Vous avez déjà un compte imin",
                "У вас уже є акаунт imin"), r.html(), r.text());
    }

    /**
     * The password-reset link. Reuses the organizer {@code password-reset}
     * template, which is audience-neutral — it names no dashboard, and its only
     * placeholders are the URL and the TTL.
     */
    public void sendPasswordReset(String to, String locale, String resetUrl, int expiresInMinutes) {
        var r = renderer.render("password-reset", locale, Map.of(
                "resetUrl", resetUrl,
                "expiresInMinutes", String.valueOf(expiresInMinutes)));
        email.send(to, EmailLocale.choose(locale,
                "Reset your password",
                "Restablece tu contraseña",
                "Réinitialisez votre mot de passe",
                "Скиньте свій пароль"), r.html(), r.text());
    }

    /**
     * Post-reset security notice. <b>Not</b> the organizer
     * {@code password-changed} template: that one promises "this device stays
     * signed in", which is true for {@code AuthService.changePassword} (it
     * reissues the acting session) and false here — a buyer password reset
     * revokes every session, including the one that asked, and the buyer signs
     * in again with the new password.
     */
    public void sendPasswordChanged(String to, String locale) {
        var r = renderer.render("buyer-password-changed", locale, Map.of("signInUrl", signInUrl()));
        email.send(to, EmailLocale.choose(locale,
                "Your password was changed",
                "Tu contraseña se cambió",
                "Votre mot de passe a été modifié",
                "Ваш пароль змінено"), r.html(), r.text());
    }
}
