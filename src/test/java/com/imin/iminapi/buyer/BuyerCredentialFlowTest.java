package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerEmailVerificationCodeRepository;
import com.imin.iminapi.buyer.repository.BuyerSessionRepository;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for the buyer email + password credential flows (R1.2 of the
 * buyer-accounts epic).
 *
 * <p>Deliberately black-box through MockMvc: the six-digit code and the reset
 * token are read back out of the <b>rendered email</b> rather than out of the
 * database, so these tests also prove the code actually reaches the message the
 * buyer receives — a service-level test that reached into the repository would
 * pass with a broken template.
 *
 * <p>{@link EmailService} is mocked because the test profile carries a dummy
 * Resend key and a real send would attempt network.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerCredentialFlowTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");
    private static final Pattern RESET_TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired MockMvc mvc;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository emails;
    @Autowired BuyerSessionRepository sessions;
    @Autowired BuyerEmailVerificationCodeRepository codes;
    @MockitoBean EmailService email;

    private String address;

    @BeforeEach
    void freshAddress() {
        // uq_bae_verified_email is a real platform-wide UNIQUE and these tests
        // share one database, so every test needs its own address.
        address = "ada+" + UUID.randomUUID() + "@example.com";
        reset(email);
    }

    // ── Signup branches on VERIFIED, never on EXISTS (§15 C-2) ─────────────

    @Test
    void signup_to_an_unknown_address_creates_an_unverified_account_and_mails_a_code() throws Exception {
        signup(address).andExpect(status().isNoContent());

        BuyerAccountEmail row = onlyRowFor(address);
        assertThat(row.isVerified()).as("a fresh signup grants nothing until the code is redeemed").isFalse();
        assertThat(row.getAddedVia()).isEqualTo(BuyerAccountEmail.ADDED_VIA_SIGNUP);
        assertThat(codeSentTo(address)).isNotNull();
    }

    @Test
    void signup_to_an_address_claimed_but_unverified_elsewhere_creates_a_second_account_and_mails_a_code()
            throws Exception {
        // This is the squatting case §15 C-2 exists for. An attacker claims the
        // victim's address and never verifies it. The victim's real signup must
        // still create an account and still receive a code — branching on
        // "exists" here would mail them "sign in or reset your password" for an
        // account that is not theirs, with no diagnosable error.
        signup(address).andExpect(status().isNoContent());
        reset(email);

        signup(address).andExpect(status().isNoContent());

        assertThat(rowsFor(address))
                .as("unverified claims are deliberately NOT unique — that is what defuses the squat")
                .hasSize(2);
        assertThat(codeSentTo(address)).as("the real owner must get a code, not a notice").isNotNull();
    }

    @Test
    void signup_to_a_verified_address_creates_nothing_and_sends_the_neutral_notice() throws Exception {
        signupAndVerify(address);
        long accountsBefore = accounts.count();
        reset(email);

        signup(address).andExpect(status().isNoContent());

        assertThat(accounts.count()).isEqualTo(accountsBefore);
        assertThat(rowsFor(address)).hasSize(1);
        assertThat(subjectSentTo(address))
                .as("the notice, not a code")
                .isEqualTo("You already have an imin account");
        assertThat(codeSentTo(address)).isNull();
    }

    @Test
    void signup_answers_the_same_204_with_an_empty_body_on_every_branch() throws Exception {
        String verified = "ada+" + UUID.randomUUID() + "@example.com";
        signupAndVerify(verified);

        MvcResult unknown = signup(address).andExpect(status().isNoContent()).andReturn();
        MvcResult taken = signup(verified).andExpect(status().isNoContent()).andReturn();

        assertThat(unknown.getResponse().getContentAsString()).isEmpty();
        assertThat(taken.getResponse().getContentAsString())
                .as("identical status, identical body — the whole anti-enumeration contract")
                .isEqualTo(unknown.getResponse().getContentAsString());
    }

    @Test
    void signup_still_answers_204_when_the_mail_provider_is_down() throws Exception {
        doThrow(new RuntimeException("resend is down"))
                .when(email).send(anyString(), anyString(), anyString(), anyString());

        signup(address).andExpect(status().isNoContent());

        assertThat(rowsFor(address))
                .as("a send failure must not roll the signup back, or a Resend outage becomes an oracle")
                .hasSize(1);
    }

    // ── Verify ─────────────────────────────────────────────────────────────

    @Test
    void verifying_the_code_signs_the_buyer_in_and_the_cookie_works() throws Exception {
        signup(address).andExpect(status().isNoContent());
        String code = codeSentTo(address);

        MvcResult result = verifyEmail(address, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emails[0].verified").value(true))
                .andExpect(jsonPath("$.emails[0].primary").value(true))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andReturn();

        String raw = sessionCookieValue(result);
        assertThat(raw).isNotBlank();
        mvc.perform(get("/api/v1/buyer/me").cookie(new Cookie(BuyerSessionCookie.NAME, raw)))
                .andExpect(status().isOk());

        BuyerAccount account = accounts.findById(onlyRowFor(address).getBuyerAccountId()).orElseThrow();
        assertThat(account.getActivatedAt()).as("activated_at is stamped on first verification").isNotNull();
    }

    @Test
    void verifying_an_address_deletes_every_unverified_claim_on_it_elsewhere() throws Exception {
        // §2.3 rule 3: whoever proves control wins. The squatter's row goes.
        signup(address).andExpect(status().isNoContent());   // squatter
        reset(email);
        signup(address).andExpect(status().isNoContent());   // real owner
        String code = codeSentTo(address);
        assertThat(rowsFor(address)).hasSize(2);

        verifyEmail(address, code).andExpect(status().isOk());

        List<BuyerAccountEmail> after = rowsFor(address);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).isVerified()).isTrue();
    }

    @Test
    void a_later_signup_on_the_same_address_cannot_steal_the_code_the_first_buyer_received()
            throws Exception {
        // The mirror image of the test above, and the ordering that was never
        // pinned: the real owner signs up FIRST and the squatter second. A code
        // belongs to the account that asked for it, so the code the owner
        // received must still verify the OWNER's account. Resolving "the live
        // code for this address" by recency instead would hand her address —
        // and every order joined to it — to whoever signed up last.
        signup(address).andExpect(status().isNoContent());   // real owner
        UUID owner = onlyRowFor(address).getBuyerAccountId();
        String ownerCode = codeSentTo(address);
        reset(email);

        signup(address).andExpect(status().isNoContent());   // squatter, afterwards
        UUID squatter = rowsFor(address).stream()
                .map(BuyerAccountEmail::getBuyerAccountId)
                .filter(id -> !id.equals(owner))
                .findFirst()
                .orElseThrow();

        verifyEmail(address, ownerCode).andExpect(status().isOk());

        List<BuyerAccountEmail> after = rowsFor(address);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getBuyerAccountId())
                .as("the account that received the code is the one that gets verified")
                .isEqualTo(owner);
        assertThat(after.get(0).isVerified()).isTrue();
        assertThat(after.get(0).isPrimary()).isTrue();
        assertThat(sessions.findByBuyerAccountIdAndRevokedAtIsNull(squatter))
                .as("redeeming a code must never sign anybody into the later claimant's account")
                .isEmpty();
        assertThat(sessions.findByBuyerAccountIdAndRevokedAtIsNull(owner)).isNotEmpty();
    }

    @Test
    void resend_sends_nothing_while_two_accounts_hold_a_live_claim_on_one_address() throws Exception {
        // resend-verification is unauthenticated and carries only an address, so
        // once a second account claims it there is nothing in the request that
        // says which claim the caller is completing. Picking one would mail the
        // squatter's code to the owner's inbox. The neutral 204 is unchanged;
        // the owner's own code simply keeps working.
        signup(address).andExpect(status().isNoContent());   // real owner
        UUID owner = onlyRowFor(address).getBuyerAccountId();
        String ownerCode = codeSentTo(address);

        signup(address).andExpect(status().isNoContent());   // squatter
        reset(email);

        resendVerification(address).andExpect(status().isNoContent());
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());

        verifyEmail(address, ownerCode).andExpect(status().isOk());
        assertThat(onlyRowFor(address).getBuyerAccountId()).isEqualTo(owner);
    }

    @Test
    void a_wrong_code_is_a_neutral_INVALID_CODE() throws Exception {
        signup(address).andExpect(status().isNoContent());

        verifyEmail(address, "000000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CODE"));
    }

    @Test
    void a_code_for_an_address_nobody_ever_claimed_is_the_same_INVALID_CODE() throws Exception {
        verifyEmail(address, "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CODE"));
    }

    @Test
    void ten_failures_lock_the_address_even_against_the_correct_code() throws Exception {
        // The property a per-code attempt cap alone does NOT give you: without
        // the DB-counted lockout an attacker just asks for a fresh code every
        // five guesses. RateLimitConfig is @Profile("!test"), so this counter is
        // the only one the suite can assert on — which is exactly why it exists.
        signup(address).andExpect(status().isNoContent());
        String correct = codeSentTo(address);

        for (int i = 0; i < 10; i++) {
            verifyEmail(address, "000000").andExpect(status().isBadRequest());
        }

        verifyEmail(address, correct)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertThat(onlyRowFor(address).isVerified()).isFalse();
    }

    @Test
    void resend_mails_a_fresh_code_and_retires_the_previous_one() throws Exception {
        signup(address).andExpect(status().isNoContent());
        String first = codeSentTo(address);
        reset(email);

        resendVerification(address).andExpect(status().isNoContent());
        String second = codeSentTo(address);
        assertThat(second).isNotNull().isNotEqualTo(first);

        verifyEmail(address, first)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CODE"));
        verifyEmail(address, second).andExpect(status().isOk());
    }

    @Test
    void resend_is_204_and_silent_for_an_address_nobody_claimed() throws Exception {
        resendVerification(address).andExpect(status().isNoContent());
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void resend_is_204_and_silent_for_an_already_verified_address() throws Exception {
        signupAndVerify(address);
        reset(email);

        resendVerification(address).andExpect(status().isNoContent());

        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * The per-code attempt counter must be its own gate.
     *
     * <p>{@code consume} reads {@code attempts} and then increments it in a
     * separate {@code REQUIRES_NEW} transaction, so N concurrent wrong guesses
     * against one fresh code all read the same value, all pass the check, and
     * the increments serialise — and the sixth write violates
     * {@code chk_bevc_attempts_range}, which escapes as a 500 instead of the
     * neutral {@code INVALID_CODE} this flow promises, and skips the hourly
     * lockout counter on the way out. {@code POST /buyer/auth/verify-email} has
     * no bucket at all, so nothing bounds the concurrency. Driving the writes
     * directly is the deterministic version of that burst.
     */
    @Test
    void a_burst_of_wrong_guesses_cannot_push_attempts_past_the_check_constraint() throws Exception {
        signup(address).andExpect(status().isNoContent());
        UUID codeId = codes.findByEmailNormalizedAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        address.toLowerCase(), Instant.now()).get(0).getId();

        assertThatCode(() -> {
            for (int i = 0; i < 8; i++) codes.incrementAttempts(codeId, 5);
        }).as("the counter must refuse to overrun rather than 500 the request").doesNotThrowAnyException();

        assertThat(codes.findById(codeId).orElseThrow().getAttempts()).isEqualTo(5);
        verifyEmail(address, "000000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CODE"));
    }

    // ── Login: one generic 401, and D-2's ordering ─────────────────────────

    @Test
    void login_with_the_wrong_password_is_a_generic_401() throws Exception {
        signupAndVerify(address);

        login(address, "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void login_to_an_address_with_no_account_is_the_identical_401() throws Exception {
        login(address, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void an_unverified_account_answers_401_on_a_wrong_password_and_403_only_on_the_right_one() throws Exception {
        // §15 D-2. Returned in the other order, 403 EMAIL_NOT_VERIFIED tells an
        // unauthenticated caller "this address has an imin account" — precisely
        // the fact the neutral signup response refuses to disclose.
        signup(address).andExpect(status().isNoContent());

        login(address, "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));

        login(address, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void login_succeeds_after_verification_and_issues_a_usable_session() throws Exception {
        signupAndVerify(address);

        MvcResult result = login(address, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emails[0].email").value(address))
                .andReturn();

        mvc.perform(get("/api/v1/buyer/me")
                        .cookie(new Cookie(BuyerSessionCookie.NAME, sessionCookieValue(result))))
                .andExpect(status().isOk());
    }

    // ── Forgot / reset ─────────────────────────────────────────────────────

    @Test
    void forgot_password_is_204_and_silent_for_an_unknown_address() throws Exception {
        forgotPassword(address).andExpect(status().isNoContent());
        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void forgot_password_is_204_and_silent_for_an_address_that_is_only_claimed() throws Exception {
        // An unverified row grants nothing (§2.3 rule 4) — including the ability
        // to be sent a reset link for somebody else's future account.
        signup(address).andExpect(status().isNoContent());
        reset(email);

        forgotPassword(address).andExpect(status().isNoContent());

        verify(email, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void reset_password_rotates_the_credential_and_revokes_every_session() throws Exception {
        signupAndVerify(address);
        MvcResult signedIn = login(address, PASSWORD).andExpect(status().isOk()).andReturn();
        String liveCookie = sessionCookieValue(signedIn);
        UUID accountId = onlyRowFor(address).getBuyerAccountId();
        reset(email);

        forgotPassword(address).andExpect(status().isNoContent());
        String token = resetTokenSentTo(address);
        assertThat(token).isNotBlank();

        resetPassword(token, "brand-new-password").andExpect(status().isNoContent());

        assertThat(sessions.findByBuyerAccountIdAndRevokedAtIsNull(accountId))
                .as("a reset happens because someone else may have the old credential")
                .isEmpty();
        mvc.perform(get("/api/v1/buyer/me").cookie(new Cookie(BuyerSessionCookie.NAME, liveCookie)))
                .andExpect(status().isUnauthorized());

        login(address, PASSWORD).andExpect(status().isUnauthorized());
        login(address, "brand-new-password").andExpect(status().isOk());
    }

    @Test
    void a_reset_token_is_single_use() throws Exception {
        signupAndVerify(address);
        reset(email);
        forgotPassword(address).andExpect(status().isNoContent());
        String token = resetTokenSentTo(address);

        resetPassword(token, "brand-new-password").andExpect(status().isNoContent());
        resetPassword(token, "another-new-password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    /**
     * A second reset link retires the first.
     *
     * <p>The one flow whose premise is "somebody else may have my credential"
     * has to actually close. An earlier link that is still live — forwarded,
     * leaked out of a shared or compromised inbox, sitting in a proxy log —
     * could be redeemed straight after the owner's own recovery, take the
     * account back, and revoke the fresh sessions on its way through. Only the
     * newest link may work.
     */
    @Test
    void asking_for_a_new_reset_link_retires_the_previous_one() throws Exception {
        signupAndVerify(address);
        reset(email);

        forgotPassword(address).andExpect(status().isNoContent());
        String first = resetTokenSentTo(address);
        reset(email);
        forgotPassword(address).andExpect(status().isNoContent());
        String second = resetTokenSentTo(address);
        assertThat(second).isNotBlank().isNotEqualTo(first);

        resetPassword(second, "brand-new-password").andExpect(status().isNoContent());

        resetPassword(first, "attacker-chosen-password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
        login(address, "brand-new-password").andExpect(status().isOk());
    }

    @Test
    void an_unknown_reset_token_is_INVALID_TOKEN() throws Exception {
        resetPassword("not-a-real-token", "brand-new-password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
    }

    // ── Google is config-gated on its OWN redirect URI ─────────────────────

    @Test
    void the_buyer_google_endpoints_404_until_the_second_redirect_uri_is_configured() throws Exception {
        // GOOGLE_OAUTH_BUYER_REDIRECT_URI is blank in the test profile, which is
        // the state of the world until the URI is registered in the Google
        // console. A 404 (rather than an authorize URL Google will refuse) is
        // what lets the frontend hide the button instead of dead-ending a buyer
        // on redirect_uri_mismatch.
        mvc.perform(get("/api/v1/buyer/auth/google/url"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_DISABLED"));

        mvc.perform(post("/api/v1/buyer/auth/google/callback")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc\",\"state\":\"xyz\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_DISABLED"));
    }

    // ── Validation ─────────────────────────────────────────────────────────

    @Test
    void a_password_under_ten_characters_is_rejected() throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/signup")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"password\":\"short1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions signup(String to) throws Exception {
        return mvc.perform(post("/api/v1/buyer/auth/signup")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\",\"password\":\"" + PASSWORD + "\",\"locale\":\"en\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions verifyEmail(String to, String code)
            throws Exception {
        return mvc.perform(post("/api/v1/buyer/auth/verify-email")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\",\"code\":\"" + code + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions resendVerification(String to) throws Exception {
        return mvc.perform(post("/api/v1/buyer/auth/resend-verification")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String to, String password) throws Exception {
        return mvc.perform(post("/api/v1/buyer/auth/login")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\",\"password\":\"" + password + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions forgotPassword(String to) throws Exception {
        return mvc.perform(post("/api/v1/buyer/auth/forgot-password")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions resetPassword(String token, String password)
            throws Exception {
        return mvc.perform(post("/api/v1/buyer/auth/reset-password")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"));
    }

    private void signupAndVerify(String to) throws Exception {
        signup(to).andExpect(status().isNoContent());
        verifyEmail(to, codeSentTo(to)).andExpect(status().isOk());
    }

    private List<BuyerAccountEmail> rowsFor(String to) {
        String normalized = to.trim().toLowerCase();
        return accounts.findAll().stream()
                .flatMap(a -> emails.findByBuyerAccountIdOrderByCreatedAtAsc(a.getId()).stream())
                .filter(r -> normalized.equals(r.getEmailNormalized()))
                .toList();
    }

    private BuyerAccountEmail onlyRowFor(String to) {
        List<BuyerAccountEmail> rows = rowsFor(to);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** The six digits as they appear in the message body actually sent to {@code to}. */
    private String codeSentTo(String to) {
        return bodySentTo(to).map(body -> {
            Matcher m = SIX_DIGITS.matcher(body);
            return m.find() ? m.group(1) : null;
        }).orElse(null);
    }

    private String resetTokenSentTo(String to) {
        return bodySentTo(to).map(body -> {
            Matcher m = RESET_TOKEN.matcher(body);
            return m.find() ? m.group(1) : null;
        }).orElse(null);
    }

    private String subjectSentTo(String to) {
        Sends sends = capture();
        for (int i = sends.to().size() - 1; i >= 0; i--) {
            if (to.equalsIgnoreCase(sends.to().get(i))) return sends.subject().get(i);
        }
        return null;
    }

    /** The plain-text body of the most recent message to this address. */
    private Optional<String> bodySentTo(String to) {
        Sends sends = capture();
        for (int i = sends.to().size() - 1; i >= 0; i--) {
            if (to.equalsIgnoreCase(sends.to().get(i))) return Optional.of(sends.text().get(i));
        }
        return Optional.empty();
    }

    private record Sends(List<String> to, List<String> subject, List<String> text) {}

    private Sends capture() {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        try {
            verify(email, atLeast(0)).send(to.capture(), subject.capture(), html.capture(), text.capture());
        } catch (AssertionError e) {
            return new Sends(List.of(), List.of(), List.of());
        }
        return new Sends(to.getAllValues(), subject.getAllValues(), text.getAllValues());
    }

    private static String sessionCookieValue(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(BuyerSessionCookie.NAME);
        return cookie == null ? null : cookie.getValue();
    }
}
