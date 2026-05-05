package com.imin.iminapi.email;

import com.imin.iminapi.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class AccountEmailService {

    private static final String SUBJECT_VERIFICATION = "Your verification code";
    private static final String SUBJECT_WELCOME = "Welcome to imin";
    private static final String SUBJECT_PASSWORD_RESET = "Reset your password";
    private static final String SUBJECT_PASSWORD_CHANGED = "Your password was changed";

    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private String appBaseUrl = "http://localhost:3000";

    public AccountEmailService(EmailService email, EmailTemplateRenderer renderer) {
        this.email = email;
        this.renderer = renderer;
    }

    @Value("${imin.email.app-base-url:http://localhost:3000}")
    public void setAppBaseUrl(String appBaseUrl) { this.appBaseUrl = appBaseUrl; }

    public void sendVerificationCode(User user, String code, int expiresInMinutes) {
        Map<String, String> vars = Map.of(
                "code", code,
                "expiresInMinutes", String.valueOf(expiresInMinutes));
        EmailTemplateRenderer.Rendered r = renderer.render("verification-code", vars);
        email.send(user.getEmail(), SUBJECT_VERIFICATION, r.html(), r.text());
    }

    public void sendWelcome(User user) {
        // Template reads "Welcome to imin{{name}}". Caller-controlled comma+space prefix
        // gives "Welcome to imin, Ada" or "Welcome to imin" for blank names. Renderer
        // HTML-escapes the value, so a malicious name cannot inject HTML.
        String name = user.getName();
        String namePart = (name == null || name.isBlank()) ? "" : ", " + name;
        Map<String, String> vars = Map.of(
                "name", namePart,
                "appBaseUrl", appBaseUrl);
        EmailTemplateRenderer.Rendered r = renderer.render("welcome", vars);
        email.send(user.getEmail(), SUBJECT_WELCOME, r.html(), r.text());
    }

    public void sendPasswordReset(User user, String resetUrl, int expiresInMinutes) {
        Map<String, String> vars = Map.of(
                "resetUrl", resetUrl,
                "expiresInMinutes", String.valueOf(expiresInMinutes));
        EmailTemplateRenderer.Rendered r = renderer.render("password-reset", vars);
        email.send(user.getEmail(), SUBJECT_PASSWORD_RESET, r.html(), r.text());
    }

    public void sendPasswordChangedNotification(User user) {
        Map<String, String> vars = new HashMap<>();
        vars.put("appBaseUrl", appBaseUrl);
        EmailTemplateRenderer.Rendered r = renderer.render("password-changed", vars);
        email.send(user.getEmail(), SUBJECT_PASSWORD_CHANGED, r.html(), r.text());
    }
}
