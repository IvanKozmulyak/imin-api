package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.stripe.StripeClient;
import com.stripe.exception.ApiException;
import com.stripe.model.v2.core.Account;
import com.stripe.param.v2.core.AccountRetrieveParams;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeConnectStatusMirrorTest {

    @Test
    void derive_active_when_transfers_active_and_no_currently_due() {
        Organization org = newOrg();
        Account account = StripeFixtures.accountActive("acct_1");

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.ACTIVE);
        assertThat(org.isStripePayoutsEnabled()).isTrue();
        assertThat(org.isStripeDetailsSubmitted()).isTrue();
        assertThat(org.getStripeRequirementsCurrentlyDue()).isEmpty();
        assertThat(org.getStripeDisabledReason()).isNull();
    }

    @Test
    void derive_restricted_when_details_submitted_and_currently_due_nonempty() {
        Organization org = newOrg();
        org.setStripeDetailsSubmitted(true); // previously submitted (reached pending/active before)
        Account account = StripeFixtures.accountWithCurrentlyDue(
                "acct_2", List.of("individual.verification.document", "business_profile.url"));

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.RESTRICTED);
        assertThat(org.getStripeRequirementsCurrentlyDue())
                .containsExactlyInAnyOrder("individual.verification.document", "business_profile.url");
    }

    @Test
    void derive_pending_verification_from_capability_pending_not_deadline() {
        // The in-review signal is the capability (status=pending + status_details.code=
        // requirements_pending_verification), NOT minimum_deadline.status (which can only ever
        // be currently_due/eventually_due/past_due). This is the case that was previously
        // undetectable and stranded organizers on "Continue onboarding".
        Organization org = newOrg();
        Account account = StripeFixtures.accountPendingVerification("acct_3");

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.PENDING_VERIFICATION);
        assertThat(org.isStripeDetailsSubmitted()).isTrue();
        assertThat(org.isStripePayoutsEnabled()).isFalse();
    }

    @Test
    void derive_onboarding_when_account_exists_but_not_submitted() {
        Organization org = newOrg();
        Account account = StripeFixtures.accountOnboarding("acct_4",
                List.of("individual.first_name", "individual.last_name"));

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.ONBOARDING);
        assertThat(org.isStripeDetailsSubmitted()).isFalse();
    }

    @Test
    void derive_onboarding_when_fresh_account_all_eventually_due() {
        // Organizer created the account but abandoned onboarding: nothing submitted, every
        // requirement still eventually_due. This must read as ONBOARDING (show "Continue
        // onboarding"), NOT PENDING_VERIFICATION — a fresh restricted capability is the
        // untouched baseline, not evidence the organizer submitted anything.
        Organization org = newOrg();
        Account account = StripeFixtures.accountFreshEventuallyDue("acct_fresh",
                List.of("individual.first_name", "individual.dob.day", "bank_account"));

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.ONBOARDING);
        assertThat(org.isStripeDetailsSubmitted()).isFalse();
        assertThat(org.isStripePayoutsEnabled()).isFalse();
        assertThat(org.getStripeRequirementsCurrentlyDue()).isEmpty();
    }

    @Test
    void derive_disabled_when_capability_unsupported_and_records_reason() {
        // Terminal/unrecoverable: country/business/entity unsupported. Must NOT show as a
        // recoverable RESTRICTED/ONBOARDING (no self-serve CTA helps); surface DISABLED and
        // keep the machine-readable reason for the FE/support.
        Organization org = newOrg();
        Account account = StripeFixtures.accountDisabledUnsupported("acct_disabled");

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.DISABLED);
        assertThat(org.isStripePayoutsEnabled()).isFalse();
        assertThat(org.getStripeDisabledReason()).isEqualTo("unsupported_country");
    }

    @Test
    void details_submitted_is_sticky_once_true() {
        Organization org = newOrg();
        org.setStripeDetailsSubmitted(true);
        // Even if the latest account snapshot looks like fresh onboarding (e.g., Stripe
        // re-opened requirements), once we've seen submission we never un-flag it.
        Account account = StripeFixtures.accountOnboarding("acct_5", List.of("foo"));

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.isStripeDetailsSubmitted()).isTrue();
        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.RESTRICTED);
    }

    // ── syncFromStripe wiring: the ensureManual (Track B Phase 1) hook ──────────────
    // The mirror flips the account to a MANUAL payout schedule exactly on the
    // ACTIVE + payoutsEnabled transition, and a Stripe failure there must never disturb
    // the just-persisted mirror projection (ensureManual runs in its own REQUIRES_NEW tx
    // and swallows StripeException). These wire the live syncFromStripe path with the
    // collaborators mocked.

    @Test
    void syncFromStripe_invokes_ensureManual_on_active_payouts_enabled_transition() throws Exception {
        StripeClient stripe = mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripePayoutScheduleService schedule = mock(StripePayoutScheduleService.class);

        Organization org = newOrg();   // starts not-yet-manual (flag false)
        when(orgs.findByStripeAccountId("acct_active")).thenReturn(Optional.of(org));
        when(stripe.v2().core().accounts().retrieve(eq("acct_active"), any(AccountRetrieveParams.class)))
                .thenReturn(StripeFixtures.accountActive("acct_active"));

        new StripeConnectStatusMirror(stripe, orgs, schedule).syncFromStripe("acct_active");

        // Mirror projection landed ACTIVE + payouts enabled...
        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(orgs).save(saved.capture());
        assertThat(saved.getValue().getStripeConnectState()).isEqualTo(StripeConnectState.ACTIVE);
        assertThat(saved.getValue().isStripePayoutsEnabled()).isTrue();
        // ...and the manual-schedule flip fired for THIS org.
        verify(schedule).ensureManual(org);
    }

    @Test
    void syncFromStripe_does_not_invoke_ensureManual_when_payouts_not_enabled() throws Exception {
        StripeClient stripe = mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripePayoutScheduleService schedule = mock(StripePayoutScheduleService.class);

        Organization org = newOrg();
        when(orgs.findByStripeAccountId("acct_onb")).thenReturn(Optional.of(org));
        when(stripe.v2().core().accounts().retrieve(eq("acct_onb"), any(AccountRetrieveParams.class)))
                .thenReturn(StripeFixtures.accountOnboarding("acct_onb", List.of("individual.first_name")));

        new StripeConnectStatusMirror(stripe, orgs, schedule).syncFromStripe("acct_onb");

        verify(orgs).save(any());
        assertThat(org.isStripePayoutsEnabled()).isFalse();
        verify(schedule, never()).ensureManual(any());
    }

    @Test
    void syncFromStripe_persists_mirror_even_if_ensureManual_throws() throws Exception {
        StripeClient stripe = mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripePayoutScheduleService schedule = mock(StripePayoutScheduleService.class);

        Organization org = newOrg();
        when(orgs.findByStripeAccountId("acct_active2")).thenReturn(Optional.of(org));
        when(stripe.v2().core().accounts().retrieve(eq("acct_active2"), any(AccountRetrieveParams.class)))
                .thenReturn(StripeFixtures.accountActive("acct_active2"));
        // In prod ensureManual swallows StripeException internally; even if it somehow
        // surfaced a RuntimeException, the mirror projection (already saved above) stands.
        Mockito.doThrow(new RuntimeException("stripe down", new ApiException("boom", "req", "api_error", 500, null)))
                .when(schedule).ensureManual(any());

        try {
            new StripeConnectStatusMirror(stripe, orgs, schedule).syncFromStripe("acct_active2");
        } catch (RuntimeException ignored) {
            // a surfaced failure from the hook must not have prevented the mirror save
        }

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(orgs).save(saved.capture());
        assertThat(saved.getValue().getStripeConnectState()).isEqualTo(StripeConnectState.ACTIVE);
        assertThat(saved.getValue().isStripePayoutsEnabled()).isTrue();
        assertThat(saved.getValue().isStripeDetailsSubmitted()).isTrue();
    }

    private static Organization newOrg() {
        Organization o = new Organization();
        o.setName("Test");
        o.setSlug("test");
        o.setContactEmail("ops@example.com");
        o.setCountry("FR");
        o.setStripeAccountId("acct_test");
        return o;
    }
}
