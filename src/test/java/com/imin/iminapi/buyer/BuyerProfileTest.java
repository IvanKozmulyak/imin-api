package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerIdentityRepository;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.buyer.service.BuyerOAuthService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.oauth.OAuthUserInfo;
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
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile writes — spec §4.3.
 *
 * <p>Three properties carry the whole slice: the patch is genuinely partial
 * (absent ≠ explicit null), a password change spares the session that made it,
 * and no settings toggle can lock a buyer out of their own tickets.
 *
 * <p>Every state-changing call carries {@code Origin} —
 * {@code BuyerRequestGuardFilter} 403s without it before any controller runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerProfileTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "tr0ubador-and-more";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired MockMvc mvc;
    @Autowired BuyerOAuthService google;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerIdentityRepository identities;
    @MockitoBean EmailService email;

    private String address;
    private String cookie;

    @BeforeEach
    void signedInBuyer() throws Exception {
        reset(email);
        address = address();
        cookie = signUpAndSignIn(address);
        reset(email);
    }

    // ── PATCH /buyer/me ────────────────────────────────────────────────────

    @Test
    void patchIsPartial_anAbsentKeyIsUntouchedAndAnExplicitNullClears() throws Exception {
        patchMe("{\"displayName\":\"Sofiya K.\",\"city\":\"Metz\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Sofiya K."))
                .andExpect(jsonPath("$.city").value("Metz"));

        patchMe("{\"city\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Sofiya K."))   // untouched
                .andExpect(jsonPath("$.city").doesNotExist());             // cleared
    }

    @Test
    void aBlankNameIsStoredAsNullNotAsWhitespace() throws Exception {
        patchMe("{\"displayName\":\"   \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").doesNotExist());
    }

    @Test
    void anUnknownLocaleIsRejected() throws Exception {
        patchMe("{\"locale\":\"de\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void patchCannotReachStatusOrDeleteAt() throws Exception {
        patchMe("{\"status\":\"delete_pending\",\"deleteAt\":\"2030-01-01T00:00:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    // ── POST /buyer/me/password ────────────────────────────────────────────

    @Test
    void passwordChangeRevokesOtherSessionsButNotThisOne() throws Exception {
        String second = signInAgain(address);

        changePassword("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(cookie)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(second)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theWrongCurrentPasswordIsRefused() throws Exception {
        changePassword("{\"currentPassword\":\"not-the-password\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void aShortNewPasswordIsRejectedJustLikeAtSignup() throws Exception {
        changePassword("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void aGoogleOnlyAccountSetsItsFirstPasswordWithoutProvingOne() throws Exception {
        String googleCookie = googleOnlySession();

        // No currentPassword: there is no password_hash to prove.
        mvc.perform(post("/api/v1/buyer/me/password")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie(googleCookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
    }

    // ── Identities ─────────────────────────────────────────────────────────

    @Test
    void unlinkingTheOnlyCredentialIs409() throws Exception {
        String googleCookie = googleOnlySession();

        mvc.perform(delete("/api/v1/buyer/identities/google")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie(googleCookie)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LAST_CREDENTIAL"));
    }

    @Test
    void unlinkingIsAllowedOnceAPasswordExists() throws Exception {
        String googleCookie = googleOnlySession();

        mvc.perform(post("/api/v1/buyer/me/password")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie(googleCookie))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/v1/buyer/identities/google")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie(googleCookie)))
                .andExpect(status().isNoContent());
    }

    @Test
    void identitiesListsTheProviderAndNeverTheProviderUserId() throws Exception {
        String googleCookie = googleOnlySession();

        mvc.perform(get("/api/v1/buyer/identities").cookie(cookie(googleCookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].provider").value("google"))
                .andExpect(jsonPath("$[0].providerUserId").doesNotExist());
    }

    @Test
    void theProfileEndpointsNeedABuyerSession() throws Exception {
        mvc.perform(get("/api/v1/buyer/identities")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/buyer/me")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Metz\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private ResultActions patchMe(String body) throws Exception {
        return mvc.perform(patch("/api/v1/buyer/me")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions changePassword(String body) throws Exception {
        return mvc.perform(post("/api/v1/buyer/me/password")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** A brand-new account whose only credential is Google — no password hash. */
    private String googleOnlySession() {
        var info = new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(),
                address(), true, "Ada", "Lovelace", "Ada Lovelace");
        var signedIn = google.resolve(info, "JUnit/1.0");
        assertThat(signedIn.account().getPasswordHash())
                .as("the fixture is only meaningful if the account has no password")
                .isNull();
        return signedIn.session().rawToken();
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

    /** A second live session on the same account, for the revoke-others test. */
    private String signInAgain(String to) throws Exception {
        MvcResult in = mvc.perform(post("/api/v1/buyer/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + to + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie set = in.getResponse().getCookie(BuyerSessionCookie.NAME);
        if (set == null) throw new AssertionError("no session cookie on login");
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
