package com.imin.iminapi.email;

import com.imin.iminapi.model.User;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountEmailServiceTest {

    EmailTemplateRenderer renderer = new EmailTemplateRenderer();
    RecordingEmailService email = new RecordingEmailService();
    EmailProperties props = makeProps();
    AccountEmailService sut = new AccountEmailService(email, renderer, props);

    private static EmailProperties makeProps() {
        EmailProperties p = new EmailProperties();
        p.setAppBaseUrl("http://localhost:3000");
        return p;
    }

    private User userWith(String addr, String firstName) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(addr);
        u.setFirstName(firstName == null ? "" : firstName);
        u.setLastName("");
        return u;
    }

    @Test
    void sends_verification_code_email() {
        sut.sendVerificationCode(userWith("ada@example.com", "Ada"), "1234", 10);

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.to()).isEqualTo("ada@example.com");
        assertThat(s.subject()).isEqualTo("Your verification code");
        assertThat(s.html()).contains("1234").contains("10");
        assertThat(s.text()).contains("1234").contains("10");
    }

    @Test
    void sends_welcome_email_with_name_when_present() {
        sut.sendWelcome(userWith("ada@example.com", "Ada"));

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.to()).isEqualTo("ada@example.com");
        assertThat(s.subject()).isEqualTo("Welcome to imin");
        assertThat(s.html()).contains("Ada");
    }

    @Test
    void sends_welcome_email_with_friendly_fallback_when_first_name_blank() {
        sut.sendWelcome(userWith("ada@example.com", ""));

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.html()).doesNotContain("{{");
        // Template renders "Welcome, {{name}}." — falls back to "there" when first name is blank
        assertThat(s.html()).contains("Welcome, there.");
        assertThat(s.text()).contains("Welcome, there.");
    }

    @Test
    void sends_welcome_in_users_locale_when_set() {
        User fr = userWith("ada@example.com", "Ada");
        fr.setLocale("fr");
        User en = userWith("bob@example.com", "Bob"); // locale null → English

        sut.sendWelcome(fr);
        sut.sendWelcome(en);

        RecordingEmailService.SentEmail frSent = email.sent().get(0);
        RecordingEmailService.SentEmail enSent = email.sent().get(1);
        // Subject is chosen by locale…
        assertThat(frSent.subject()).isEqualTo("Bienvenue sur imin");
        assertThat(enSent.subject()).isEqualTo("Welcome to imin");
        // …and the French template body is used, not the English one.
        assertThat(frSent.html()).isNotEqualTo(enSent.html());
        assertThat(frSent.html()).doesNotContain("{{");
    }

    @Test
    void sends_password_reset_email() {
        sut.sendPasswordReset(
                userWith("ada@example.com", "Ada"),
                "https://app.imin/reset-password?token=abc123",
                30);

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.to()).isEqualTo("ada@example.com");
        assertThat(s.subject()).isEqualTo("Reset your password");
        assertThat(s.html()).contains("https://app.imin/reset-password?token=abc123").contains("30");
        assertThat(s.text()).contains("https://app.imin/reset-password?token=abc123");
    }

    @Test
    void sends_password_changed_notification() {
        sut.sendPasswordChangedNotification(userWith("ada@example.com", "Ada"));

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.to()).isEqualTo("ada@example.com");
        assertThat(s.subject()).isEqualTo("Your password was changed");
        assertThat(s.html()).doesNotContain("{{");
    }
}
