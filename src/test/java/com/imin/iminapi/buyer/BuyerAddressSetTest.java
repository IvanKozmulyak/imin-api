package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The address set — {@code /api/v1/buyer/emails} (R1.3 of the buyer-accounts
 * epic).
 *
 * <p>Black-box through MockMvc, reading the six-digit code out of the
 * <b>rendered mail</b> rather than out of the database, so these tests also
 * prove the code reaches the message a buyer actually receives.
 *
 * <p>Three properties are load-bearing and each has a test that fails loudly if
 * it is lost: an unverified row grants nothing, the taken and untaken branches
 * of "add an address" are indistinguishable <i>including from a follow-up
 * GET</i>, and an account can never be left without a verified primary address.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerAddressSetTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired MockMvc mvc;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository emails;
    @Autowired BuyerSessionRepository sessions;
    @MockitoBean EmailService email;

    private String primary;
    private String cookie;
    private UUID accountId;

    @BeforeEach
    void signedInBuyer() throws Exception {
        reset(email);
        primary = address();
        cookie = signUpAndSignIn(primary);
        accountId = rowsFor(primary).get(0).getBuyerAccountId();
        reset(email);
    }

    // ── Add: an unverified row grants nothing ──────────────────────────────

    @Test
    void adding_an_unknown_address_is_204_mails_a_code_and_leaves_the_row_unverified() throws Exception {
        String second = address();

        addAddress(second).andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        BuyerAccountEmail row = onlyRowFor(second);
        assertThat(row.getBuyerAccountId()).isEqualTo(accountId);
        assertThat(row.isVerified())
                .as("adding an address must grant nothing until the code is redeemed")
                .isFalse();
        assertThat(row.getAddedVia()).isEqualTo(BuyerAccountEmail.ADDED_VIA_MANUAL);
        assertThat(codeSentTo(second)).isNotNull();
    }

    @Test
    void the_new_row_is_listed_as_unverified_and_not_primary() throws Exception {
        String second = address();
        addAddress(second).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/buyer/emails").cookie(cookie(cookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].email").value(second))
                .andExpect(jsonPath("$[1].verified").value(false))
                .andExpect(jsonPath("$[1].primary").value(false));
    }

    // ── Add: VERIFIED, not EXISTS (§15 C-2) ────────────────────────────────

    @Test
    void adding_an_address_only_claimed_elsewhere_still_sends_a_code() throws Exception {
        // The squatting case. Someone else holds an UNVERIFIED claim on this
        // address; branching on "exists" would leave the real owner unable to
        // ever attach their own address, with no diagnosable error because every
        // response here is neutral.
        String contested = address();
        String squatterCookie = signUpAndSignIn(address());
        addAddress(contested, squatterCookie).andExpect(status().isNoContent());
        reset(email);

        addAddress(contested).andExpect(status().isNoContent());

        assertThat(rowsFor(contested))
                .as("unverified claims are deliberately not unique")
                .hasSize(2);
        assertThat(codeSentTo(contested)).as("the real owner must get a code").isNotNull();
    }

    @Test
    void adding_an_address_verified_on_another_account_sends_nothing() throws Exception {
        String taken = address();
        signUpAndSignIn(taken);                    // verified on somebody else's account
        reset(email);

        addAddress(taken).andExpect(status().isNoContent());

        assertThat(codeSentTo(taken))
                .as("no code may go to an address somebody else has already proved they control")
                .isNull();
    }

    /**
     * THE PROPERTY A 204-ON-BOTH-BRANCHES TEST ALONE DOES NOT GIVE YOU.
     *
     * <p>This endpoint is authenticated, so the caller can look at their own
     * account straight afterwards. If the taken branch skipped the row insert,
     * "POST then GET /buyer/emails" would answer the question the neutral status
     * refuses to answer: row present ⇒ nobody holds this address, row absent ⇒
     * somebody does. So the pending row is written on both branches and the two
     * are indistinguishable from outside.
     */
    @Test
    void the_taken_and_untaken_branches_are_indistinguishable_including_from_the_follow_up_GET()
            throws Exception {
        String taken = address();
        signUpAndSignIn(taken);
        reset(email);
        String untaken = address();

        MvcResult a = addAddress(taken).andExpect(status().isNoContent()).andReturn();
        String afterTaken = listAddressesWithout(primary);

        MvcResult b = addAddress(untaken).andExpect(status().isNoContent()).andReturn();
        String afterBoth = listAddressesWithout(primary);

        assertThat(a.getResponse().getStatus()).isEqualTo(b.getResponse().getStatus());
        assertThat(a.getResponse().getContentAsString()).isEmpty();
        assertThat(b.getResponse().getContentAsString())
                .as("identical status, identical body")
                .isEqualTo(a.getResponse().getContentAsString());

        assertThat(afterTaken)
                .as("a pending row appears for the taken address too, or the GET is the oracle")
                .contains("\"verified\":false");
        assertThat(afterBoth.split("\"verified\":false", -1).length - 1)
                .as("both branches leave exactly one pending row each")
                .isEqualTo(2);
    }

    @Test
    void an_address_verified_elsewhere_can_never_be_verified_here_because_no_code_exists() throws Exception {
        String taken = address();
        signUpAndSignIn(taken);
        reset(email);

        addAddress(taken).andExpect(status().isNoContent());
        // The pending row exists, but there is no code and no way to get one:
        // resend-verification also refuses once an address is verified.
        resendVerification(taken).andExpect(status().isNoContent());
        assertThat(codeSentTo(taken)).isNull();

        verifyAddress(taken, "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CODE"));
    }

    // ── Verify ─────────────────────────────────────────────────────────────

    @Test
    void verifying_the_code_marks_the_address_verified_but_not_primary() throws Exception {
        String second = address();
        addAddress(second).andExpect(status().isNoContent());

        verifyAddress(second, codeSentTo(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emails.length()").value(2))
                .andExpect(jsonPath("$.emails[1].email").value(second))
                .andExpect(jsonPath("$.emails[1].verified").value(true))
                .andExpect(jsonPath("$.emails[1].primary")
                        // The account already had a primary; verifying a second
                        // address must not silently move where security mail goes.
                        .value(false));
    }

    @Test
    void verifying_deletes_every_unverified_claim_on_that_address_elsewhere() throws Exception {
        String contested = address();
        String squatterCookie = signUpAndSignIn(address());
        addAddress(contested, squatterCookie).andExpect(status().isNoContent());
        reset(email);
        addAddress(contested).andExpect(status().isNoContent());

        verifyAddress(contested, codeSentTo(contested)).andExpect(status().isOk());

        List<BuyerAccountEmail> after = rowsFor(contested);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getBuyerAccountId()).isEqualTo(accountId);
        assertThat(after.get(0).isVerified()).isTrue();
    }

    @Test
    void a_wrong_code_is_a_neutral_INVALID_CODE_and_the_address_stays_unverified() throws Exception {
        String second = address();
        addAddress(second).andExpect(status().isNoContent());

        verifyAddress(second, "000000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CODE"));
        assertThat(onlyRowFor(second).isVerified()).isFalse();
    }

    @Test
    void a_code_for_an_address_that_is_not_on_this_account_cannot_verify_anything() throws Exception {
        // The other buyer asks for a code for their own address; this buyer
        // submits it. Nothing on this account may move.
        String otherCookie = signUpAndSignIn(address());
        String theirs = address();
        addAddress(theirs, otherCookie).andExpect(status().isNoContent());
        String theirCode = codeSentTo(theirs);

        verifyAddress(theirs, theirCode)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CODE"));

        assertThat(onlyRowFor(theirs).isVerified()).isFalse();
    }

    // ── Primary ────────────────────────────────────────────────────────────

    @Test
    void an_unverified_address_cannot_become_primary() throws Exception {
        String second = address();
        addAddress(second).andExpect(status().isNoContent());

        setPrimary(second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void promoting_a_verified_address_moves_the_marker_revokes_every_session_and_clears_the_cookie()
            throws Exception {
        String second = address();
        addAddress(second).andExpect(status().isNoContent());
        verifyAddress(second, codeSentTo(second)).andExpect(status().isOk());

        setPrimary(second)
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString(BuyerSessionCookie.NAME + "=")));

        assertThat(sessions.findByBuyerAccountIdAndRevokedAtIsNull(accountId))
                .as("changing where password resets go must end every live session")
                .isEmpty();
        mvc.perform(get("/api/v1/buyer/me").cookie(cookie(cookie)))
                .andExpect(status().isUnauthorized());

        assertThat(onlyRowFor(second).isPrimary()).isTrue();
        assertThat(onlyRowFor(primary).isPrimary()).isFalse();
    }

    @Test
    void both_the_old_and_the_new_primary_are_told() throws Exception {
        String second = address();
        addAddress(second).andExpect(status().isNoContent());
        verifyAddress(second, codeSentTo(second)).andExpect(status().isOk());
        reset(email);

        setPrimary(second).andExpect(status().isNoContent());

        assertThat(subjectSentTo(primary))
                .as("the old primary is the only inbox a buyer still watches if this was not them")
                .isEqualTo("Your imin primary email address changed");
        assertThat(subjectSentTo(second)).isEqualTo("Your imin primary email address changed");
    }

    @Test
    void the_primary_is_told_when_an_address_is_added() throws Exception {
        String second = address();

        addAddress(second).andExpect(status().isNoContent());

        assertThat(subjectSentTo(primary))
                .as("the only signal a buyer has that their account is being extended without them")
                .isEqualTo("An email address was added to your imin account");
    }

    // ── Remove: the rule ───────────────────────────────────────────────────

    @Test
    void an_unverified_row_can_always_be_removed() throws Exception {
        String second = address();
        addAddress(second).andExpect(status().isNoContent());

        removeAddress(second).andExpect(status().isNoContent());

        assertThat(rowsFor(second)).isEmpty();
    }

    @Test
    void the_primary_address_cannot_be_removed() throws Exception {
        String second = address();
        addAddress(second).andExpect(status().isNoContent());
        verifyAddress(second, codeSentTo(second)).andExpect(status().isOk());

        removeAddress(primary)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(rowsFor(primary)).hasSize(1);
    }

    @Test
    void the_last_verified_address_cannot_be_removed() throws Exception {
        // On a healthy account the last verified address IS the primary, so this
        // is the same refusal reached by the same route — the point is that an
        // account can never be emptied of the thing sign-in is gated on.
        removeAddress(primary)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        assertThat(emails.countByBuyerAccountIdAndVerifiedAtIsNotNull(accountId)).isEqualTo(1);
    }

    @Test
    void a_verified_non_primary_address_can_be_removed() throws Exception {
        String second = address();
        addAddress(second).andExpect(status().isNoContent());
        verifyAddress(second, codeSentTo(second)).andExpect(status().isOk());

        removeAddress(second).andExpect(status().isNoContent());

        assertThat(rowsFor(second))
                .as("and the platform-wide verified claim is released with it")
                .isEmpty();
        assertThat(emails.countByBuyerAccountIdAndVerifiedAtIsNotNull(accountId)).isEqualTo(1);
    }

    @Test
    void removing_an_address_that_is_not_on_the_account_is_404() throws Exception {
        String strangers = address();
        signUpAndSignIn(strangers);

        removeAddress(strangers)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(rowsFor(strangers)).hasSize(1);
    }

    // ── Auth ───────────────────────────────────────────────────────────────

    @Test
    void the_address_endpoints_need_a_buyer_session() throws Exception {
        mvc.perform(get("/api/v1/buyer/emails")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/buyer/emails")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private static String address() {
        return "ada+" + UUID.randomUUID() + "@example.com";
    }

    private ResultActions addAddress(String to) throws Exception {
        return addAddress(to, cookie);
    }

    private ResultActions addAddress(String to, String withCookie) throws Exception {
        return mvc.perform(post("/api/v1/buyer/emails")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(withCookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\"}"));
    }

    private ResultActions verifyAddress(String to, String code) throws Exception {
        return mvc.perform(post("/api/v1/buyer/emails/verify")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\",\"code\":\"" + code + "\"}"));
    }

    private ResultActions setPrimary(String to) throws Exception {
        return mvc.perform(post("/api/v1/buyer/emails/primary")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\"}"));
    }

    /**
     * {@code URI.create}, not the {@code String} overload: the string form is a
     * URI <i>template</i> and re-encodes what is already percent-encoded, so
     * {@code %40} would arrive as {@code %2540} and Spring Security's strict
     * firewall would 400 it before any handler ran. Real clients send the
     * address encoded exactly like this.
     */
    private ResultActions removeAddress(String to) throws Exception {
        return mvc.perform(delete(URI.create("/api/v1/buyer/emails/" + percentEncodeSegment(to)))
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie)));
    }

    /** {@code URLEncoder} is form encoding; a path segment must keep {@code +} literal. */
    private static String percentEncodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%2B");
    }

    private ResultActions resendVerification(String to) throws Exception {
        return mvc.perform(post("/api/v1/buyer/auth/resend-verification")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\"}"));
    }

    /** Signs a brand-new account up on {@code to}, verifies it, returns the session cookie value. */
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
        assertThat(set).isNotNull();
        return set.getValue();
    }

    private String listAddressesWithout(String skip) throws Exception {
        String body = mvc.perform(get("/api/v1/buyer/emails").cookie(cookie(cookie)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replace(skip, "");
    }

    private static Cookie cookie(String raw) {
        return new Cookie(BuyerSessionCookie.NAME, raw);
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

    private String codeSentTo(String to) {
        return bodySentTo(to).map(body -> {
            Matcher m = SIX_DIGITS.matcher(body);
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
}
