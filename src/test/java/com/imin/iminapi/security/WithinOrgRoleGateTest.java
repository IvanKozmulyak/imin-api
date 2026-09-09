package com.imin.iminapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Within-org role tests — the axis {@link CrossOrgScopingTest} does not cover.
 *
 * <p>That suite proves org A cannot reach org B. This one proves that inside a
 * single org the {@code role} column actually decides something: a MEMBER is
 * refused on the destructive / money / bulk-PII endpoints, while the OWNER of
 * the same org is not. The expected answer here is {@code 403 FORBIDDEN}, not
 * the leak-safe 404 the cross-org probes get — the resource is the caller's own
 * org, so there is nothing to hide, and the dashboard needs to be able to say
 * why.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class WithinOrgRoleGateTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;

    /** Stripe is mocked — the role gate must fire before any Stripe call. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean StripeConnectService stripeConnectService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean StripeProductService stripeProductService;

    private final ObjectMapper om = new ObjectMapper();

    private UUID orgId;
    private UUID ownerId;
    private UUID memberId;
    private UUID eventId;

    private Authentication asMember;
    private Authentication asOwner;

    @BeforeEach
    void seed() {
        Organization o = new Organization();
        o.setName("Role Org");
        o.setSlug("role-org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("owner@example.test");
        o.setCountry("DE");
        orgId = orgs.save(o).getId();

        User owner = new User();
        owner.setOrgId(orgId);
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        owner.setRole(UserRole.OWNER);
        ownerId = users.save(owner).getId();

        User member = new User();
        member.setOrgId(orgId);
        member.setEmail("member-" + UUID.randomUUID() + "@example.test");
        member.setRole(UserRole.MEMBER);
        memberId = users.save(member).getId();

        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(ownerId);
        e.setName("Role gate event");
        e.setSlug("role-gate-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        e.setCurrency("EUR");
        eventId = events.save(e).getId();

        asMember = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(memberId, orgId, UserRole.MEMBER, UUID.randomUUID()),
                null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        asOwner = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(ownerId, orgId, UserRole.OWNER, UUID.randomUUID()),
                null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    @AfterEach
    void clear() {
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private void expectForbidden(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(authentication(asMember)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void member_cannot_invite_a_team_member() throws Exception {
        expectForbidden(post("/api/v1/org/team/invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("email", "x@example.test", "role", "admin"))));
    }

    @Test
    void member_cannot_remove_a_team_member() throws Exception {
        expectForbidden(delete("/api/v1/org/team/" + ownerId));
    }

    @Test
    void member_cannot_export_the_attendee_list() throws Exception {
        expectForbidden(get("/api/v1/events/" + eventId + "/attendees/export"));
    }

    @Test
    void member_cannot_rotate_the_gate_credential() throws Exception {
        expectForbidden(post("/api/v1/orgs/" + orgId + "/gate/credentials/rotate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("password", "a-long-enough-password"))));
    }

    @Test
    void member_cannot_patch_the_org() throws Exception {
        expectForbidden(patch("/api/v1/org")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("contactEmail", "attacker@example.test"))));
    }

    @Test
    void member_cannot_connect_a_payout_account() throws Exception {
        expectForbidden(post("/api/v1/payouts/connect"));
    }

    /** The same MEMBER keeps the read half of the dashboard — the gate is not a blanket lockout. */
    @Test
    void member_can_still_list_the_team() throws Exception {
        mvc.perform(get("/api/v1/org/team").with(authentication(asMember)))
                .andExpect(status().isOk());
    }

    /** …and an OWNER is not refused by the gate (invite reaches the service and succeeds). */
    @Test
    void owner_can_invite() throws Exception {
        mvc.perform(post("/api/v1/org/team/invite")
                        .with(authentication(asOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                Map.of("email", "invited-" + UUID.randomUUID() + "@example.test",
                                        "role", "admin"))))
                .andExpect(status().isOk());
    }
}
