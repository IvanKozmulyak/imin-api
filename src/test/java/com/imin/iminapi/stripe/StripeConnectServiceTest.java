package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.AccountSession;
import com.stripe.model.v2.core.Account;
import com.stripe.param.AccountSessionCreateParams;
import com.stripe.param.v2.core.AccountCreateParams;
import com.stripe.service.AccountSessionService;
import com.stripe.service.V2Services;
import com.stripe.service.v2.CoreService;
import com.stripe.service.v2.core.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeConnectServiceTest {

    private StripeClient stripeClient;
    private V2Services v2Services;
    private CoreService coreService;
    private AccountService accountService;
    private AccountSessionService accountSessionService;
    private OrganizationRepository orgs;
    private StripeProperties props;
    private StripeConnectService svc;

    private final UUID orgId = UUID.randomUUID();
    private final AuthPrincipal principal =
            new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());

    @BeforeEach
    void setUp() throws Exception {
        stripeClient = mock(StripeClient.class);
        v2Services = mock(V2Services.class);
        coreService = mock(CoreService.class);
        accountService = mock(AccountService.class);
        accountSessionService = mock(AccountSessionService.class);
        when(stripeClient.v2()).thenReturn(v2Services);
        when(v2Services.core()).thenReturn(coreService);
        when(coreService.accounts()).thenReturn(accountService);
        when(stripeClient.accountSessions()).thenReturn(accountSessionService);

        orgs = mock(OrganizationRepository.class);
        props = new StripeProperties();
        svc = new StripeConnectService(stripeClient, orgs, props);

        Account created = mock(Account.class);
        when(created.getId()).thenReturn("acct_test_123");
        when(accountService.create(any(AccountCreateParams.class))).thenReturn(created);
    }

    @Test
    void getOrCreateAccount_sendsConfigurationRecipientPayload() throws Exception {
        Organization org = org("US");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        var result = svc.getOrCreateAccount(principal, orgId);
        assertThat(result.accountId()).isEqualTo("acct_test_123");
        assertThat(result.created()).isTrue();

        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        verify(accountService, times(1)).create(captor.capture());
        AccountCreateParams sent = captor.getValue();
        assertThat(sent.getAccountToken()).isNull();
        assertThat(sent.getIdentity()).isNotNull();
        assertThat(sent.getIdentity().getCountry()).isEqualTo("US");
        assertThat(sent.getConfiguration()).isNotNull();
        assertThat(sent.getConfiguration().getRecipient()).isNotNull();
        assertThat(sent.getConfiguration().getRecipient().getCapabilities()).isNotNull();
    }

    @Test
    void getOrCreateAccount_idempotent_skipsStripeWhenAccountAlreadyExists() throws Exception {
        Organization org = org("FR");
        org.setStripeAccountId("acct_existing");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        var result = svc.getOrCreateAccount(principal, orgId);
        assertThat(result.accountId()).isEqualTo("acct_existing");
        assertThat(result.created()).isFalse();
        verify(accountService, never()).create(any(AccountCreateParams.class));
    }

    @Test
    void createAccountSession_returns_clientSecret_with_account_onboarding_component_enabled() throws Exception {
        Organization org = org("FR");
        org.setStripeAccountId("acct_fr_xyz");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        AccountSession session = mock(AccountSession.class);
        when(session.getClientSecret()).thenReturn("secret_abc");
        when(accountSessionService.create(any(AccountSessionCreateParams.class))).thenReturn(session);

        String secret = svc.createAccountSession(principal, orgId);
        assertThat(secret).isEqualTo("secret_abc");

        ArgumentCaptor<AccountSessionCreateParams> captor = ArgumentCaptor.forClass(AccountSessionCreateParams.class);
        verify(accountSessionService).create(captor.capture());
        AccountSessionCreateParams sent = captor.getValue();
        assertThat(sent.getAccount()).isEqualTo("acct_fr_xyz");
        assertThat(sent.getComponents()).isNotNull();
        assertThat(sent.getComponents().getAccountOnboarding()).isNotNull();
        assertThat(sent.getComponents().getAccountOnboarding().getEnabled()).isTrue();
    }

    @Test
    void mapsUnsupportedCountryRejectionTo422() throws Exception {
        Organization org = org("UA");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        // Canonical Stripe rejection for a capability that isn't available in the org's country.
        InvalidRequestException unsupported = new InvalidRequestException(
                "Error: configuration.recipient.capabilities.stripe_balance.stripe_transfers "
                        + "is currently unavailable in UA for your platform.; request-id: req_test123",
                null,
                "req_test123",
                "invalid_request_error",
                400,
                null);
        when(accountService.create(any(AccountCreateParams.class))).thenThrow(unsupported);

        ApiException ex = assertThrows(ApiException.class,
                () -> svc.getOrCreateAccount(principal, orgId));
        assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.code()).isEqualTo(ErrorCode.COUNTRY_NOT_SUPPORTED);
    }

    @Test
    void keepsTransientStripeErrorAs502() throws Exception {
        Organization org = org("UA");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        // A genuine transient/unrelated upstream failure must NOT be swallowed as a
        // terminal country rejection — it stays a retryable 502.
        ApiConnectionException transient_ = new ApiConnectionException(
                "Could not connect to Stripe", null);
        when(accountService.create(any(AccountCreateParams.class))).thenThrow(transient_);

        ApiException ex = assertThrows(ApiException.class,
                () -> svc.getOrCreateAccount(principal, orgId));
        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private Organization org(String country) {
        Organization o = new Organization();
        o.setId(orgId);
        o.setName("Acme " + country);
        o.setContactEmail("contact@acme.example");
        o.setCountry(country);
        return o;
    }
    /**
     * api-14: returnUrl/refreshUrl come off the request body and were forwarded to Stripe with no
     * scheme check at all, so a {@code javascript:} or {@code data:} URL would have been handed
     * to Stripe as the destination it bounces the organizer's browser to at the end of hosted
     * onboarding. The endpoint is authenticated and org-scoped, so the caller can only redirect
     * themselves — but "the upstream probably rejects it" is not a control we own.
     */
    @Test
    void createOnboardingLink_ignores_a_non_http_returnUrl_and_uses_the_configured_default()
            throws Exception {
        Organization org = org("FR");
        org.setStripeAccountId("acct_fr_xyz");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));
        props.setReturnUrlBase("https://dashboard.example.test");

        com.stripe.service.v2.core.AccountLinkService links =
                mock(com.stripe.service.v2.core.AccountLinkService.class);
        when(coreService.accountLinks()).thenReturn(links);
        com.stripe.model.v2.core.AccountLink created = mock(com.stripe.model.v2.core.AccountLink.class);
        when(created.getUrl()).thenReturn("https://connect.stripe.com/setup/x");
        when(links.create(any(com.stripe.param.v2.core.AccountLinkCreateParams.class))).thenReturn(created);

        svc.createOnboardingLink(principal, orgId,
                "javascript:alert(document.cookie)", "data:text/html,<script>1</script>");

        ArgumentCaptor<com.stripe.param.v2.core.AccountLinkCreateParams> captor =
                ArgumentCaptor.forClass(com.stripe.param.v2.core.AccountLinkCreateParams.class);
        verify(links).create(captor.capture());
        var onboarding = captor.getValue().getUseCase().getAccountOnboarding();
        assertThat(onboarding.getReturnUrl()).startsWith("https://dashboard.example.test/");
        assertThat(onboarding.getRefreshUrl()).startsWith("https://dashboard.example.test/");
    }

    /** A normal https URL from the dashboard must still be honoured verbatim. */
    @Test
    void createOnboardingLink_keeps_an_https_returnUrl() throws Exception {
        Organization org = org("FR");
        org.setStripeAccountId("acct_fr_xyz");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        com.stripe.service.v2.core.AccountLinkService links =
                mock(com.stripe.service.v2.core.AccountLinkService.class);
        when(coreService.accountLinks()).thenReturn(links);
        com.stripe.model.v2.core.AccountLink created = mock(com.stripe.model.v2.core.AccountLink.class);
        when(created.getUrl()).thenReturn("https://connect.stripe.com/setup/x");
        when(links.create(any(com.stripe.param.v2.core.AccountLinkCreateParams.class))).thenReturn(created);

        svc.createOnboardingLink(principal, orgId,
                "https://dashboard.imin.wtf/settings/payments?stripe=ok",
                "https://dashboard.imin.wtf/settings/payments?stripe=refresh");

        ArgumentCaptor<com.stripe.param.v2.core.AccountLinkCreateParams> captor =
                ArgumentCaptor.forClass(com.stripe.param.v2.core.AccountLinkCreateParams.class);
        verify(links).create(captor.capture());
        var onboarding = captor.getValue().getUseCase().getAccountOnboarding();
        assertThat(onboarding.getReturnUrl())
                .isEqualTo("https://dashboard.imin.wtf/settings/payments?stripe=ok");
    }

}
