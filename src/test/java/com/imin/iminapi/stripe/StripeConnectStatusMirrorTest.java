package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.stripe.model.v2.core.Account;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
