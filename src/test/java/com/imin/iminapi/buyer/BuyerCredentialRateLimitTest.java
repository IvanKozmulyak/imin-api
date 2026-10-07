package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.oauth.AppleNativeIdentityService;
import com.imin.iminapi.oauth.GoogleOAuthService;
import com.imin.iminapi.oauth.OAuthProperties;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.support.PropertyFlips;
import com.imin.iminapi.support.RecordingRateLimiter;
import com.imin.iminapi.support.RecordingRateLimiter.Call;
import com.imin.iminapi.support.IminIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three credential endpoints that shipped unmetered.
 *
 * <p>{@code POST /buyer/me/password} verifies the CURRENT password and had
 * neither a bucket nor an attempt counter — a wrong password 403s and records
 * nothing, so guesses were free and unlimited to anyone holding a session. The
 * two native sign-in lanes consumed no bucket at all, leaving provider-JWKS
 * signature verification and first-sign-in account creation unbounded.
 *
 * <p><b>Why this class reads {@link RecordingRateLimiter} instead of exhausting a
 * real bucket.</b> {@code RateLimitConfig} is {@code @Profile("!test")}, so a test
 * that merely hammered an endpoint would pass against a completely unmetered
 * controller, and would pass just as happily against a typo'd bucket name that
 * 500s in production. The thing worth pinning is therefore not "it eventually
 * 429s" but the exact pair the controller asks for: WHICH bucket, and WHICH key.
 * Those two strings are the contract.
 * {@code RateLimitBucketCoverageTest} independently proves the names exist in
 * both application.yaml and RateLimitConfig.
 */
