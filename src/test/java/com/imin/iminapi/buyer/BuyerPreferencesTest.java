package com.imin.iminapi.buyer;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
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

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The preference centre — spec §4.4.
 *
 * <p>The first test is the one this whole slice exists to make true: a buyer
 * who unsubscribed from one organizer through a footer link, then flipped the
 * master toggle off and on again, is <b>still</b> unsubscribed from that
 * organizer. Without it the toggle manufactures consent on an organizer's
 * behalf, and nothing else in the system would notice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerPreferencesTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired MockMvc mvc;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired MarketingOptOutRepository optOuts;
    @Autowired OrganizationRepository organizations;
    @Autowired ConsentService consentService;
    @MockitoBean EmailService email;

    private String address;
    private String cookie;
    private UUID orgA;
    private UUID orgB;
    private UUID membershipA;
    private UUID membershipB;

    @BeforeEach
    void signedInBuyerWithTwoOrganizers() throws Exception {
        reset(email);
        address = address();
        cookie = signUpAndSignIn(address);

        UUID consumerId = consumer(address);
        orgA = org("Alpha");
        orgB = org("Beta");
        membershipA = membership(orgA, consumerId, "subscribed");
        membershipB = membership(orgB, consumerId, "subscribed");
        reset(email);
    }

    // ── the consent-laundering guard ───────────────────────────────────────

    @Test
    void masterToggleOnDoesNotResurrectAFooterUnsubscribe() throws Exception {
        consentService.unsubscribe(orgA, membershipA, "footer_link", "email",
                ConsentOrigin.DATA_SUBJECT, null);              // sticky row written

        patchPrefs("{\"organizerUpdates\":false}").andExpect(status().isOk());
        patchPrefs("{\"organizerUpdates\":true}").andExpect(status().isOk());

        assertThat(memberships.findByIdAndOrgId(membershipA, orgA).orElseThrow().getConsentStatus())
                .as("a footer unsubscribe must survive the master toggle")
                .isEqualTo("unsubscribed");
        assertThat(memberships.findByIdAndOrgId(membershipB, orgB).orElseThrow().getConsentStatus())
                .isEqualTo("subscribed");
    }

    @Test
    void masterToggleOffWritesNoStickyRow() throws Exception {
        long before = optOuts.count();

        patchPrefs("{\"organizerUpdates\":false}").andExpect(status().isOk());

        assertThat(optOuts.count())
                .as("DATA_SUBJECT_GLOBAL is globally reversible and leaves no sticky row")
                .isEqualTo(before);
        assertThat(memberships.findByIdAndOrgId(membershipA, orgA).orElseThrow().getConsentStatus())
                .isEqualTo("unsubscribed");
        assertThat(memberships.findByIdAndOrgId(membershipB, orgB).orElseThrow().getConsentStatus())
                .isEqualTo("unsubscribed");
    }

    @Test
    void masterToggleOffThenOnRestoresEverythingThatWasNotStickilyOptedOut() throws Exception {
        patchPrefs("{\"organizerUpdates\":false}").andExpect(status().isOk());
        patchPrefs("{\"organizerUpdates\":true}").andExpect(status().isOk());

        assertThat(memberships.findByIdAndOrgId(membershipA, orgA).orElseThrow().getConsentStatus())
                .isEqualTo("subscribed");
        assertThat(memberships.findByIdAndOrgId(membershipB, orgB).orElseThrow().getConsentStatus())
                .isEqualTo("subscribed");
    }

    @Test
    void theToggleIsLockedWhenEveryOrganizerHasAStickyRow() throws Exception {
        consentService.unsubscribe(orgA, membershipA, "footer_link", "email",
                ConsentOrigin.DATA_SUBJECT, null);
        consentService.unsubscribe(orgB, membershipB, "footer_link", "email",
                ConsentOrigin.DATA_SUBJECT, null);

        readPrefs()
                .andExpect(jsonPath("$.organizerUpdates").value(false))
                .andExpect(jsonPath("$.organizerUpdatesLocked").value(true));
    }

    @Test
    void anAccountWithNoMembershipsIsNotLockedItIsSimplyEmpty() throws Exception {
        String fresh = signUpAndSignIn(address());

        mvc.perform(get("/api/v1/buyer/preferences").cookie(cookie(fresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizerUpdates").value(false))
                .andExpect(jsonPath("$.organizerUpdatesLocked").value(false));
    }

    @Test
    void organizerUpdatesIsDerivedAndNeverStored() throws Exception {
        // Flip a membership straight in the repo; the endpoint must follow it.
        setConsent(membershipA, orgA, "unsubscribed");
        setConsent(membershipB, orgB, "unsubscribed");
        readPrefs().andExpect(jsonPath("$.organizerUpdates").value(false));

        setConsent(membershipA, orgA, "subscribed");
        readPrefs().andExpect(jsonPath("$.organizerUpdates").value(true));
    }

    // ── the stored switches ────────────────────────────────────────────────

    @Test
    void eventRemindersDefaultsOnAndTheRowIsCreatedLazily() throws Exception {
        readPrefs().andExpect(status().isOk())
                .andExpect(jsonPath("$.eventReminders").value(true))
                .andExpect(jsonPath("$.productNews").value(false));
    }

    @Test
    void patchingOneSwitchLeavesTheOtherAlone() throws Exception {
        patchPrefs("{\"eventReminders\":false}").andExpect(status().isOk());
        patchPrefs("{\"organizerUpdates\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.eventReminders").value(false));
    }

    // ── disclosure ─────────────────────────────────────────────────────────

    @Test
    void organizersListsWhoHoldsConsentAndWhoIsStickilyOptedOut() throws Exception {
        consentService.unsubscribe(orgA, membershipA, "footer_link", "email",
                ConsentOrigin.DATA_SUBJECT, null);

        mvc.perform(get("/api/v1/buyer/organizers").cookie(cookie(cookie)))
                .andExpect(status().isOk())
                // {items, nextCursor}, not a bare array — see BuyerSavedListResponse.
                // $.length() would have counted the ENVELOPE's two keys and passed
                // for the wrong reason; assert inside items.
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    /**
     * The fan-out is bounded. Each membership costs roughly three statements
     * inside one transaction, so an account with an unbounded number of them
     * would hold locks on {@code memberships} and {@code consent_records} for
     * as long as it took. Refused whole rather than applied halfway: a partial
     * fan-out leaves the derived toggle reading a state nobody asked for.
     */
    @Test
    void anAccountWithTooManyOrganizersIsRefusedRatherThanHalfApplied() throws Exception {
        UUID consumerId = consumers.findByNormalizedEmail(address.toLowerCase())
                .orElseThrow().getConsumerId();
        for (int i = 0; i < 201; i++) {
            membership(org("Bulk " + i), consumerId, "unsubscribed");
        }

        patchPrefs("{\"organizerUpdates\":true}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        // Nothing moved — not even the first few.
        assertThat(memberships.findByIdAndOrgId(membershipA, orgA).orElseThrow().getConsentStatus())
                .isEqualTo("subscribed");
    }

    @Test
    void thePreferenceEndpointsNeedABuyerSession() throws Exception {
        mvc.perform(get("/api/v1/buyer/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/buyer/organizers")).andExpect(status().isUnauthorized());
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private ResultActions readPrefs() throws Exception {
        return mvc.perform(get("/api/v1/buyer/preferences").cookie(cookie(cookie)));
    }

    private ResultActions patchPrefs(String body) throws Exception {
        return mvc.perform(patch("/api/v1/buyer/preferences")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void setConsent(UUID membershipId, UUID orgId, String status) {
        Membership m = memberships.findByIdAndOrgId(membershipId, orgId).orElseThrow();
        m.setConsentStatus(status);
        memberships.save(m);
    }

    private UUID consumer(String normalizedEmail) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(normalizedEmail.trim().toLowerCase());
        c.setDisplayName("Ada");
        return consumers.save(c).getConsumerId();
    }

    private UUID org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug("org-" + UUID.randomUUID());
        o.setContactEmail("org-" + UUID.randomUUID() + "@example.com");
        o.setCountry("DE");
        return organizations.save(o).getId();
    }

    private UUID membership(UUID orgId, UUID consumerId, String consentStatus) {
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumerId);
        m.setConsentStatus(consentStatus);
        m.setConsentBasis("soft_opt_in");
        return memberships.save(m).getMembershipId();
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
