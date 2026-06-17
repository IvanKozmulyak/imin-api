package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.stripe.StripeClient;
import com.stripe.exception.ApiException;
import com.stripe.net.RequestOptions;
import com.stripe.param.BalanceSettingsUpdateParams;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Track B Phase 1 manual-payout flip. Stripe is mocked via
 * {@code RETURNS_DEEP_STUBS} so {@code stripeClient.balanceSettings().update(...)} can be
 * stubbed/verified without a live SDK call.
 */
class StripePayoutScheduleServiceTest {

    private static ApiException stripeBoom() {
        return new ApiException("boom", "req_1", "api_error", 500, null);
    }

    private static Organization eligibleOrg() {
        Organization o = new Organization();
        o.setId(UUID.randomUUID());
        o.setStripeAccountId("acct_eligible");
        o.setStripePayoutsEnabled(true);
        o.setStripePayoutScheduleManual(false);
        return o;
    }

    private static StripeProperties propsWithFlag(boolean on) {
        StripeProperties p = new StripeProperties();
        p.setPayoutScheduleManual(on);
        return p;
    }

    @Test
    void ensureManual_flips_flag_and_calls_balanceSettings_when_eligible() throws Exception {
        StripeClient stripe = Mockito.mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = Mockito.mock(OrganizationRepository.class);
        Organization org = eligibleOrg();

        new StripePayoutScheduleService(stripe, orgs, propsWithFlag(true)).ensureManual(org);

        // Stripe called exactly once with manual params on the account-scoped RequestOptions...
        verify(stripe.balanceSettings()).update(any(BalanceSettingsUpdateParams.class), any(RequestOptions.class));
        // ...and ONLY on success is the flag flipped — written via the isolated, column-only
        // modifying query (NOT a full-entity save of the OUTER tx's managed instance), then
        // mirrored onto the in-memory entity so the caller stays consistent.
        verify(orgs).markPayoutScheduleManual(org.getId());
        verify(orgs, never()).save(any());
        assertThat(org.isStripePayoutScheduleManual()).isTrue();
    }

    @Test
    void ensureManual_does_not_flip_flag_on_StripeException() throws Exception {
        StripeClient stripe = Mockito.mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = Mockito.mock(OrganizationRepository.class);
        Organization org = eligibleOrg();
        when(stripe.balanceSettings().update(any(BalanceSettingsUpdateParams.class), any(RequestOptions.class)))
                .thenThrow(stripeBoom());

        // Must NOT throw into the onboarding flow.
        new StripePayoutScheduleService(stripe, orgs, propsWithFlag(true)).ensureManual(org);

        // Flag stays false (retried by backfill / next ACTIVE transition); nothing persisted.
        assertThat(org.isStripePayoutScheduleManual()).isFalse();
        verify(orgs, never()).markPayoutScheduleManual(any());
        verify(orgs, never()).save(any());
    }

    @Test
    void ensureManual_is_a_noop_when_already_manual() throws Exception {
        StripeClient stripe = Mockito.mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = Mockito.mock(OrganizationRepository.class);
        Organization org = eligibleOrg();
        org.setStripePayoutScheduleManual(true);

        new StripePayoutScheduleService(stripe, orgs, propsWithFlag(true)).ensureManual(org);

        verify(stripe.balanceSettings(), never())
                .update(any(BalanceSettingsUpdateParams.class), any(RequestOptions.class));
        verify(orgs, never()).save(any());
    }

    @Test
    void ensureManual_is_a_noop_when_flag_off() throws Exception {
        StripeClient stripe = Mockito.mock(StripeClient.class, Mockito.RETURNS_DEEP_STUBS);
        OrganizationRepository orgs = Mockito.mock(OrganizationRepository.class);
        Organization org = eligibleOrg();

        new StripePayoutScheduleService(stripe, orgs, propsWithFlag(false)).ensureManual(org);

        // Kill-switch off ⇒ nothing flips a schedule, nothing is touched.
        verify(stripe.balanceSettings(), never())
                .update(any(BalanceSettingsUpdateParams.class), any(RequestOptions.class));
        assertThat(org.isStripePayoutScheduleManual()).isFalse();
        verify(orgs, never()).save(any());
    }
}
