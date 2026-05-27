package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeConnectServiceStatusTest {

    @Test
    void status_reads_from_db_without_calling_stripe_when_already_synced() {
        StripeClient stripeClient = mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);

        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setId(orgId);
        org.setStripeAccountId("acct_1");
        org.setStripeConnectState(StripeConnectState.ACTIVE);
        org.setStripePayoutsEnabled(true);
        org.setStripeDetailsSubmitted(true);
        org.setStripeConnectStatusUpdatedAt(java.time.Instant.now());
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService svc = new StripeConnectService(
                stripeClient, orgs, new StripeProperties(), null, mirror);
        AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), orgId,
                com.imin.iminapi.model.UserRole.OWNER, UUID.randomUUID());

        StripeConnectService.StatusResult result = svc.getStatus(p, orgId);

        assertThat(result.state()).isEqualTo(StripeConnectState.ACTIVE);
        assertThat(result.readyToReceivePayments()).isTrue();
        assertThat(result.currentlyDue()).isEmpty();
        // Crucial: no live Stripe call when state is already synced.
        verify(mirror, never()).syncFromStripe(any());
    }

    @Test
    void status_triggers_lazy_sync_when_never_synced() {
        StripeClient stripeClient = mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);

        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setId(orgId);
        org.setStripeAccountId("acct_2");
        org.setStripeConnectState(StripeConnectState.ONBOARDING);
        org.setStripeConnectStatusUpdatedAt(null); // never synced
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService svc = new StripeConnectService(
                stripeClient, orgs, new StripeProperties(), null, mirror);
        AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), orgId,
                com.imin.iminapi.model.UserRole.OWNER, UUID.randomUUID());

        svc.getStatus(p, orgId);

        verify(mirror).syncFromStripe("acct_2");
    }

    @Test
    void status_returns_not_started_when_no_account() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        UUID orgId = UUID.randomUUID();
        Organization org = new Organization();
        org.setId(orgId);
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService svc = new StripeConnectService(
                mock(StripeClient.class), orgs, new StripeProperties(), null,
                mock(StripeConnectStatusMirror.class));
        AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), orgId,
                com.imin.iminapi.model.UserRole.OWNER, UUID.randomUUID());

        assertThat(svc.getStatus(p, orgId).state()).isEqualTo(StripeConnectState.NOT_STARTED);
    }
}
