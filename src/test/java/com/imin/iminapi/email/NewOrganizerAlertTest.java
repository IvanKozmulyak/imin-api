package com.imin.iminapi.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.org.InviteRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.oauth.OAuthAccountService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.auth.verification.EmailVerificationService;
import com.imin.iminapi.service.org.TeamService;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The team hears about an organizer once, when their sign-up becomes real, and never through a sign-up failure. */
@IminIntegrationTest
class NewOrganizerAlertTest {

    private static final String PASSWORD = "lovelace12345";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired UserRepository users;
    @Autowired EmailVerificationService verification;
    @Autowired OAuthAccountService accounts;
    @Autowired TeamService teamService;
    @Autowired TeamAlertService teamAlerts;
    @Autowired TeamAlertProperties alertProps;
    @Autowired PropertyFlips flips;
    @Autowired RecordingEmailService mail;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager txManager;
    @Autowired @Qualifier("ticketEmailExecutor") Executor ticketEmailExecutor;

    private final ObjectMapper om = new ObjectMapper();

    private ResultActions postJson(String path, Map<String, ?> body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private void signUp(String email, String orgName) throws Exception {
        postJson("/api/v1/auth/signup", Map.of("email", email, "password", PASSWORD, "firstName", "Ada",
                "lastName", "Lovelace", "orgName", orgName, "country", "GB")).andExpect(status().isOk());
    }

    private ResultActions verifyEmail(String email, String code) throws Exception {
        return postJson("/api/v1/auth/verify-email", Map.of("email", email, "code", code));
    }

    private static OAuthUserInfo google(String email) {
        return new OAuthUserInfo("google", "sub-" + UUID.randomUUID(), email, true, "Ada", "Lovelace", "Ada Lovelace");
    }

    /** The six digits of the newest mail to {@code email}, i.e. the verification code the sign-up sent. */
    private String codeSentTo(String email) {
        List<RecordingEmailService.SentEmail> sent = mail.sent().stream()
                .filter(m -> email.equalsIgnoreCase(m.to())).toList();
        Matcher digits = SIX_DIGITS.matcher(sent.get(sent.size() - 1).text());
        assertThat(digits.find()).as("the verification mail carries a six-digit code").isTrue();
        return digits.group(1);
    }

    /** Waits for the async send, then every mail to {@code address}. */
    private List<RecordingEmailService.SentEmail> mailTo(String address) {
        AsyncDrain.drain(ticketEmailExecutor);
        return mail.sent().stream().filter(m -> address.equalsIgnoreCase(m.to())).toList();
    }

    @Test
    void a_verified_email_signup_alerts_the_team_once_with_the_signup_details() throws Exception {
        String inbox = fx.email("team");
        flips.set(alertProps, "emails", List.of(inbox));
        // 22:30Z is already the next day in Paris.
        clock.setInstant(Instant.parse("2026-10-16T22:30:00Z"));
        String email = fx.email("ada");

        signUp(email, "Ada <b>Co</b>\n& Sons");
        assertThat(mailTo(inbox)).as("nothing until the address is verified").isEmpty();
        // Older than the alert and on another Paris day, so the "Signed up" row can only come from the user.
        User signedUp = users.findByEmailLower(email.toLowerCase()).orElseThrow();
        signedUp.setCreatedAt(Instant.parse("2026-10-10T22:45:00Z"));
        users.save(signedUp);

        verifyEmail(email, codeSentTo(email)).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());

        assertThat(mailTo(inbox)).singleElement().satisfies(alert -> {
            // The org name carries markup and a line break: text stays on one line, HTML is escaped.
            assertThat(alert.subject()).isEqualTo("New organizer: Ada <b>Co</b> & Sons (GB)");
            assertThat(alert.html())
                    .contains("Ada Lovelace", email, "Ada &lt;b&gt;Co&lt;/b&gt; &amp; Sons", "GB", "Email and password",
                            "2026-10-11 00:45 Europe/Paris", "2026-10-17 00:30 Europe/Paris")
                    .doesNotContain("<b>Co</b>");
            assertThat(alert.text())
                    .contains("Ada Lovelace", email, "Ada <b>Co</b> & Sons", "GB", "Email and password",
                            "Signed up:      2026-10-11 00:45 Europe/Paris", "Time:           2026-10-17 00:30 Europe/Paris");
        });
        // A slow Resend must hold neither the sign-up's transaction nor its thread.
        List<RecordingEmailService.SentEmail> sent = mail.sent();
        int at = IntStream.range(0, sent.size()).filter(i -> inbox.equalsIgnoreCase(sent.get(i).to())).findFirst().orElseThrow();
        assertThat(mail.sentInTransaction(at)).as("sent after the commit, outside the transaction").isFalse();
        assertThat(mail.sentOnThread(at)).as("and off the request thread").isNotSameAs(Thread.currentThread());
    }

