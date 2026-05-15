package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.stripe.StripeClient;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    private Organization org(String country) {
        Organization o = new Organization();
        o.setId(orgId);
        o.setName("Acme " + country);
        o.setContactEmail("contact@acme.example");
        o.setCountry(country);
        return o;
    }
}
