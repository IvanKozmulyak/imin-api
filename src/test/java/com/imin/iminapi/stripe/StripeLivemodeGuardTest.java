package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.stripe.StripeClient;
import com.stripe.model.v2.core.Account;
import com.stripe.param.v2.core.AccountCreateParams;
import com.stripe.service.V2Services;
import com.stripe.service.v2.CoreService;
import com.stripe.service.v2.core.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The V129 livemode guard: {@code organizations.stripe_livemode} records which Stripe mode
 * minted the stored {@code acct_}, and the checkout readiness read refuses an org whose
 * recorded mode contradicts the running key. Without it a key swap is invisible —
 * {@code StripeConnectStatusMirror.syncFromStripe} swallows the resulting 404 and leaves the
 * mirror reporting the org as able to receive money it cannot receive.
 */
class StripeLivemodeGuardTest {

    private StripeClient stripeClient;
    private AccountService accountService;
    private OrganizationRepository orgs;
    private StripeConnectStatusMirror mirror;
    private StripeProperties props;

    private final UUID orgId = UUID.randomUUID();
    private final AuthPrincipal principal =
            new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());

    @BeforeEach
    void setUp() throws Exception {
        stripeClient = mock(StripeClient.class);
        V2Services v2Services = mock(V2Services.class);
        CoreService coreService = mock(CoreService.class);
        accountService = mock(AccountService.class);
        when(stripeClient.v2()).thenReturn(v2Services);
        when(v2Services.core()).thenReturn(coreService);
        when(coreService.accounts()).thenReturn(accountService);

        Account created = mock(Account.class);
        when(created.getId()).thenReturn("acct_new");
        when(accountService.create(any(AccountCreateParams.class))).thenReturn(created);

        orgs = mock(OrganizationRepository.class);
        when(orgs.lockAndReadStripeAccountId(orgId)).thenReturn(Optional.empty());
        mirror = mock(StripeConnectStatusMirror.class);
        props = new StripeProperties();
    }

    @Test
    void createStampsLivemodeTrueUnderARestrictedLiveKey() {
        props.setSecretKey("rk_live_abc123");
        Organization org = unconnectedOrg();
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        service().getOrCreateAccount(principal, orgId);

        assertThat(savedOrg().getStripeLivemode()).isTrue();
    }

    @Test
    void createStampsLivemodeFalseUnderATestKey() {
        props.setSecretKey("sk_test_abc123");
        Organization org = unconnectedOrg();
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        service().getOrCreateAccount(principal, orgId);

        assertThat(savedOrg().getStripeLivemode()).isFalse();
    }

    @Test
    void getStatusLiveRefusesAnOrgWhoseStoredModeDiffers() {
        props.setSecretKey("sk_live_abc123");
        Organization org = connectedOrg();
        org.setStripeLivemode(false);   // account minted under a test key
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService.StatusResult result = service().getStatusLive(orgId);

        assertThat(result.state()).isEqualTo(StripeConnectState.NOT_STARTED);
        assertThat(result.readyToReceivePayments()).isFalse();
        assertThat(result.accountId()).isNull();
        // Refused locally: the Stripe round trip would only answer 404 and the mirror would
        // swallow it, leaving the stale ACTIVE in place.
        verify(mirror, never()).syncFromStripe(any());
    }

    @Test
    void getStatusLiveAllowsAnOrgWithNoRecordedMode() {
        props.setSecretKey("sk_live_abc123");
        Organization org = connectedOrg();
        org.setStripeLivemode(null);    // never stamped — V129 backfills nothing
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService.StatusResult result = service().getStatusLive(orgId);

        assertThat(result.state()).isEqualTo(StripeConnectState.ACTIVE);
        assertThat(result.readyToReceivePayments()).isTrue();
        assertThat(result.accountId()).isEqualTo("acct_live_1");
    }

    @Test
    void getOrCreateAccountReplacesAnAccountFromTheOtherMode() {
        props.setSecretKey("sk_live_abc123");
        Organization org = connectedOrg();
        org.setStripeLivemode(false);           // acct_live_1 was actually minted by a test key
        org.setStripePayoutScheduleManual(true);
        org.setStripeRequirementsCurrentlyDue(new java.util.ArrayList<>(List.of("individual.id_number")));
        org.setStripeRequirementsPastDue(new java.util.ArrayList<>(List.of("individual.verification.document")));
        org.setStripeDisabledReason("requirements.past_due");
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));
        // The row lock reads the same stale id and mode back; it must not early-return on them.
        when(orgs.lockAndReadStripeAccountId(orgId)).thenReturn(Optional.of("acct_live_1"));
        when(orgs.lockAndReadStripeLivemode(orgId)).thenReturn(Optional.of(false));

        StripeConnectService.ConnectResult result = service().getOrCreateAccount(principal, orgId);

        assertThat(result.created()).isTrue();
        assertThat(result.accountId()).isEqualTo("acct_new");
        Organization saved = savedOrg();
        assertThat(saved.getStripeAccountId()).isEqualTo("acct_new");
        assertThat(saved.getStripeLivemode()).as("stamped with the mode that minted it").isTrue();
        // The mirror described the abandoned account — carried over, it would report an org as
        // ACTIVE on an account this key cannot even read.
        assertThat(saved.getStripeConnectState()).isEqualTo(StripeConnectState.NOT_STARTED);
        assertThat(saved.isStripePayoutsEnabled()).isFalse();
        assertThat(saved.isStripeDetailsSubmitted()).isFalse();
        assertThat(saved.isStripePayoutScheduleManual()).isFalse();
        assertThat(saved.getStripeRequirementsCurrentlyDue()).isEmpty();
        assertThat(saved.getStripeRequirementsPastDue()).isEmpty();
        assertThat(saved.getStripeDisabledReason()).isNull();
        assertThat(saved.getStripeConnectStatusUpdatedAt()).isNull();
    }

    /**
     * The loser of a concurrent connect on a mismatched org. Its own entity still shows the
     * abandoned test-mode account, but the locked row already carries the winner's live one —
     * minting a second account here would orphan the winner's and split the org's money.
     */
    @Test
    void getOrCreateAccountReturnsTheWinnersAccountWhenTheLockedRowIsAlreadyCorrect() throws Exception {
        props.setSecretKey("sk_live_abc123");
        Organization org = connectedOrg();
        org.setStripeLivemode(false);           // the stale pre-lock read
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));
        when(orgs.lockAndReadStripeAccountId(orgId)).thenReturn(Optional.of("acct_winner"));
        when(orgs.lockAndReadStripeLivemode(orgId)).thenReturn(Optional.of(true));

        StripeConnectService.ConnectResult result = service().getOrCreateAccount(principal, orgId);

        assertThat(result.accountId()).isEqualTo("acct_winner");
        assertThat(result.created()).isFalse();
        verify(accountService, never()).create(any(AccountCreateParams.class));
        verify(orgs, never()).save(any(Organization.class));
    }

    @Test
    void getOrCreateAccountKeepsAnAccountFromTheRunningMode() throws Exception {
        props.setSecretKey("sk_live_abc123");
        Organization org = connectedOrg();
        org.setStripeLivemode(true);
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService.ConnectResult result = service().getOrCreateAccount(principal, orgId);

        assertThat(result.accountId()).isEqualTo("acct_live_1");
        assertThat(result.created()).isFalse();
        verify(accountService, never()).create(any(AccountCreateParams.class));
        verify(orgs, never()).save(any(Organization.class));
    }

    @Test
    void getOrCreateAccountKeepsAnAccountWithNoRecordedMode() throws Exception {
        props.setSecretKey("sk_live_abc123");
        Organization org = connectedOrg();
        org.setStripeLivemode(null);            // never stamped — V129 backfills nothing
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService.ConnectResult result = service().getOrCreateAccount(principal, orgId);

        assertThat(result.accountId()).as("unknown is never a mismatch").isEqualTo("acct_live_1");
        assertThat(result.created()).isFalse();
        verify(accountService, never()).create(any(AccountCreateParams.class));
    }

    @Test
    void getStatusRefusesAnOrgWhoseStoredModeDiffers() {
        props.setSecretKey("sk_live_abc123");
        Organization org = connectedOrg();
        org.setStripeLivemode(false);
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        StripeConnectService.StatusResult result = service().getStatus(principal, orgId);

        // The dashboard renders NOT_STARTED as the "connect Stripe" CTA, which is the only
        // action that can fix this org.
        assertThat(result.state()).isEqualTo(StripeConnectState.NOT_STARTED);
        assertThat(result.readyToReceivePayments()).isFalse();
        assertThat(result.accountId()).isNull();
        verify(mirror, never()).syncFromStripe(any());
    }

    @Test
    void getStatusLiveAllowsAMatchingMode() {
        props.setSecretKey("sk_live_abc123");
        Organization org = connectedOrg();
        org.setStripeLivemode(true);
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));

        assertThat(service().getStatusLive(orgId).readyToReceivePayments()).isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private StripeConnectService service() {
        return new StripeConnectService(stripeClient, orgs, props, null, mirror);
    }

    private Organization unconnectedOrg() {
        Organization org = new Organization();
        org.setId(orgId);
        org.setName("Org");
        org.setContactEmail("o@test.example");
        org.setCountry("DE");
        return org;
    }

    /** Connected and ACTIVE with a fresh mirror, so nothing but the guard can refuse it. */
    private Organization connectedOrg() {
        Organization org = unconnectedOrg();
        org.setStripeAccountId("acct_live_1");
        org.setStripeConnectState(StripeConnectState.ACTIVE);
        org.setStripePayoutsEnabled(true);
        org.setStripeDetailsSubmitted(true);
        org.setStripeConnectStatusUpdatedAt(Instant.now());
        return org;
    }

    private Organization savedOrg() {
        ArgumentCaptor<Organization> captor = ArgumentCaptor.forClass(Organization.class);
        verify(orgs).save(captor.capture());
        return captor.getValue();
    }
}
