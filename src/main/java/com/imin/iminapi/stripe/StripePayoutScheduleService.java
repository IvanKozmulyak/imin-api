package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.BalanceSettingsUpdateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Track B (manual payouts) Phase 1: flips a payout-eligible connected account to
 * a <strong>manual</strong> payout schedule via the account-scoped Balance
 * Settings API, so the organizer's net sits on their connected balance instead of
 * being auto-disbursed by Stripe. The Phase 2 post-event job then creates the
 * actual bank {@code Payout} against that held balance.
 *
 * <p><strong>Inert when the kill-switch is off.</strong> Every entry point returns
 * immediately when {@link StripeProperties#isPayoutScheduleManual()} is false
 * ({@code STRIPE_PAYOUT_SCHEDULE_MANUAL}, default false) — nothing flips a schedule
 * on deploy.
 *
 * <p><strong>Stripe-FIRST, flag-only-on-success.</strong> {@link #ensureManual} runs
 * in its own {@link Propagation#REQUIRES_NEW} transaction so a Stripe failure here can
 * never roll back the caller's already-committed mirror projection
 * ({@link StripeConnectStatusMirror#syncFromStripe}). It calls Stripe first and writes
 * {@code stripe_payout_schedule_manual = true} <em>only</em> on success; on
 * {@link StripeException} it logs WARN and returns <em>without</em> writing the flag, so
 * the account is retried by the backfill sweeper or the next ACTIVE transition. It never
 * throws into the onboarding flow.
 *
 * <p><strong>Idempotent twice over:</strong> the {@code stripe_payout_schedule_manual}
 * column short-circuits a repeat call, and {@code balanceSettings().update(MANUAL)} on an
 * already-manual account is itself a natural no-op.
 */
@Service
public class StripePayoutScheduleService {

    private static final Logger log = LoggerFactory.getLogger(StripePayoutScheduleService.class);

    private final StripeClient stripeClient;
    private final OrganizationRepository orgs;
    private final StripeProperties props;

    public StripePayoutScheduleService(StripeClient stripeClient,
                                       OrganizationRepository orgs,
                                       StripeProperties props) {
        this.stripeClient = stripeClient;
        this.orgs = orgs;
        this.props = props;
    }

    /**
     * Set the org's connected account to a manual payout schedule, idempotently.
     *
     * <p>Body order is load-bearing: kill-switch gate, then idempotency guard, then
     * Stripe call FIRST, then flag-only-on-success. {@link Propagation#REQUIRES_NEW}
     * isolates this from the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureManual(Organization org) {
        if (!props.isPayoutScheduleManual()) return;            // inert when flag off
        if (org.isStripePayoutScheduleManual()) return;          // already done (V44 column)

        String acct = org.getStripeAccountId();
        RequestOptions onAcct = RequestOptions.builder().setStripeAccount(acct).build();
        BalanceSettingsUpdateParams manual = BalanceSettingsUpdateParams.builder()
                .setPayments(BalanceSettingsUpdateParams.Payments.builder()
                        .setPayouts(BalanceSettingsUpdateParams.Payments.Payouts.builder()
                                .setSchedule(BalanceSettingsUpdateParams.Payments.Payouts.Schedule.builder()
                                        .setInterval(BalanceSettingsUpdateParams.Payments.Payouts.Schedule.Interval.MANUAL)
                                        .build())
                                .build())
                        .build())
                .build();

        try {
            stripeClient.balanceSettings().update(manual, onAcct);   // Stripe FIRST
            // Flag ONLY on success, and ONLY the flag column — a targeted modifying query
            // rather than a full-entity save of the passed instance. The caller
            // (StripeConnectStatusMirror.syncFromStripe) holds this same `org` as the OUTER
            // tx's managed entity; re-saving it here from the inner REQUIRES_NEW tx would let
            // an outer-tx rollback after this inner commit strand the flag ahead of the mirror.
            orgs.markPayoutScheduleManual(org.getId());
            // Keep the caller's in-memory entity consistent for the rest of the request.
            org.setStripePayoutScheduleManual(true);
            log.info("[payout-schedule] set MANUAL for org={} acct={}", org.getId(), acct);
        } catch (StripeException e) {
            // WARN and return WITHOUT writing the flag → backfill / next ACTIVE transition retries.
            // Never throw into onboarding, never strand the account on Stripe's auto schedule.
            log.warn("[payout-schedule] failed to set MANUAL for org={} acct={} — will retry",
                    org.getId(), acct, e);
        }
    }
}
