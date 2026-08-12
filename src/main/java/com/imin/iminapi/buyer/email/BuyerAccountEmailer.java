package com.imin.iminapi.buyer.email;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The transactional mails the buyer credential and address flows send.
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
     * "Someone added an address to your imin account" — sent to the account's
     * <b>primary</b> address whenever {@code POST /buyer/emails} attaches one
     * (§2.3 rule 7). This is the only signal a buyer has that their account is
     * being extended without them, which is why it goes to the address they
     * already control rather than to the one being added.
     *
     * <p>The copy must hold on <b>both</b> branches of {@code addAddress}: the
     * one where a code was mailed to the new address and the one where it was
     * silently skipped because that address is already verified elsewhere. So it
     * says the address is pending confirmation and never claims a code was sent
     * — otherwise the notice itself would tell the caller, in their own inbox,
     * which branch they hit, and the neutral HTTP response would be pointless.
     */
    public void sendAddressAdded(String to, String locale, String addedAddress) {
        var r = renderer.render("buyer-address-added", locale, Map.of(
                "address", addedAddress,
                "profileUrl", profileUrl()));
        email.send(to, EmailLocale.choose(locale,
                "An email address was added to your imin account",
                "Se añadió una dirección de correo a tu cuenta de imin",
                "Une adresse e-mail a été ajoutée à votre compte imin",
                "До вашого акаунта imin додано адресу електронної пошти"), r.html(), r.text());
    }

    /**
     * "Your primary address changed" — sent to <b>both</b> the old and the new
     * primary (§2.3 rule 7). The old address is the one that matters: it is
     * where password resets used to go, so if the change was not the buyer's
     * doing, that inbox is the only place they will still hear about it.
     */
    public void sendPrimaryAddressChanged(String to, String locale, String newPrimary) {
        var r = renderer.render("buyer-primary-changed", locale, Map.of(
                "address", newPrimary,
                "signInUrl", signInUrl()));
        email.send(to, EmailLocale.choose(locale,
                "Your imin primary email address changed",
                "Tu dirección de correo principal de imin ha cambiado",
                "Votre adresse e-mail principale imin a changé",
                "Основну адресу електронної пошти imin змінено"), r.html(), r.text());
    }

    /** Profile screen on the buyer site, where addresses are managed. */
    public String profileUrl() {
        return props.getBuyerSiteBaseUrl() + "/profile/emails";
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
