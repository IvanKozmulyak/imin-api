package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.util.Times;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.v2.core.Account;
import com.stripe.param.v2.core.AccountRetrieveParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Fetches a Stripe v2 Account and projects the relevant fields onto the
 * {@link Organization} columns added in V33. Pure projection lives in
 * {@link #applyTo(Organization, Account)} so it's unit-testable without
 * Stripe; {@link #syncFromStripe(String)} is the live path used by the
 * webhook and the lazy first-read.
 *
 * <p>The v2 Account exposes per-entry requirements via {@code requirements.entries[]}
 * — each Entry has a {@code description} (machine-readable requirement code,
 * e.g. "individual.verification.document") and a {@code minimum_deadline.status}
 * (one of {@code currently_due}, {@code eventually_due}, {@code past_due}). The
 * mirror collects entries by deadline status into the two jsonb arrays.
 */
@Service
public class StripeConnectStatusMirror {

    private static final Logger log = LoggerFactory.getLogger(StripeConnectStatusMirror.class);

    private final StripeClient stripeClient;
    private final OrganizationRepository orgs;
    private final StripePayoutScheduleService payoutScheduleService;

    public StripeConnectStatusMirror(StripeClient stripeClient,
                                     OrganizationRepository orgs,
                                     StripePayoutScheduleService payoutScheduleService) {
        this.stripeClient = stripeClient;
        this.orgs = orgs;
        this.payoutScheduleService = payoutScheduleService;
    }

    /** Webhook + lazy-backfill entry. Looks up org by stripeAccountId, fetches, persists. */
    @Transactional
    public void syncFromStripe(String stripeAccountId) {
        if (stripeAccountId == null || stripeAccountId.isBlank()) return;
        Organization org = orgs.findByStripeAccountId(stripeAccountId).orElse(null);
        if (org == null) {
            log.warn("[stripe-mirror] no org for stripeAccountId={} — skipping", stripeAccountId);
            return;
        }

        AccountRetrieveParams params = AccountRetrieveParams.builder()
                .addInclude(AccountRetrieveParams.Include.CONFIGURATION__RECIPIENT)
                .addInclude(AccountRetrieveParams.Include.REQUIREMENTS)
                .build();
        Account account;
        try {
            account = stripeClient.v2().core().accounts().retrieve(stripeAccountId, params);
        } catch (StripeException e) {
            log.error("[stripe-mirror] retrieve failed for {}: {}", stripeAccountId, e.getMessage(), e);
            return; // leave existing columns; the next webhook will retry
        }

        applyTo(org, account);
        orgs.save(org);
        // Track B Phase 1: the moment payouts go active, flip the account to a manual
        // payout schedule (idempotent; inert when the kill-switch is off). ensureManual
        // runs in its own REQUIRES_NEW tx, so a Stripe failure there cannot roll back the
        // mirror projection just saved above. Stays out of the static applyTo(...) — that
        // is a pure, Stripe-free projection. Fires once on the ACTIVE transition on both
        // the webhook fast path and the StripeConnectStatusSweeper backstop.
        if (org.isStripePayoutsEnabled() && !org.isStripePayoutScheduleManual()) {
            payoutScheduleService.ensureManual(org);
        }
        log.info("[stripe-mirror] persisted state={} payouts={} currentlyDue={} for {}",
                org.getStripeConnectState(), org.isStripePayoutsEnabled(),
                org.getStripeRequirementsCurrentlyDue().size(), stripeAccountId);
    }

    /** Pure projection — unit-testable, no Stripe call. */
    static void applyTo(Organization org, Account account) {
        String transferStatus = readTransferStatus(account);
        List<String> statusDetailCodes = readTransferStatusDetails(account, true);
        List<String> statusDetailResolutions = readTransferStatusDetails(account, false);

        boolean payoutsEnabled = "active".equals(transferStatus);
        List<String> currentlyDue = readRequirementFields(account, "currently_due");
        List<String> pastDue = readRequirementFields(account, "past_due");

        // "Submitted, now in review" is a CAPABILITY signal, not a requirements-deadline one.
        // The v2 minimum_deadline.status is only ever currently_due/eventually_due/past_due and
        // never carries a verification lifecycle, so the old {pending_verification, verified, …}
        // deadline allowlist was dead code that stranded submitted-but-in-review organizers on
        // ONBOARDING. The real lifecycle lives on
        // configuration.recipient.capabilities.stripe_balance.stripe_transfers:
        //   status == active                                  → enabled (payouts)
        //   status == pending / code requirements_pending_verification → submitted, Stripe verifying
        //   status == restricted | unsupported                → blocked (recoverable vs terminal)
        boolean pendingVerification = statusDetailCodes.contains("requirements_pending_verification");
        boolean inReview = pendingVerification
                || ("pending".equals(transferStatus) && !statusDetailCodes.contains("determining_status"));

        // details_submitted is sticky: once we observe submission (payouts active OR Stripe
        // actively verifying), never un-flag. A fresh/abandoned account sits at
        // restricted/unsupported with nothing in review, so it stays ONBOARDING.
        boolean observedSubmission = payoutsEnabled || inReview;
        if (observedSubmission) {
            org.setStripeDetailsSubmitted(true);
        }

        // Terminal-and-unrecoverable from the organizer's side: capability unsupported, or any
        // status-detail resolution == contact_stripe (Stripe must intervene; no self-serve CTA).
        boolean terminalDisabled = "unsupported".equals(transferStatus)
                || statusDetailResolutions.contains("contact_stripe");

        org.setStripePayoutsEnabled(payoutsEnabled);
        org.setStripeRequirementsCurrentlyDue(currentlyDue);
        org.setStripeRequirementsPastDue(pastDue);
        // disabledReason: surface the first capability status-detail code (e.g. unsupported_country,
        // requirements_past_due) as the actionable signal. Stripe leaves status_details empty when
        // the capability is active, so this is null for a healthy account.
        org.setStripeDisabledReason(
                (payoutsEnabled || statusDetailCodes.isEmpty()) ? null : statusDetailCodes.get(0));
        org.setStripeConnectState(derive(org, currentlyDue.isEmpty(), payoutsEnabled, terminalDisabled));
        org.setStripeConnectStatusUpdatedAt(Times.nowMicros());
    }

    private static StripeConnectState derive(Organization org, boolean noCurrentlyDue,
                                             boolean payoutsEnabled, boolean terminalDisabled) {
        if (org.getStripeAccountId() == null || org.getStripeAccountId().isBlank()) {
            return StripeConnectState.NOT_STARTED;
        }
        // Terminal block is checked before the submitted gate: an account can be unsupported
        // (e.g. unsupported country) before the organizer ever submits anything.
        if (terminalDisabled) {
            return StripeConnectState.DISABLED;
        }
        if (!org.isStripeDetailsSubmitted()) {
            return StripeConnectState.ONBOARDING;
        }
        if (!noCurrentlyDue) {
            return StripeConnectState.RESTRICTED;
        }
        if (payoutsEnabled) {
            return StripeConnectState.ACTIVE;
        }
        return StripeConnectState.PENDING_VERIFICATION;
    }

    private static String readTransferStatus(Account a) {
        try {
            return a.getConfiguration().getRecipient().getCapabilities()
                    .getStripeBalance().getStripeTransfers().getStatus();
        } catch (NullPointerException ignored) { return null; }
    }

    /**
     * Reads the recipient {@code stripe_transfers} capability {@code status_details[]} —
     * {@code code=true} returns the machine-readable codes, otherwise the resolutions.
     */
    private static List<String> readTransferStatusDetails(Account a, boolean code) {
        try {
            var details = a.getConfiguration().getRecipient().getCapabilities()
                    .getStripeBalance().getStripeTransfers().getStatusDetails();
            if (details == null) return List.of();
            List<String> out = new ArrayList<>();
            for (var d : details) {
                String v = code ? d.getCode() : d.getResolution();
                if (v != null) out.add(v);
            }
            return out;
        } catch (NullPointerException ignored) {
            return List.of();
        }
    }

    private static List<String> readRequirementFields(Account a, String status) {
        try {
            var entries = a.getRequirements().getEntries();
            if (entries == null) return List.of();
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (var entry : entries) {
                String entryStatus = entry.getMinimumDeadline() == null
                        ? null : entry.getMinimumDeadline().getStatus();
                if (status.equals(entryStatus) && entry.getDescription() != null) {
                    out.add(entry.getDescription());
                }
            }
            return new ArrayList<>(out);
        } catch (NullPointerException ignored) {
            return List.of();
        }
    }
}
