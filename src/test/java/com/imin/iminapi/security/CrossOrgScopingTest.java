package com.imin.iminapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import com.stripe.StripeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-org tenancy security tests.
 *
 * <p>Seeds two organizations (A and B) with real persistence, authenticates as org A,
 * then attempts to access resources that belong to org B via every organizer-facing
 * endpoint. Every probe MUST return {@code 404 NOT_FOUND} with the canonical leak-safe
 * envelope (no "forbidden", "wrong organization", "permission" phrasing — those would
 * reveal that the resource exists somewhere).
 *
 * <p>This test uses the shared integration context: every service is real, including
 * {@code StripeConnectService}; only the {@code StripeClient} boundary is faked. Org B holds a
 * same-mode Stripe account, so without the gate the Connect probes would reach Stripe; they assert
 * it was never called. The authentication path is
 * replaced by directly populating the {@code SecurityContextHolder} with the org-A
 * principal — same shape the production {@link BearerTokenAuthFilter} would produce.
 */
@IminIntegrationTest
class CrossOrgScopingTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired EventRepository events;
    @Autowired PromoCodeRepository promos;
    @Autowired OrganizationRepository orgs;
    @Autowired StripeProperties stripeProps;

    /** The tenancy gate must fire before any call reaches Stripe. */
    @Autowired StripeClient stripeClient;

    private final ObjectMapper om = new ObjectMapper();

    private UUID orgA;
    private UUID orgB;
    private UUID userA;
    private UUID userB;

    /** Org B's resources — what org A will try (and must fail) to access. */
    private UUID eventInB;
    private UUID tierInB;
    private UUID promoInB;

    /** Pre-built auth post-processor for user A in org A — apply to every request. */
    private Authentication authA;

    @BeforeEach
    void seed() {
        // Two distinct orgs, each with one OWNER user; org B also has one event with one tier + one promo.
        Organization a = fx.org();
        orgA = a.getId();
        Organization b = fx.org();
        // A same-mode account with a never-synced mirror: status, session and link would all call Stripe.
        b.setStripeAccountId("acct_" + UUID.randomUUID().toString().replace("-", ""));
        b.setStripeLivemode(stripeProps.isLiveKey());
        b = orgs.save(b);
        orgB = b.getId();

        User uA = fx.owner(a);
        userA = uA.getId();
        User uB = fx.owner(b);
        userB = uB.getId();

        Event eB = fx.event(b, uB, EventStatus.DRAFT, null);
        eventInB = eB.getId();
        tierInB = fx.tier(eB, 1500, 100).getId();

        PromoCode pB = new PromoCode();
        pB.setEventId(eventInB);
        pB.setCode("BSECRET");
        pB.setDiscountPct(10);
        pB.setMaxUses(100);
        promoInB = promos.save(pB).getId();

        // Build the auth principal for user A in org A. Applied per-request via
        // SecurityMockMvcRequestPostProcessors.authentication() — the established way
        // to bind a custom principal in MockMvc (BearerTokenAuthFilter sets the same
        // shape in production).
        authA = new UsernamePasswordAuthenticationToken(
                fx.principal(uA), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    /**
     * Asserts the response is the canonical leak-safe 404:
     *  - HTTP 404
     *  - body envelope has {@code error.code == "NOT_FOUND"}
     *  - message does not contain "forbidden" / "wrong organization" / "permission" /
     *    "access denied" (case-insensitive) — those would tell an attacker the resource
     *    exists but isn't theirs.
     */
    private void expectLeakSafeNotFound(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.with(authentication(authA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andReturn();
        String body = result.getResponse().getContentAsString().toLowerCase();
        assertThat(body)
                .as("leak-safe 404 body must not contain forbidden/wrong-org/permission phrasing: %s", body)
                .doesNotContain("forbidden")
                .doesNotContain("wrong organization")
                .doesNotContain("permission")
                .doesNotContain("access denied");
    }

    /** The Stripe check runs first, so a missing gate fails on the Stripe call itself, not only on the status. */
    private void expectLeakSafeNotFoundWithoutStripe(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.with(authentication(authA))).andReturn();
        verifyNoInteractions(stripeClient);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertLeakSafeBody(result);
    }

    private static void assertLeakSafeBody(MvcResult result) throws Exception {
        String raw = result.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(raw, "$.error.code")).isEqualTo("NOT_FOUND");
        String body = raw.toLowerCase();
        assertThat(body)
                .as("leak-safe 404 body must not contain forbidden/wrong-org/permission phrasing: %s", body)
                .doesNotContain("forbidden")
                .doesNotContain("wrong organization")
                .doesNotContain("permission")
                .doesNotContain("access denied");
    }

    /** Multipart-specific overload — multipart builder isn't a MockHttpServletRequestBuilder. */
    private void expectLeakSafeNotFound(MockMultipartHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.with(authentication(authA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andReturn();
        String body = result.getResponse().getContentAsString().toLowerCase();
        assertThat(body)
                .as("leak-safe 404 body must not contain forbidden/wrong-org/permission phrasing: %s", body)
                .doesNotContain("forbidden")
                .doesNotContain("wrong organization")
                .doesNotContain("permission")
                .doesNotContain("access denied");
    }

    // ── Event endpoints ────────────────────────────────────────────────────────

    @Test
    void getEventInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(get("/api/v1/events/" + eventInB));
    }

    @Test
    void getEventOverviewInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(get("/api/v1/events/" + eventInB + "/overview"));
    }

    @Test
    void patchEventInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(patch("/api/v1/events/" + eventInB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", "Hacked"))));
    }

    @Test
    void publishEventInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(post("/api/v1/events/" + eventInB + "/publish"));
    }

    @Test
    void unpublishEventInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(post("/api/v1/events/" + eventInB + "/unpublish"));
    }

    @Test
    void deleteEventInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(delete("/api/v1/events/" + eventInB));
        assertThat(events.findActive(eventInB)).as("org B's draft must survive").isPresent();
    }

    @Test
    void listEvents_doesNotIncludeOtherOrgs_events() throws Exception {
        // The list endpoint is repo-scoped to p.orgId() — org B's event must not appear in A's list.
        MvcResult result = mvc.perform(get("/api/v1/events").with(authentication(authA)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Org A's event list must not contain org B's event id")
                .doesNotContain(eventInB.toString());
    }

    // ── Tier sub-resource ──────────────────────────────────────────────────────

    @Test
    void createTierUnderOtherOrgEvent_returns404() throws Exception {
        expectLeakSafeNotFound(post("/api/v1/events/" + eventInB + "/tiers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "name", "Sneaky", "priceMinor", 1000, "quantity", 50))));
    }

    @Test
    void patchTierInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(patch("/api/v1/events/" + eventInB + "/tiers/" + tierInB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("priceMinor", 1))));
    }

    @Test
    void deleteTierInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(delete("/api/v1/events/" + eventInB + "/tiers/" + tierInB));
    }

    // ── Promo code sub-resource ────────────────────────────────────────────────

    @Test
    void createPromoUnderOtherOrgEvent_returns404() throws Exception {
        expectLeakSafeNotFound(post("/api/v1/events/" + eventInB + "/promos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "code", "STEAL", "discountPct", 50, "maxUses", 10))));
    }

    @Test
    void patchPromoInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(patch("/api/v1/events/" + eventInB + "/promos/" + promoInB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("discountPct", 99))));
    }

    @Test
    void deletePromoInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(delete("/api/v1/events/" + eventInB + "/promos/" + promoInB));
    }

    // ── Media upload sub-resource ──────────────────────────────────────────────

    @Test
    void uploadMediaToOtherOrgEvent_returns404() throws Exception {
        // PNG magic-bytes prefix so the bytes pass the file-format pre-check even though
        // we expect the org-tenancy check to fire first.
        MockMultipartFile file = new MockMultipartFile(
                "file", "p.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        expectLeakSafeNotFound(multipart("/api/v1/events/" + eventInB + "/media/" + MediaKind.POSTER.wireValue())
                .file(file));
    }

    @Test
    void deleteMediaInOtherOrgEvent_returns404() throws Exception {
        expectLeakSafeNotFound(delete("/api/v1/events/" + eventInB + "/media/" + MediaKind.POSTER.wireValue()));
    }

    // ── Stripe Connect — path-id endpoints (the strongest org-leak smell) ──────

    @Test
    void getStripeStatusForOtherOrg_returns404() throws Exception {
        // The path-id /orgs/{orgId}/… is the riskiest pattern; if the handler trusts the
        // path id over the principal, this test will fail loudly.
        expectLeakSafeNotFoundWithoutStripe(get("/api/v1/orgs/" + orgB + "/stripe/status"));
    }

    @Test
    void connectStripeForOtherOrg_returns404() throws Exception {
        // With no account on file, connect would create one at Stripe.
        Organization b = orgs.findById(orgB).orElseThrow();
        b.setStripeAccountId(null);
        b.setStripeLivemode(null);
        orgs.save(b);
        expectLeakSafeNotFoundWithoutStripe(post("/api/v1/orgs/" + orgB + "/stripe/connect"));
    }

    @Test
    void accountSessionForOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFoundWithoutStripe(post("/api/v1/orgs/" + orgB + "/stripe/account-session"));
    }

    @Test
    void onboardingLinkForOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFoundWithoutStripe(post("/api/v1/orgs/" + orgB + "/stripe/onboarding-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    // ── Org team ──────────────────────────────────────────────────────────────

    @Test
    void getOrgTeam_seesOnlyOwnOrg_membersNotOrgB_members() throws Exception {
        // /api/v1/org/team is principal-scoped (no path id) — must return only org A's users.
        MvcResult result = mvc.perform(get("/api/v1/org/team").with(authentication(authA)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Org A's team list must not contain org B's user id")
                .doesNotContain(userB.toString());
        assertThat(body)
                .as("Org A's team list must contain org A's own user id")
                .contains(userA.toString());
    }

    @Test
    void removeMemberInOtherOrg_returns404() throws Exception {
        expectLeakSafeNotFound(delete("/api/v1/org/team/" + userB));
    }

    // ── Org-self endpoints (no path id; structurally org-A-only) ───────────────

    @Test
    void getOrg_returnsOnlyOwnOrg_neverOrgB() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/org").with(authentication(authA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orgA.toString()))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("GET /api/v1/org must never expose org B's id when authenticated as A")
                .doesNotContain(orgB.toString());
    }

    @Test
    void getAuditLog_returnsOnlyOwnOrg() throws Exception {
        // No path id; just confirm the call is principal-scoped and returns a valid envelope.
        mvc.perform(get("/api/v1/org/audit").with(authentication(authA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void getDashboard_returnsOnlyOwnOrg_neverLeaksOrgBEventId() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/dashboard").with(authentication(authA)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Dashboard for org A must not surface org B's event id")
                .doesNotContain(eventInB.toString());
    }
}
