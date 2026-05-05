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

    private User userWith(String addr, String name) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(addr);
        u.setName(name == null ? "" : name);
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
    void sends_welcome_email_without_name_when_blank() {
        sut.sendWelcome(userWith("ada@example.com", ""));

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.html()).doesNotContain("{{");
        // Template is "Welcome to imin{{name}}"; name empty becomes "Welcome to imin"
        assertThat(s.html()).contains("Welcome to imin");
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
