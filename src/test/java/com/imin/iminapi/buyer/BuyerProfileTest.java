package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerIdentityRepository;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.buyer.service.BuyerOAuthService;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.support.IminIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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
@IminIntegrationTest
class BuyerProfileTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "tr0ubador-and-more";

    @Autowired MockMvc mvc;
    @Autowired BuyerOAuthService google;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerIdentityRepository identities;
    @Autowired com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository preferences;
    @Autowired com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository emailRows;
    @Autowired RecordingEmailService mail;

    /** Buyer account mail is sent AFTER_COMMIT on this pool — see {@link BuyerMailSync}. */
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("ticketEmailExecutor")
    java.util.concurrent.Executor mailExecutor;

    private String address;
    private String cookie;
    /** This test's own account. The database is shared, so every assertion below is scoped to it. */
    private UUID accountId;

    @BeforeEach
    void signedInBuyer() throws Exception {
        address = address();
        cookie = signUpAndSignIn(address);
        accountId = accountIdOf(address);
    }

    private UUID accountIdOf(String to) {
        return emailRows.findByVerifiedKey(to.trim().toLowerCase()).orElseThrow().getBuyerAccountId();
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
    void anUnknownLocaleIsRejected() throws Exception {
        patchMe("{\"locale\":\"de\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    static Stream<Arguments> nameRules() {
        String halves = "{\"firstName\":\"Sofiya\",\"lastName\":\"K.\"}";
        return Stream.of(
                Arguments.of("a blank name is stored as null, not whitespace",
                        null, "{\"displayName\":\"   \"}", null, null, null),
                // display_name stays authoritative for display and follows the halves.
                Arguments.of("first and last name keep the display name in step",
                        null, halves, "Sofiya", "K.", "Sofiya K."),
                Arguments.of("an explicit display name wins over the derived one",
                        null, "{\"firstName\":\"Sofiya\",\"lastName\":\"K.\",\"displayName\":\"Sof\"}",
                        "Sofiya", "K.", "Sof"),
                Arguments.of("clearing both halves clears the display name",
                        halves, "{\"firstName\":null,\"lastName\":null}", null, null, null));
    }

    /** A null expectation means the key is absent from the response. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nameRules")
    void nameRules(String rule, String before, String body, String first, String last, String display)
            throws Exception {
        if (before != null) patchMe(before).andExpect(status().isOk());
        patchMe(body)
                .andExpect(status().isOk())
                .andExpect(first == null ? jsonPath("$.firstName").doesNotExist() : jsonPath("$.firstName").value(first))
                .andExpect(last == null ? jsonPath("$.lastName").doesNotExist() : jsonPath("$.lastName").value(last))
                .andExpect(display == null
                        ? jsonPath("$.displayName").doesNotExist() : jsonPath("$.displayName").value(display));
    }

    /** 2015 is accepted because no age gate exists, so the optional field must not become one. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(nullValues = "null", value = {
            "1994-03-17, 200, null",
            "2015-06-01, 200, null",
            "2099-01-01, 400, INVALID_REQUEST",
            "not-a-date, 400, null"})
    void dateOfBirth(String dob, int expectedStatus, String errorCode) throws Exception {
        ResultActions result = patchMe("{\"dateOfBirth\":\"" + dob + "\"}")
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 200) result.andExpect(jsonPath("$.dateOfBirth").value(dob));
        if (errorCode != null) result.andExpect(jsonPath("$.error.code").value(errorCode));
    }

    @Test
    void aPhoneRoundTripsAndClears() throws Exception {
        patchMe("{\"phone\":\"+33 6 12 34 56 78\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+33 6 12 34 56 78"));

        // Absent leaves it alone; an explicit null clears it. Same contract as
        // every other optional field on this endpoint.
        patchMe("{\"city\":\"Metz\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+33 6 12 34 56 78"));
        patchMe("{\"phone\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    /**
     * NOT FORMAT-CHECKED, ON PURPOSE (V94). Four countries, no country picker
     * on the field, and a rule that rejects a legitimate French mobile written
     * the way a French buyer writes it is worse than storing what they typed.
     * Only the column width is enforced.
     */
    @Test
    void aPhoneIsLengthCappedButNotFormatChecked() throws Exception {
        patchMe("{\"phone\":\"06 12 34 56 78\"}").andExpect(status().isOk());
        patchMe("{\"phone\":\"" + "9".repeat(33) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void patchCannotReachStatusOrDeleteAt() throws Exception {
        patchMe("{\"status\":\"delete_pending\",\"deleteAt\":\"2030-01-01T00:00:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    // ── POST /buyer/me/onboarding ──────────────────────────────────────────

    @Test
    void onboardingStoresTheDetailsAndStampsTheTermsAcceptance() throws Exception {
        onboard("{\"firstName\":\"Sofiya\",\"lastName\":\"K.\",\"city\":\"Metz\","
                        + "\"dateOfBirth\":\"1994-03-17\",\"acceptedTerms\":true,"
                        + "\"termsVersion\":\"2026-08-14\",\"productNews\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Sofiya"))
                .andExpect(jsonPath("$.displayName").value("Sofiya K."))
                .andExpect(jsonPath("$.city").value("Metz"))
                .andExpect(jsonPath("$.dateOfBirth").value("1994-03-17"));

        var account = accounts.findById(accountId).orElseThrow();
        assertThat(account.getTermsAcceptedAt()).isNotNull();
        // The CLIENT sent "2026-08-14" and it is deliberately not believed: whatever
        // the browser said used to become the audit fact, which is the one property
        // a consent record must not have. The field is still accepted on the wire.
        assertThat(account.getTermsVersion())
                .isEqualTo(com.imin.iminapi.buyer.BuyerTerms.CURRENT_VERSION);
        // A version is only evidence if the text it names can be produced later.
        assertThat(account.getTermsProof()).isNotBlank();
    }

    /**
     * False is not a toggle the client may send — the screen cannot continue without it — and an
     * absent answer is not a false one; it is a client that skipped the gate.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "{\"firstName\":\"Ada\",\"acceptedTerms\":false} | INVALID_REQUEST",
            "{\"firstName\":\"Ada\"}                         | null"})
    void onboardingWithoutAcceptingTheTermsIsRefused(String body, String errorCode) throws Exception {
        ResultActions result = onboard(body).andExpect(status().isBadRequest());
        if (errorCode != null) result.andExpect(jsonPath("$.error.code").value(errorCode));
    }

    @Test
    void marketingIsOffUnlessExplicitlyTrue() throws Exception {
        onboard("{\"acceptedTerms\":true}").andExpect(status().isOk());

        mvc.perform(get("/api/v1/buyer/preferences").cookie(cookie(cookie)))
                .andExpect(jsonPath("$.productNews").value(false));
    }

    @Test
    void marketingOptInIsRecordedWithTheSentenceThatWasShown() throws Exception {
        onboard("{\"acceptedTerms\":true,\"productNews\":true,"
                        + "\"productNewsProof\":\"Email me about new nights and offers from imin.\"}")
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/buyer/preferences").cookie(cookie(cookie)))
                .andExpect(jsonPath("$.productNews").value(true));

        var prefs = preferences.findById(accountId).orElseThrow();
        assertThat(prefs.getProductNewsAt()).isNotNull();
        assertThat(prefs.getProductNewsProof())
                .isEqualTo("Email me about new nights and offers from imin.");
    }

    /** Withdrawing clears the proof: a stale one beside a false flag describes an agreement that ended. */
    @Test
    void turningMarketingOffClearsTheProof() throws Exception {
        onboard("{\"acceptedTerms\":true,\"productNews\":true,\"productNewsProof\":\"Yes please.\"}")
                .andExpect(status().isOk());
        onboard("{\"acceptedTerms\":true,\"productNews\":false}").andExpect(status().isOk());

        var prefs = preferences.findById(accountId).orElseThrow();
        assertThat(prefs.isProductNews()).isFalse();
        assertThat(prefs.getProductNewsAt()).isNull();
        assertThat(prefs.getProductNewsProof()).isNull();
    }

    /** The record is of the FIRST acceptance; re-running the step must not rewrite it. */
    @Test
    void reRunningOnboardingDoesNotRewriteWhenTheTermsWereAccepted() throws Exception {
        onboard("{\"acceptedTerms\":true,\"termsVersion\":\"v1\"}").andExpect(status().isOk());
        var stampedAt = accounts.findById(accountId).orElseThrow().getTermsAcceptedAt();
        assertThat(stampedAt).isNotNull();

        onboard("{\"acceptedTerms\":true,\"termsVersion\":\"v2\"}").andExpect(status().isOk());

        var again = accounts.findById(accountId).orElseThrow();
        assertThat(again.getTermsAcceptedAt()).isEqualTo(stampedAt);
        // Neither "v1" nor "v2" — the version has never come from the client.
        assertThat(again.getTermsVersion())
                .isEqualTo(com.imin.iminapi.buyer.BuyerTerms.CURRENT_VERSION);
    }

    /**
     * The finish-registration screen starts every field empty and sends the
     * ones the buyer left alone as explicit nulls (imin-public
     * {@code CompleteClient.tsx}). Onboarding only ever SETS: an absent name is
     * "leave it alone", not "clear it", so the display name a provider supplied
     * — the only one a Google buyer has — survives ticking just the terms box.
     */
    @Test
    void onboardingWithNoNameKeepsTheDisplayNameGoogleSupplied() throws Exception {
        var info = new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(),
                address(), true, "Ada", "Lovelace", "Ada Lovelace");
        var signedIn = google.resolve(info, "JUnit/1.0");
        UUID id = signedIn.account().getId();
        assertThat(signedIn.account().getDisplayName()).isEqualTo("Ada Lovelace");

        mvc.perform(post("/api/v1/buyer/me/onboarding")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie(signedIn.session().rawToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        // Byte for byte what the buyer site posts when only the box is ticked.
                        .content("{\"firstName\":null,\"lastName\":null,\"city\":null,"
                                + "\"dateOfBirth\":null,\"acceptedTerms\":true,"
                                + "\"termsVersion\":\"2026-08-14\",\"productNews\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"));

        assertThat(accounts.findById(id).orElseThrow().getDisplayName())
                .isEqualTo("Ada Lovelace");
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

    /**
     * Unlinking is one of §2.2's five mandatory revocation events, and it was
     * the only implemented one that skipped it — so "remove this sign-in
     * method" removed the method and left every session minted through it alive
     * for the remaining 180 days. The acting session is spared, as on the
     * password change: a settings toggle must not sign the buyer out of the tab
     * they used.
     */
    @Test
    void unlinkingAProviderRevokesOtherSessionsButNotThisOne() throws Exception {
        var info = new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(),
                address(), true, "Ada", "Lovelace", "Ada Lovelace");
        String acting = google.resolve(info, "JUnit/1.0").session().rawToken();

        // A password first, or the unlink is refused as the last credential.
        mvc.perform(post("/api/v1/buyer/me/password")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie(acting))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        // Minted AFTER the password change, so only the unlink can kill it.
        String other = google.resolve(info, "JUnit/1.0").session().rawToken();

        mvc.perform(delete("/api/v1/buyer/identities/google")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie(acting)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(other)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(acting)))
                .andExpect(status().isOk());
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

    static Stream<Arguments> sessionlessCalls() {
        return Stream.of(
                Arguments.of("POST /buyer/me/onboarding", post("/api/v1/buyer/me/onboarding")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptedTerms\":true}")),
                Arguments.of("GET /buyer/identities", get("/api/v1/buyer/identities")),
                Arguments.of("PATCH /buyer/me", patch("/api/v1/buyer/me")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Metz\"}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sessionlessCalls")
    void theProfileEndpointsNeedABuyerSession(String call, MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(status().isUnauthorized());
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private ResultActions patchMe(String body) throws Exception {
        return mvc.perform(patch("/api/v1/buyer/me")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions onboard(String body) throws Exception {
        return mvc.perform(post("/api/v1/buyer/me/onboarding")
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
        return BuyerMailSync.codeTo(mail, mailExecutor, to);
    }
}
