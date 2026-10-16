package com.imin.iminapi.controller.org;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audienceplan.config.PlanRefreshExecutor;
import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.oauth.OAuthAccountService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.auth.OrganizerTerms;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.stripe.StripeClient;
import com.stripe.model.v2.core.Account;
import com.stripe.param.v2.core.AccountCreateParams;
import com.stripe.service.V2Services;
import com.stripe.service.v2.CoreService;
import com.stripe.service.v2.core.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An account whose owner has not finished setting up may not create its Stripe account (which would fix the
 * payout country for good), start Stripe onboarding, or publish; once setup is done each goes through.
 */
@IminIntegrationTest
class OrgSetupGuardTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired OAuthAccountService oauth;
    @Autowired UserRepository users;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired StripeClient stripeClient;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier(PlanRefreshExecutor.NAME) Executor planRefresh;

    private final ObjectMapper om = new ObjectMapper();
    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void deleteOwnOrgs() {
        AsyncDrain.drain(planRefresh); // a publish plans in the background; let it finish before the rows go
        OrgRows.delete(jdbc, orgIds);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stripe-connect", "payouts-connect", "account-session", "onboarding-link", "publish"})
    void whileSetupIsPending_theActionIsRefused(String action) throws Exception {
        User owner = googleSignUp();
        Event draft = publishableDraft(owner);

        mvc.perform(request(action, owner, draft).with(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SETUP_REQUIRED"));

        verifyNoInteractions(stripeClient);
        Map<String, Object> org = jdbc.queryForMap(
                "select stripe_account_id from organizations where id = ?", owner.getOrgId());
        assertThat(org.get("stripe_account_id")).isNull();
        assertThat(events.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void afterSetup_connectCreatesTheStripeAccountInTheChosenCountry() throws Exception {
        User owner = googleSignUp();
        finishSetup(owner, "FR");
        AccountService accounts = stubStripeAccountCreate("acct_" + UUID.randomUUID().toString().replace("-", ""));

        mvc.perform(post("/api/v1/orgs/{orgId}/stripe/connect", owner.getOrgId()).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));

        ArgumentCaptor<AccountCreateParams> sent = ArgumentCaptor.forClass(AccountCreateParams.class);
        verify(accounts).create(sent.capture());
        assertThat(sent.getValue().getIdentity().getCountry()).isEqualTo("FR");
    }

    @Test
    void afterSetup_aFreeEventPublishes() throws Exception {
        User owner = googleSignUp();
        Event draft = publishableDraft(owner);
        finishSetup(owner, "FR");

        mvc.perform(post("/api/v1/events/{id}/publish", draft.getId()).with(as(owner)))
                .andExpect(status().isOk());

        assertThat(events.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.LIVE);
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder request(String action, User owner, Event draft) {
        UUID orgId = owner.getOrgId();
        return switch (action) {
            case "stripe-connect" -> post("/api/v1/orgs/{orgId}/stripe/connect", orgId);
            case "payouts-connect" -> post("/api/v1/payouts/connect");
            case "account-session" -> post("/api/v1/orgs/{orgId}/stripe/account-session", orgId);
            case "onboarding-link" -> post("/api/v1/orgs/{orgId}/stripe/onboarding-link", orgId)
                    .contentType(MediaType.APPLICATION_JSON).content("{}");
            case "publish" -> post("/api/v1/events/{id}/publish", draft.getId());
            default -> throw new IllegalArgumentException(action);
        };
    }

    private User googleSignUp() {
        AuthResponse signedIn = oauth.resolve(new OAuthUserInfo("google", "google-sub-" + UUID.randomUUID(),
                fx.email("ada"), true, "Ada", "Lovelace", "Ada Lovelace"));
        orgIds.add(signedIn.org().id());
        return users.findById(signedIn.user().id()).orElseThrow();
    }

    private void finishSetup(User owner, String country) throws Exception {
        mvc.perform(post("/api/v1/org/setup").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("firstName", "Ada", "lastName", "Lovelace",
                                "orgName", "Lovelace Nights " + UUID.randomUUID().toString().substring(0, 8),
                                "country", country, "termsVersion", OrganizerTerms.CURRENT_VERSION))))
                .andExpect(status().isOk());
    }

    /** A free draft that passes publish validation, so only the setup state can stop it. */
    private Event publishableDraft(User owner) {
        Organization org = orgs.findById(owner.getOrgId()).orElseThrow();
        Event e = fx.event(org, owner, EventStatus.DRAFT, Instant.now().plusSeconds(14 * 86_400));
        e.setVenueStreet("1 rue de la Nuit");
        e.setVenueCity("Metz");
        e.setVenuePostalCode("57000");
        e.setDescription("A night.");
        Event saved = events.save(e);
        fx.tier(saved, 0, 100);
        return saved;
    }

    private AccountService stubStripeAccountCreate(String accountId) throws Exception {
        V2Services v2 = mock(V2Services.class);
        CoreService core = mock(CoreService.class);
        AccountService accounts = mock(AccountService.class);
        Account created = mock(Account.class);
        when(created.getId()).thenReturn(accountId);
        when(stripeClient.v2()).thenReturn(v2);
        when(v2.core()).thenReturn(core);
        when(core.accounts()).thenReturn(accounts);
        when(accounts.create(any(AccountCreateParams.class))).thenReturn(created);
        return accounts;
    }

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }
}