@IminIntegrationTest
class BuyerCredentialRateLimitTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "tr0ubador-and-more";
    /** MockMvc's default remote address, i.e. what getRemoteAddr() returns here. */
    private static final String LOCAL_IP = "127.0.0.1";

    @Autowired MockMvc mvc;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository emailRows;
    @Autowired RecordingEmailService mail;

    /** Buyer account mail is sent AFTER_COMMIT on this pool — see {@link BuyerMailSync}. */
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("ticketEmailExecutor")
    java.util.concurrent.Executor mailExecutor;
    @Autowired RecordingRateLimiter limiter;
    @Autowired GoogleOAuthService googleIdTokens;
    @Autowired AppleNativeIdentityService apple;
    @Autowired OAuthProperties oauthProps;
    @Autowired PropertyFlips flips;

    private String address;
    private String cookie;
    private UUID accountId;

    @BeforeEach
    void signedInBuyer() throws Exception {
        address = address();
        cookie = signUpAndSignIn(address);
        accountId = accountIdOf(address);
        // Sign-up/verify consumed their own buckets; only the calls made by the
        // endpoint under test should be visible to the assertions below.
        limiter.reset();
    }

    // ── POST /buyer/me/password ────────────────────────────────────────────

    @Test
    void aPasswordChangeConsumesItsOwnBucketKeyedByAccount() throws Exception {
        changePassword("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .andExpect(status().isNoContent());

        assertConsumedOnce("buyer-password-change", "acct:" + accountId);
    }

    @Test
    void aWrongCurrentPasswordStillConsumesTheBucket() throws Exception {
        // The whole point of the fix. If the bucket were consumed after the
        // service call, or only on success, failed guesses would stay free and
        // the endpoint would remain exactly as brute-forceable as before.
        mvc.perform(post("/api/v1/buyer/me/password")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie(cookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isForbidden());

        assertConsumedOnce("buyer-password-change", "acct:" + accountId);
    }

    @Test
    void theKeyIsTheAccountAndNotTheClientAddress() throws Exception {
        // Keying this endpoint on the IP would be the wrong trade twice over: an
        // attacker holding a stolen session can change address at will, while a
        // household or office behind one NAT shares the punishment.
        changePassword("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .andExpect(status().isNoContent());

        assertThat(limiter.calls())
                .filteredOn(c -> c.bucket().equals("buyer-password-change"))
                .extracting(Call::key)
                .containsExactly("acct:" + accountId)
                .allSatisfy(key -> assertThat(key).doesNotContain(LOCAL_IP));
    }

    @Test
    void anExhaustedBucketAnswers429AndDoesNotChangeThePassword() throws Exception {
        limiter.limit("buyer-password-change", 0);

        changePassword("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .andExpect(status().isTooManyRequests());

        // Proves the limiter runs BEFORE the write, not merely that a 429 shape
        // exists: the old password must still be the one that works.
        assertThat(accounts.findById(accountId).orElseThrow().getPasswordHash())
                .as("a throttled request must not have rotated the password")
                .isNotNull();
        mvc.perform(post("/api/v1/buyer/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    // ── native sign-in ─────────────────────────────────────────────────────

    @Test
    void googleNativeConsumesTheNativeSignInBucketKeyedByIp() throws Exception {
        flips.set(oauthProps, "google.nativeAudience", "test-native-audience");
        doReturn(new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(),
                address(), true, "Ada", "Lovelace", "Ada Lovelace"))
                .when(googleIdTokens).verifyNativeIdToken("g-token");

        mvc.perform(post("/api/v1/buyer/auth/google/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"g-token\"}"))
                .andExpect(status().isOk());

        assertConsumedOnce("buyer-native-signin", "ip:" + LOCAL_IP);
    }

    @Test
    void appleNativeSharesTheSameBucketAsGoogle() throws Exception {
        // One bucket for both lanes on purpose: same act, same cost, and two
        // buckets would hand an attacker double the budget for alternating
        // between providers.
        flips.set(oauthProps, "apple.nativeAudience", "test-apple-bundle");
        doReturn(new OAuthUserInfo("apple", "apple-sub-" + UUID.randomUUID(),
                UUID.randomUUID() + "@privaterelay.appleid.com", true, "Sofiya", "K", "Sofiya K"))
                .when(apple).verify(eq("a-token"), any());

        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"a-token\",\"fullName\":\"Sofiya K\"}"))
                .andExpect(status().isOk());

        assertConsumedOnce("buyer-native-signin", "ip:" + LOCAL_IP);
    }

    @Test
    void nativeSignInIsMeteredEvenWhenTheProviderIsTurnedOff() throws Exception {
        // Metered before the config gate, like the wallet endpoints. Otherwise a
        // disabled provider is an unbounded 404 generator, and — worse — the
        // ordering that makes verification free to probe would be a refactor away.
        flips.set(oauthProps, "apple.nativeAudience", "");

        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"a-token\"}"))
                .andExpect(status().isNotFound());

        assertConsumedOnce("buyer-native-signin", "ip:" + LOCAL_IP);
        verify(apple, never()).verify(anyString(), any());
    }

    @Test
    void theNativeKeyIgnoresAClientSuppliedForwardedForHeader() throws Exception {
        // The trap the wallet endpoint documents. getRemoteAddr() is resolved by
        // the framework from trusted proxy headers (forward-headers-strategy in
        // application-prod.yaml); reading X-Forwarded-For directly here would let
        // any caller mint a fresh bucket per request by varying one header, which
        // is a rate limit in name only.
        flips.set(oauthProps, "apple.nativeAudience", "test-apple-bundle");
        doReturn(new OAuthUserInfo("apple", "apple-sub-" + UUID.randomUUID(),
                UUID.randomUUID() + "@privaterelay.appleid.com", true, "S", "K", "S K"))
                .when(apple).verify(eq("a-token"), any());

        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"a-token\"}"))
                .andExpect(status().isOk());

        assertConsumedOnce("buyer-native-signin", "ip:" + LOCAL_IP);
        assertThat(limiter.calls()).extracting(Call::key).doesNotContain("ip:203.0.113.9");
    }

    // ── POST /buyer/auth/verify-email ──────────────────────────────────────

    /**
     * The endpoint shipped with no bucket at all, on the reasoning that the
     * DB-counted per-address lockout was the control. It was not: that counter
     * is spent by whoever makes the failures, so keyed on the address alone it
     * was a way to lock a stranger out rather than a way to stop them. The
     * bucket is keyed per client IP for the same reason signup is.
     */
    @Test
    void verifyEmailIsMeteredPerClientIp() throws Exception {
        String to = address();
        mvc.perform(post("/api/v1/buyer/auth/verify-email")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + to + "\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest());

        assertConsumedOnce("buyer-verify-email", "ip:" + LOCAL_IP);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions changePassword(String body) throws Exception {
        return mvc.perform(post("/api/v1/buyer/me/password")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private UUID accountIdOf(String to) {
        return emailRows.findByVerifiedKey(to.trim().toLowerCase()).orElseThrow().getBuyerAccountId();
    }

    private static String address() {
        return "ada+" + UUID.randomUUID() + "@example.com";
    }

    private static Cookie cookie(String raw) {
        return new Cookie(BuyerSessionCookie.NAME, raw);
    }

    private String signUpAndSignIn(String to) throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/signup")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + to + "\",\"password\":\"" + PASSWORD + "\",\"locale\":\"en\"}"))
                .andExpect(status().isNoContent());
        MvcResult verified = mvc.perform(post("/api/v1/buyer/auth/verify-email")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + to + "\",\"code\":\"" + codeSentTo(to) + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie set = verified.getResponse().getCookie(BuyerSessionCookie.NAME);
        if (set == null) throw new AssertionError("no session cookie on verify-email");
        return set.getValue();
    }

    private String codeSentTo(String to) {
        return BuyerMailSync.codeTo(mail, mailExecutor, to);
    }

    /** The endpoint consumed this bucket exactly once, with this key. */
    private void assertConsumedOnce(String bucket, String key) {
        assertThat(limiter.calls()).filteredOn(c -> c.bucket().equals(bucket))
                .containsExactly(new Call(bucket, key));
    }
}
