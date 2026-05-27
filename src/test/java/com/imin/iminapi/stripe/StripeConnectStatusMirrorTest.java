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
    }

    @Test
    void derive_restricted_when_details_submitted_and_currently_due_nonempty() {
        Organization org = newOrg();
        org.setStripeDetailsSubmitted(true); // previously submitted
        Account account = StripeFixtures.accountWithCurrentlyDue(
                "acct_2", List.of("individual.verification.document", "business_profile.url"));

        StripeConnectStatusMirror.applyTo(org, account);

        assertThat(org.getStripeConnectState()).isEqualTo(StripeConnectState.RESTRICTED);
        assertThat(org.getStripeRequirementsCurrentlyDue())
                .containsExactlyInAnyOrder("individual.verification.document", "business_profile.url");
    }

    @Test
    void derive_pending_verification_when_submitted_no_currently_due_not_active() {
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
