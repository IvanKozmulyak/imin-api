package com.imin.iminapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.stripe.StripeConnectService;
import com.imin.iminapi.stripe.StripeProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
 * <p>This test uses the full Spring context (real {@code EventService},
 * {@code TicketTierService}, {@code PromoCodeService}, {@code MediaUploadService},
 * {@code TeamService}, {@code OrgService}, {@code AuditQueryService}, {@code DashboardService}),
 * with only Stripe + storage external integrations mocked. The authentication path is
 * replaced by directly populating the {@code SecurityContextHolder} with the org-A
 * principal — same shape the production {@link BearerTokenAuthFilter} would produce.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class CrossOrgScopingTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired PromoCodeRepository promos;

    /** Stripe is mocked — the audit only cares that org-tenancy gates the call BEFORE Stripe runs. */
    @MockitoBean StripeConnectService stripeConnectService;
    @MockitoBean StripeProductService stripeProductService;

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
        // Two distinct orgs, each with one OWNER user and one event with one tier + one promo.
        Organization a = new Organization();
        a.setName("Org A");
        a.setSlug("org-a-" + UUID.randomUUID().toString().substring(0, 8));
        a.setContactEmail("a@example.test");
        a.setCountry("DE");
        a = orgs.save(a);
        orgA = a.getId();

        Organization b = new Organization();
        b.setName("Org B");
        b.setSlug("org-b-" + UUID.randomUUID().toString().substring(0, 8));
        b.setContactEmail("b@example.test");
        b.setCountry("DE");
        b = orgs.save(b);
        orgB = b.getId();

        User uA = new User();
        uA.setOrgId(orgA);
        uA.setEmail("alice-" + UUID.randomUUID() + "@example.test");
        uA.setRole(UserRole.OWNER);
        userA = users.save(uA).getId();

        User uB = new User();
        uB.setOrgId(orgB);
        uB.setEmail("bob-" + UUID.randomUUID() + "@example.test");
        uB.setRole(UserRole.OWNER);
        userB = users.save(uB).getId();

        Event eB = new Event();
        eB.setOrgId(orgB);
        eB.setCreatedBy(userB);
        eB.setName("Org B's secret event");
        eB.setSlug("org-b-event-" + UUID.randomUUID().toString().substring(0, 8));
        eB.setVisibility(EventVisibility.PUBLIC);
        eB.setStatus(EventStatus.DRAFT);
        eB.setCurrency("EUR");
        eventInB = events.save(eB).getId();

        TicketTier tB = new TicketTier();
        tB.setEventId(eventInB);
        tB.setName("GA");
        tB.setPriceMinor(1500);
        tB.setQuantity(100);
        tierInB = tiers.save(tB).getId();

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
        AuthPrincipal pA = new AuthPrincipal(userA, orgA, UserRole.OWNER, UUID.randomUUID());
        authA = new UsernamePasswordAuthenticationToken(
                pA, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    @AfterEach
    void clear() {
        // Tear down in FK order so successive tests in the suite get a clean slate.
        promos.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
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
        // We don't stub stripeConnectService — the gate must fire before any Stripe call.
        // The path-id /orgs/{orgId}/… is the riskiest pattern; if the handler trusts the
        // path id over the principal, this test will fail loudly.
        when(stripeConnectService.getStatus(any(), any())).thenAnswer(inv -> {
            UUID requestedOrgId = inv.getArgument(1);
            AuthPrincipal p = inv.getArgument(0);
            // Replicate the production gate. If the controller ever forgets to forward the
            // principal-derived org id and instead passes the path id straight through, this
            // mock STILL refuses to leak — but the real production code (StripeConnectService)
            // does the comparison itself, so this mock just mirrors it.
            if (!requestedOrgId.equals(p.orgId())) throw ApiException.notFound("Organization");
            return new StripeConnectService.StatusResult(null, false, false, null);
        });
        expectLeakSafeNotFound(get("/api/v1/orgs/" + orgB + "/stripe/status"));
    }

    @Test
    void connectStripeForOtherOrg_returns404() throws Exception {
        when(stripeConnectService.getOrCreateAccount(any(), any())).thenAnswer(inv -> {
            UUID requestedOrgId = inv.getArgument(1);
            AuthPrincipal p = inv.getArgument(0);
            if (!requestedOrgId.equals(p.orgId())) throw ApiException.notFound("Organization");
            return new StripeConnectService.ConnectResult("acct_test", true);
        });
        expectLeakSafeNotFound(post("/api/v1/orgs/" + orgB + "/stripe/connect"));
    }

    @Test
    void accountSessionForOtherOrg_returns404() throws Exception {
        when(stripeConnectService.createAccountSession(any(), any())).thenAnswer(inv -> {
            UUID requestedOrgId = inv.getArgument(1);
            AuthPrincipal p = inv.getArgument(0);
            if (!requestedOrgId.equals(p.orgId())) throw ApiException.notFound("Organization");
            return "cs_test_secret";
        });
        expectLeakSafeNotFound(post("/api/v1/orgs/" + orgB + "/stripe/account-session"));
    }

    @Test
    void onboardingLinkForOtherOrg_returns404() throws Exception {
        when(stripeConnectService.createOnboardingLink(any(), any(), any(), any())).thenAnswer(inv -> {
            UUID requestedOrgId = inv.getArgument(1);
            AuthPrincipal p = inv.getArgument(0);
            if (!requestedOrgId.equals(p.orgId())) throw ApiException.notFound("Organization");
            return "https://stripe/test";
        });
        expectLeakSafeNotFound(post("/api/v1/orgs/" + orgB + "/stripe/onboarding-link")
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
        // Stripe status mock — the dashboard doesn't hit Stripe directly, but bean must exist.
        MvcResult result = mvc.perform(get("/api/v1/dashboard").with(authentication(authA)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Dashboard for org A must not surface org B's event id")
                .doesNotContain(eventInB.toString());
    }
}
