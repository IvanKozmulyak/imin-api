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

    public StripeConnectStatusMirror(StripeClient stripeClient, OrganizationRepository orgs) {
        this.stripeClient = stripeClient;
        this.orgs = orgs;
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
        log.info("[stripe-mirror] persisted state={} payouts={} currentlyDue={} for {}",
                org.getStripeConnectState(), org.isStripePayoutsEnabled(),
                org.getStripeRequirementsCurrentlyDue().size(), stripeAccountId);
    }

    /** Pure projection — unit-testable, no Stripe call. */
    static void applyTo(Organization org, Account account) {
        boolean payoutsEnabled = "active".equals(readTransferStatus(account));
        String minDeadlineStatus = readMinimumDeadlineStatus(account);
        List<String> currentlyDue = readRequirementFields(account, "currently_due");
        List<String> pastDue = readRequirementFields(account, "past_due");

        // details_submitted is sticky: once we observe submission, never un-flag.
        // Sources that indicate submission: payouts active, OR deadline status != currently_due
        // (e.g., pending_verification, verified, past_due — anything that means Stripe is past
        // the initial intake), OR currently_due empty for a non-fresh account.
        boolean observedSubmission = payoutsEnabled
                || (minDeadlineStatus != null && !"currently_due".equals(minDeadlineStatus));
        if (observedSubmission) {
            org.setStripeDetailsSubmitted(true);
        }

        org.setStripePayoutsEnabled(payoutsEnabled);
        org.setStripeRequirementsCurrentlyDue(currentlyDue);
        org.setStripeRequirementsPastDue(pastDue);
        // The v2 Requirements object has no top-level disabled_reason; we leave the
        // column null. (The legacy v1 disabled_reason isn't surfaced on v2 accounts
        // — the per-entry impact / errors carry the equivalent context.)
        org.setStripeDisabledReason(null);
        org.setStripeConnectState(derive(org, currentlyDue.isEmpty(), payoutsEnabled));
        org.setStripeConnectStatusUpdatedAt(Times.nowMicros());
    }

    private static StripeConnectState derive(Organization org, boolean noCurrentlyDue, boolean payoutsEnabled) {
        if (org.getStripeAccountId() == null || org.getStripeAccountId().isBlank()) {
            return StripeConnectState.NOT_STARTED;
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

    private static String readMinimumDeadlineStatus(Account a) {
        try {
            return a.getRequirements().getSummary().getMinimumDeadline().getStatus();
        } catch (NullPointerException ignored) { return null; }
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