    @Test
    void an_owner_first_verified_by_linking_google_alerts_once_and_the_old_code_adds_nothing() throws Exception {
        String inbox = fx.email("team");
        flips.set(alertProps, "emails", List.of(inbox));
        String email = fx.email("ada");
        signUp(email, "Ada Co");
        String code = codeSentTo(email);

        accounts.resolve(google(email));

        assertThat(mailTo(inbox)).singleElement().satisfies(alert -> {
            assertThat(alert.subject()).isEqualTo("New organizer: Ada Co (GB)");
            assertThat(alert.text()).contains("Ada Lovelace", email, "Email and password (verified with Google)");
        });
        // The emailed code is still live; entering it now must not announce the same organizer again.
        verifyEmail(email, code).andExpect(status().isOk());
        assertThat(mailTo(inbox)).hasSize(1);
    }

    private enum Via { VERIFY_EMAIL, GOOGLE_LINK }

    private enum Account { INVITED_TEAMMATE, OWNER_VERIFIED_BEFORE }

    static Stream<Arguments> accountsThatAreNotNewOrganizers() {
        return Stream.of(Via.values()).flatMap(via -> Stream.of(Account.values()).map(kind -> Arguments.of(via, kind)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("accountsThatAreNotNewOrganizers")
    void a_verification_that_creates_no_organizer_alerts_nobody(Via via, Account kind) throws Exception {
        String inbox = fx.email("team");
        flips.set(alertProps, "emails", List.of(inbox));
        User owner = fx.owner(fx.org());
        User account = switch (kind) {
            case INVITED_TEAMMATE -> invited(owner);
            case OWNER_VERIFIED_BEFORE -> {
                owner.setVerifiedAt(clock.instant());
                yield users.save(owner);
            }
        };

        switch (via) {
            case VERIFY_EMAIL -> verifyEmail(account.getEmail(), verification.issueCode(account)).andExpect(status().isOk());
            case GOOGLE_LINK -> accounts.resolve(google(account.getEmail()));
        }

        assertThat(mailTo(inbox)).isEmpty();
    }

    private User invited(User owner) {
        String email = fx.email("invitee");
        teamService.invite(fx.principal(owner), new InviteRequest(email, "member"));
        return users.findByEmailLower(email.toLowerCase()).orElseThrow();
    }

    @Test
    void a_failing_send_never_reaches_the_caller_nor_skips_the_other_inboxes() {
        String broken = fx.email("broken");
        String healthy = fx.email("team");
        flips.set(alertProps, "emails", List.of(broken, healthy));
        Organization org = fx.org();
        User owner = fx.owner(org);
        mail.failNextSendWith(new IllegalStateException("resend is down"));

        new TransactionTemplate(txManager).executeWithoutResult(status ->
                teamAlerts.newOrganizer(owner, org, TeamAlertService.EMAIL_PASSWORD));

        assertThat(mailTo(broken)).isEmpty();
        assertThat(mailTo(healthy)).hasSize(1);
    }

    private enum Transaction { ROLLED_BACK, NONE }

    /** Rolled back, the account never existed; with no transaction at all Spring would drop the event unless told not to. */
    @ParameterizedTest
    @EnumSource(Transaction.class)
    void the_alert_follows_the_transaction_it_was_raised_in(Transaction transaction) {
        String inbox = fx.email("team");
        flips.set(alertProps, "emails", List.of(inbox));
        Organization org = fx.org();
        User owner = fx.owner(org);

        switch (transaction) {
            case ROLLED_BACK -> assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
                teamAlerts.newOrganizer(owner, org, TeamAlertService.EMAIL_PASSWORD);
                throw new IllegalStateException("verification failed after the alert was raised");
            })).isInstanceOf(IllegalStateException.class);
            case NONE -> teamAlerts.newOrganizer(owner, org, TeamAlertService.EMAIL_PASSWORD);
        }

        assertThat(mailTo(inbox)).hasSize(transaction == Transaction.NONE ? 1 : 0);
    }
}
