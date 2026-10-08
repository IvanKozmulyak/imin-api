package com.imin.iminapi.email;

import com.imin.iminapi.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    // Neither flow has another test that renders the template: AuthServiceTest mocks this service.
    @Test
    void auth_emails_carry_the_code_and_the_reset_link() {
        String link = "https://app.imin/reset-password?token=abc123";

        sut.sendVerificationCode(userWith("ada@example.com", "Ada"), "1234", 10);
        sut.sendPasswordReset(userWith("ada@example.com", "Ada"), link, 30);

        RecordingEmailService.SentEmail code = email.sent().get(0);
        assertThat(code.to()).isEqualTo("ada@example.com");
        assertThat(code.html()).contains("1234");
        assertThat(code.text()).contains("1234");
        assertThat(code.html()).contains("10");
        assertThat(code.text()).contains("10");
        RecordingEmailService.SentEmail reset = email.sent().get(1);
        assertThat(reset.to()).isEqualTo("ada@example.com");
        assertThat(reset.html()).contains(link);
        assertThat(reset.text()).contains(link);
        assertThat(reset.html()).contains("30");
    }

    @Test
    void password_changed_notice_goes_to_the_account_owner() {
        sut.sendPasswordChangedNotification(userWith("ada@example.com", "Ada"));

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.to()).isEqualTo("ada@example.com");
        assertThat(s.subject()).isEqualTo("Your password was changed");
        assertThat(s.html()).doesNotContain("{{");
    }

    @ParameterizedTest
    @CsvSource(value = {"Ada, Ada", "'', there"})
    void welcome_greets_by_first_name_or_falls_back_to_there(String firstName, String greeted) {
        sut.sendWelcome(userWith("ada@example.com", firstName));

        RecordingEmailService.SentEmail s = email.lastSent();
        assertThat(s.html()).doesNotContain("{{");
        assertThat(s.html()).contains("Welcome, " + greeted + ".");
        assertThat(s.text()).contains("Welcome, " + greeted + ".");
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
}
