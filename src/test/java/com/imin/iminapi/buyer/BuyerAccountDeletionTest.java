package com.imin.iminapi.buyer;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerSessionRepository;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /buyer/account/delete} and {@code /delete/cancel} — epic §7.1.
 *
 * <p>The point of this file is that "scheduled" is not "deferred". Three things
 * are true the instant the buyer confirms, and each of them has a test here:
 * the status flips, every session dies, and every membership is unsubscribed on
 * every channel. Only the destructive cascade waits out the 30 days, and that
 * lives in {@code BuyerAccountErasureTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerAccountDeletionTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired MockMvc mvc;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository emails;
    @Autowired BuyerSessionRepository sessions;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired OrganizationRepository orgs;
    @MockitoBean EmailService email;

    private String primary;
    private String cookie;
    private UUID accountId;

    @BeforeEach
    void signedInBuyer() throws Exception {
        reset(email);
        primary = address();
        cookie = signUpAndSignIn(primary);
        accountId = accountFor(primary);
        reset(email);
    }

    // ── Scheduling ─────────────────────────────────────────────────────────

    @Test
    void delete_flips_status_and_sets_a_thirty_day_deadline() throws Exception {
        Instant before = Instant.now();

        requestDeletion()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleteAt").isString());

        BuyerAccount account = accounts.findById(accountId).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(BuyerAccount.STATUS_DELETE_PENDING);
        assertThat(account.getDeleteAt())
                .isNotNull()
                .isBetween(before.plus(29, ChronoUnit.DAYS), before.plus(31, ChronoUnit.DAYS));
    }

    /**
     * Deletion is a credential-level event (§2.2). It also has to clear the
     * cookie in the same response: the caller's own session was among the ones
     * revoked, and a browser still holding a dead token meets an unexplained 401
     * on its next call instead of a clean signed-out state.
     */
    @Test
    void delete_revokes_every_session_and_clears_the_cookie() throws Exception {
        String secondDevice = signInAgain();
        assertThat(sessions.findByBuyerAccountIdAndRevokedAtIsNull(accountId)).hasSize(2);

        requestDeletion()
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString(BuyerSessionCookie.NAME + "=;")));

        assertThat(sessions.findByBuyerAccountIdAndRevokedAtIsNull(accountId)).isEmpty();

        // Both credentials are dead, not just the acting one.
        me(cookie).andExpect(status().isUnauthorized());
        me(secondDevice).andExpect(status().isUnauthorized());
    }

    /**
     * Art.21 objection is synchronous. A buyer who asked to be deleted must not
     * receive a campaign on day 12, so the unsubscribe cannot wait for the job.
     */
    @Test
    void delete_unsubscribes_every_membership_on_every_channel_immediately() throws Exception {
        UUID orgA = org("DelOrgA");
        UUID orgB = org("DelOrgB");
        UUID consumerId = consumer(primary);
        UUID mA = membership(orgA, consumerId, "subscribed");
        UUID mB = membership(orgB, consumerId, "subscribed");

        requestDeletion().andExpect(status().isOk());

        for (UUID mid : List.of(mA, mB)) {
            Membership m = membershipById(mid);
            assertThat(m.getConsentStatus()).isEqualTo("unsubscribed");
            assertThat(m.getSmsConsentStatus()).isEqualTo("unsubscribed");
            assertThat(m.getConsentBasis()).isNull();
        }
    }

    /** The whole address set, not just the primary — §7.2's fan-out rule applies here too. */
    @Test
    void delete_unsubscribes_memberships_behind_every_verified_address() throws Exception {
        String second = address();
        addAddress(second).andExpect(status().isNoContent());
        verifyAddress(second, codeSentTo(second)).andExpect(status().isOk());

        UUID orgA = org("DelFanA");
        UUID mPrimary = membership(orgA, consumer(primary), "subscribed");
        UUID mSecond = membership(org("DelFanB"), consumer(second), "subscribed");

        requestDeletion().andExpect(status().isOk());

        assertThat(membershipById(mPrimary).getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(membershipById(mSecond).getConsentStatus()).isEqualTo("unsubscribed");
    }

    /**
     * An unverified row was never proof of anything. If it dragged a stranger's
     * audience membership into an unsubscribe, "add an address" would become a
     * way to silence someone else's marketing.
     */
    @Test
    void delete_leaves_memberships_behind_an_unverified_address_alone() throws Exception {
        String claimed = address();
        addAddress(claimed).andExpect(status().isNoContent());

        UUID stranger = membership(org("DelUnverified"), consumer(claimed), "subscribed");

        requestDeletion().andExpect(status().isOk());

        assertThat(membershipById(stranger).getConsentStatus()).isEqualTo("subscribed");
    }

    /** Re-confirming must not quietly buy the account another 30 days. */
    @Test
    void delete_is_idempotent_and_does_not_slide_the_deadline() throws Exception {
        requestDeletion().andExpect(status().isOk());
        Instant first = accounts.findById(accountId).orElseThrow().getDeleteAt();

        cookie = signInAgain();
        requestDeletion().andExpect(status().isOk());

        assertThat(accounts.findById(accountId).orElseThrow().getDeleteAt()).isEqualTo(first);
    }

    // ── Cancelling ─────────────────────────────────────────────────────────

    @Test
    void cancel_clears_the_status_and_the_deadline() throws Exception {
        requestDeletion().andExpect(status().isOk());
        cookie = signInAgain();

        cancelDeletion().andExpect(status().isNoContent());

        BuyerAccount account = accounts.findById(accountId).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(BuyerAccount.STATUS_ACTIVE);
        assertThat(account.getDeleteAt()).isNull();
    }

    /**
     * THE TEST THIS ENDPOINT EXISTS FOR. Someone may sign in on day 12 purely to
     * pull up a ticket. Cancelling their erasure because they needed to get
     * through a door would be a decision the product made on their behalf.
     */
    @Test
    void signing_in_during_the_grace_window_does_not_cancel_the_deletion() throws Exception {
        requestDeletion().andExpect(status().isOk());
        Instant deadline = accounts.findById(accountId).orElseThrow().getDeleteAt();

        String fresh = signInAgain();
        me(fresh).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(BuyerAccount.STATUS_DELETE_PENDING));

        BuyerAccount account = accounts.findById(accountId).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(BuyerAccount.STATUS_DELETE_PENDING);
        assertThat(account.getDeleteAt()).isEqualTo(deadline);
    }

    /**
     * Consent is not a side effect of the account's lifecycle. It was withdrawn
     * by an affirmative act and only an affirmative act restores it — silently
     * re-subscribing is the consent-laundering §6.3 exists to prevent.
     */
    @Test
    void cancel_does_not_resubscribe_what_the_deletion_unsubscribed() throws Exception {
        UUID mid = membership(org("CancelNoResub"), consumer(primary), "subscribed");
        requestDeletion().andExpect(status().isOk());
        assertThat(membershipById(mid).getConsentStatus()).isEqualTo("unsubscribed");

        cookie = signInAgain();
        cancelDeletion().andExpect(status().isNoContent());

        assertThat(membershipById(mid).getConsentStatus()).isEqualTo("unsubscribed");
    }

    @Test
    void cancel_on_an_account_with_nothing_scheduled_is_a_no_op() throws Exception {
        cancelDeletion().andExpect(status().isNoContent());
        assertThat(accounts.findById(accountId).orElseThrow().getStatus())
                .isEqualTo(BuyerAccount.STATUS_ACTIVE);
    }

    // ── Access control ─────────────────────────────────────────────────────

    @Test
    void both_endpoints_require_a_buyer_session() throws Exception {
        mvc.perform(post("/api/v1/buyer/account/delete").header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/buyer/account/delete/cancel").header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    /** Deletion is destructive; the answer must never sit in a shared cache. */
    @Test
    void responses_are_private_no_store() throws Exception {
        requestDeletion().andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        cookie = signInAgain();
        cancelDeletion().andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    }

    /**
     * The retention figure in §7.3 is blocked on counsel and is frontend copy.
     * No number, and no promise about what is kept, may appear in this body.
     */
    @Test
    void the_response_carries_only_a_deadline_and_invents_no_retention_claim() throws Exception {
        String body = requestDeletion().andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("year");
        assertThat(body).doesNotContainIgnoringCase("retain");
        assertThat(body).doesNotContainIgnoringCase("law");
        assertThat(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).fieldNames()).toIterable().containsExactly("deleteAt");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ResultActions requestDeletion() throws Exception {
        return mvc.perform(post("/api/v1/buyer/account/delete")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie)));
    }

    private ResultActions cancelDeletion() throws Exception {
        return mvc.perform(post("/api/v1/buyer/account/delete/cancel")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie)));
    }

    private ResultActions me(String raw) throws Exception {
        return mvc.perform(get("/api/v1/buyer/me").cookie(cookie(raw)));
    }

    private ResultActions addAddress(String to) throws Exception {
        return mvc.perform(post("/api/v1/buyer/emails")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
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

    /** A second live session on the same account. */
    private String signInAgain() throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/buyer/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + primary + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie set = res.getResponse().getCookie(BuyerSessionCookie.NAME);
        assertThat(set).isNotNull();
        return set.getValue();
    }

    private UUID accountFor(String to) {
        String normalized = EmailNormalizer.normalize(to);
        List<UUID> ids = accounts.findAll().stream()
                .filter(a -> emails.findByBuyerAccountIdOrderByCreatedAtAsc(a.getId()).stream()
                        .anyMatch(r -> normalized.equals(r.getEmailNormalized())))
                .map(BuyerAccount::getId)
                .toList();
        assertThat(ids).hasSize(1);
        return ids.get(0);
    }

    private UUID org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail(name + "@test.com");
        o.setCountry("DE");
        return orgs.save(o).getId();
    }

    private UUID consumer(String rawEmail) {
        String normalized = EmailNormalizer.normalize(rawEmail);
        return consumers.findByNormalizedEmail(normalized)
                .map(Consumer::getConsumerId)
                .orElseGet(() -> {
                    Consumer c = new Consumer();
                    c.setNormalizedEmail(normalized);
                    c.setDisplayName(rawEmail);
                    return consumers.save(c).getConsumerId();
                });
    }

    private UUID membership(UUID orgId, UUID consumerId, String consentStatus) {
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumerId);
        m.setConsentStatus(consentStatus);
        m.setConsentBasis("explicit");
        m.setSmsConsentStatus(consentStatus);
        m.setSmsConsentBasis("explicit");
        return memberships.save(m).getMembershipId();
    }

    /** Re-reads a membership from the database — the repository exposes no unscoped find-by-id. */
    private Membership membershipById(UUID id) {
        for (Organization o : orgs.findAll()) {
            Optional<Membership> m = memberships.findByIdAndOrgId(id, o.getId());
            if (m.isPresent()) return m.get();
        }
        throw new AssertionError("membership not found: " + id);
    }

    private static Cookie cookie(String raw) {
        return new Cookie(BuyerSessionCookie.NAME, raw);
    }

    private static String address() {
        return "del-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }

    private String codeSentTo(String to) {
        return bodySentTo(to).map(body -> {
            Matcher m = SIX_DIGITS.matcher(body);
            return m.find() ? m.group(1) : null;
        }).orElse(null);
    }

    private Optional<String> bodySentTo(String to) {
        ArgumentCaptor<String> recipients = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        try {
            verify(email, atLeast(0)).send(recipients.capture(), subject.capture(),
                    html.capture(), text.capture());
        } catch (AssertionError e) {
            return Optional.empty();
        }
        List<String> sentTo = recipients.getAllValues();
        for (int i = sentTo.size() - 1; i >= 0; i--) {
            if (to.equalsIgnoreCase(sentTo.get(i))) return Optional.of(text.getAllValues().get(i));
        }
        return Optional.empty();
    }
}
