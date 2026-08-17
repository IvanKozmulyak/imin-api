package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.oauth.AppleNativeIdentityService;
import com.imin.iminapi.oauth.GoogleOAuthService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
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

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
 * <p><b>Why this class mocks {@link RateLimiter} instead of exhausting a real
 * bucket.</b> {@code RateLimitConfig} is {@code @Profile("!test")} and the
 * double at {@link TestRateLimitConfig} invents a 1000/minute bucket for any
 * name it is handed — so a test that merely hammered an endpoint would pass
 * against a completely unmetered controller, and would pass just as happily
 * against a typo'd bucket name that 500s in production. The thing worth pinning
 * is therefore not "it eventually 429s" but the exact pair the controller asks
 * for: WHICH bucket, and WHICH key. Those two strings are the contract.
 * {@code RateLimitBucketCoverageTest} independently proves the names exist in
 * both application.yaml and RateLimitConfig.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerCredentialRateLimitTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "tr0ubador-and-more";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");
    /** MockMvc's default remote address, i.e. what getRemoteAddr() returns here. */
    private static final String LOCAL_IP = "127.0.0.1";

    @Autowired MockMvc mvc;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository emailRows;
    @MockitoBean EmailService email;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean GoogleOAuthService googleIdTokens;
    @MockitoBean AppleNativeIdentityService apple;

    private String address;
    private String cookie;
    private UUID accountId;

    @BeforeEach
    void signedInBuyer() throws Exception {
        reset(email);
        address = address();
        cookie = signUpAndSignIn(address);
        accountId = accountIdOf(address);
        // Sign-up/verify consumed their own buckets; only the calls made by the
        // endpoint under test should be visible to the assertions below.
        reset(rateLimiter, email);
    }

    // ── POST /buyer/me/password ────────────────────────────────────────────

    @Test
    void aPasswordChangeConsumesItsOwnBucketKeyedByAccount() throws Exception {
        changePassword("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .andExpect(status().isNoContent());

        verify(rateLimiter).consume("buyer-password-change", "acct:" + accountId);
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

        verify(rateLimiter).consume("buyer-password-change", "acct:" + accountId);
    }

    @Test
    void theKeyIsTheAccountAndNotTheClientAddress() throws Exception {
        // Keying this endpoint on the IP would be the wrong trade twice over: an
        // attacker holding a stolen session can change address at will, while a
        // household or office behind one NAT shares the punishment.
        changePassword("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .andExpect(status().isNoContent());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).consume(eq("buyer-password-change"), key.capture());
        assertThat(key.getValue()).isEqualTo("acct:" + accountId);
        assertThat(key.getValue()).doesNotContain(LOCAL_IP);
    }

    @Test
    void anExhaustedBucketAnswers429AndDoesNotChangeThePassword() throws Exception {
        doThrow(ApiException.rateLimited()).when(rateLimiter).consume(anyString(), anyString());

        changePassword("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .andExpect(status().isTooManyRequests());

        // Proves the limiter runs BEFORE the write, not merely that a 429 shape
        // exists: the old password must still be the one that works.
        assertThat(accounts.findById(accountId).orElseThrow().getPasswordHash())
                .as("a throttled request must not have rotated the password")
                .isNotNull();
        reset(rateLimiter);
        mvc.perform(post("/api/v1/buyer/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    // ── native sign-in ─────────────────────────────────────────────────────

    @Test
    void googleNativeConsumesTheNativeSignInBucketKeyedByIp() throws Exception {
        when(googleIdTokens.nativeEnabled()).thenReturn(true);
        when(googleIdTokens.verifyNativeIdToken("g-token")).thenReturn(
                new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(),
                        address(), true, "Ada", "Lovelace", "Ada Lovelace"));

        mvc.perform(post("/api/v1/buyer/auth/google/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"g-token\"}"))
                .andExpect(status().isOk());

        verify(rateLimiter).consume("buyer-native-signin", "ip:" + LOCAL_IP);
    }

    @Test
    void appleNativeSharesTheSameBucketAsGoogle() throws Exception {
        // One bucket for both lanes on purpose: same act, same cost, and two
        // buckets would hand an attacker double the budget for alternating
        // between providers.
        when(apple.enabled()).thenReturn(true);
        when(apple.verify(eq("a-token"), any())).thenReturn(
                new OAuthUserInfo("apple", "apple-sub-" + UUID.randomUUID(),
                        UUID.randomUUID() + "@privaterelay.appleid.com", true, "Sofiya", "K", "Sofiya K"));

        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"a-token\",\"fullName\":\"Sofiya K\"}"))
                .andExpect(status().isOk());

        verify(rateLimiter).consume("buyer-native-signin", "ip:" + LOCAL_IP);
    }

    @Test
    void nativeSignInIsMeteredEvenWhenTheProviderIsTurnedOff() throws Exception {
        // Metered before the config gate, like the wallet endpoints. Otherwise a
        // disabled provider is an unbounded 404 generator, and — worse — the
        // ordering that makes verification free to probe would be a refactor away.
        when(apple.enabled()).thenReturn(false);

        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"a-token\"}"))
                .andExpect(status().isNotFound());

        verify(rateLimiter).consume("buyer-native-signin", "ip:" + LOCAL_IP);
        verify(apple, never()).verify(anyString(), any());
    }

    @Test
    void theNativeKeyIgnoresAClientSuppliedForwardedForHeader() throws Exception {
        // The trap the wallet endpoint documents. getRemoteAddr() is resolved by
        // the framework from trusted proxy headers (forward-headers-strategy in
        // application-prod.yaml); reading X-Forwarded-For directly here would let
        // any caller mint a fresh bucket per request by varying one header, which
        // is a rate limit in name only.
        when(apple.enabled()).thenReturn(true);
        when(apple.verify(eq("a-token"), any())).thenReturn(
                new OAuthUserInfo("apple", "apple-sub-" + UUID.randomUUID(),
                        UUID.randomUUID() + "@privaterelay.appleid.com", true, "S", "K", "S K"));

        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"a-token\"}"))
                .andExpect(status().isOk());

        verify(rateLimiter).consume("buyer-native-signin", "ip:" + LOCAL_IP);
        verify(rateLimiter, never()).consume(anyString(), eq("ip:203.0.113.9"));
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
        String normalized = to.trim().toLowerCase();
        return accounts.findAll().stream()
                .filter(a -> emailRows.findByBuyerAccountIdOrderByCreatedAtAsc(a.getId()).stream()
                        .anyMatch(r -> normalized.equals(r.getEmailNormalized())))
                .findFirst()
                .orElseThrow()
                .getId();
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
        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email, atLeast(1)).send(recipient.capture(), subject.capture(), html.capture(), text.capture());

        List<String> to_ = recipient.getAllValues();
        List<String> bodies = text.getAllValues();
        for (int i = to_.size() - 1; i >= 0; i--) {
            if (to.equalsIgnoreCase(to_.get(i))) {
                Matcher m = SIX_DIGITS.matcher(bodies.get(i));
                if (m.find()) return m.group(1);
            }
        }
        throw new AssertionError("no six-digit code mailed to " + to);
    }
}
