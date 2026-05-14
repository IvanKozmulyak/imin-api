package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.stripe.StripeClient;
import com.stripe.model.v2.core.Account;
import com.stripe.param.v2.core.AccountCreateParams;
import com.stripe.param.v2.core.accounts.PersonCreateParams;
import com.stripe.service.V2Services;
import com.stripe.service.v2.CoreService;
import com.stripe.service.v2.core.AccountService;
import com.stripe.service.v2.core.accounts.PersonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private PersonService personService;
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
        personService = mock(PersonService.class);
        when(stripeClient.v2()).thenReturn(v2Services);
        when(v2Services.core()).thenReturn(coreService);
        when(coreService.accounts()).thenReturn(accountService);
        when(accountService.persons()).thenReturn(personService);

        orgs = mock(OrganizationRepository.class);
        props = new StripeProperties();
        svc = new StripeConnectService(stripeClient, orgs, props);

        Account created = mock(Account.class);
        when(created.getId()).thenReturn("acct_test_123");
        when(accountService.create(any(AccountCreateParams.class))).thenReturn(created);
    }

    @Test
    void nonFrOrg_sendsConfigurationRecipientPayload_andRejectsTokenBody() throws Exception {
        Organization org = org("US");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        // Empty token body succeeds for non-FR
        var result = svc.getOrCreateAccount(principal, orgId,
                StripeConnectService.ConnectTokens.empty());
        assertThat(result.accountId()).isEqualTo("acct_test_123");
        assertThat(result.created()).isTrue();

        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        verify(accountService, times(1)).create(captor.capture());
        AccountCreateParams sent = captor.getValue();
        assertThat(sent.getAccountToken()).isNull();
        assertThat(sent.getIdentity()).isNotNull();
        assertThat(sent.getIdentity().getCountry()).isEqualTo("US");
        assertThat(sent.getConfiguration()).isNotNull();
        // recipient.capabilities.stripe_balance.stripe_transfers.requested = true
        assertThat(sent.getConfiguration().getRecipient()).isNotNull();
        assertThat(sent.getConfiguration().getRecipient().getCapabilities()).isNotNull();
        verify(personService, never()).create(any(String.class), any(PersonCreateParams.class));
    }

    @Test
    void nonFrOrg_withTokenBody_is400() {
        Organization org = org("US");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> svc.getOrCreateAccount(principal, orgId,
                new StripeConnectService.ConnectTokens("ct_acct_x", null)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void frOrg_withBothTokens_setsAccountToken_andCreatesPerson() throws Exception {
        Organization org = org("FR");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        svc.getOrCreateAccount(principal, orgId,
                new StripeConnectService.ConnectTokens("ct_acct_fr", "ct_person_fr"));

        ArgumentCaptor<AccountCreateParams> captor = ArgumentCaptor.forClass(AccountCreateParams.class);
        verify(accountService).create(captor.capture());
        AccountCreateParams sent = captor.getValue();
        assertThat(sent.getAccountToken()).isEqualTo("ct_acct_fr");
        // FR path MUST NOT send identity or configuration server-side
        assertThat(sent.getIdentity()).isNull();
        assertThat(sent.getConfiguration()).isNull();

        ArgumentCaptor<PersonCreateParams> pcap = ArgumentCaptor.forClass(PersonCreateParams.class);
        verify(personService).create(eq("acct_test_123"), pcap.capture());
        assertThat(pcap.getValue().getPersonToken()).isEqualTo("ct_person_fr");
    }

    @Test
    void frOrg_missingAccountToken_is400() {
        Organization org = org("FR");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> svc.getOrCreateAccount(principal, orgId,
                new StripeConnectService.ConnectTokens(null, "ct_person_fr")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void frOrg_missingPersonToken_is400() {
        Organization org = org("FR");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> svc.getOrCreateAccount(principal, orgId,
                new StripeConnectService.ConnectTokens("ct_acct_fr", null)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void idempotent_skipsStripeWhenAccountAlreadyExists() throws Exception {
        Organization org = org("FR");
        org.setStripeAccountId("acct_existing");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        var result = svc.getOrCreateAccount(principal, orgId,
                StripeConnectService.ConnectTokens.empty());
        assertThat(result.accountId()).isEqualTo("acct_existing");
        assertThat(result.created()).isFalse();
        verify(accountService, never()).create(any(AccountCreateParams.class));
        verify(personService, never()).create(any(String.class), any(PersonCreateParams.class));
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
