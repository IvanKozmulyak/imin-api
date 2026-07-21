package com.imin.iminapi.email;

import com.imin.iminapi.model.User;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AccountEmailService {

    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties props;

    public AccountEmailService(EmailService email, EmailTemplateRenderer renderer, EmailProperties props) {
        this.email = email;
        this.renderer = renderer;
        this.props = props;
    }

    public void sendVerificationCode(User user, String code, int expiresInMinutes) {
        String locale = user.getLocale();
        Map<String, String> vars = Map.of(
                "code", code,
                "expiresInMinutes", String.valueOf(expiresInMinutes));
        EmailTemplateRenderer.Rendered r = renderer.render("verification-code", locale, vars);
        String subject = EmailLocale.choose(locale,
                "Your verification code",
                "Tu código de verificación",
                "Votre code de vérification",
                "Ваш код підтвердження");
        email.send(user.getEmail(), subject, r.html(), r.text());
    }

    public void sendWelcome(User user) {
        // Template uses {{name}} raw (e.g. "Welcome, {{name}}."). We use the first name
        // for a friendly greeting; "there" fallback covers users without a first name
        // (e.g. invited team members who haven't completed signup). Renderer HTML-escapes
        // the value, so a malicious name cannot inject HTML.
        String locale = user.getLocale();
        String first = user.getFirstName();
        String displayName = (first == null || first.isBlank()) ? "there" : first;
        Map<String, String> vars = Map.of(
                "name", displayName,
                "appBaseUrl", props.getAppBaseUrl());
        EmailTemplateRenderer.Rendered r = renderer.render("welcome", locale, vars);
        String subject = EmailLocale.choose(locale,
                "Welcome to imin",
                "Te damos la bienvenida a imin",
                "Bienvenue sur imin",
                "Ласкаво просимо до imin");
        email.send(user.getEmail(), subject, r.html(), r.text());
    }

    public void sendPasswordReset(User user, String resetUrl, int expiresInMinutes) {
        String locale = user.getLocale();
        Map<String, String> vars = Map.of(
                "resetUrl", resetUrl,
                "expiresInMinutes", String.valueOf(expiresInMinutes));
        EmailTemplateRenderer.Rendered r = renderer.render("password-reset", locale, vars);
        String subject = EmailLocale.choose(locale,
                "Reset your password",
                "Restablece tu contraseña",
                "Réinitialisez votre mot de passe",
                "Скиньте свій пароль");
        email.send(user.getEmail(), subject, r.html(), r.text());
    }

    public void sendPasswordChangedNotification(User user) {
        String locale = user.getLocale();
        Map<String, String> vars = Map.of("appBaseUrl", props.getAppBaseUrl());
        EmailTemplateRenderer.Rendered r = renderer.render("password-changed", locale, vars);
        String subject = EmailLocale.choose(locale,
                "Your password was changed",
                "Tu contraseña se cambió",
                "Votre mot de passe a été modifié",
                "Ваш пароль змінено");
        email.send(user.getEmail(), subject, r.html(), r.text());
    }
}
