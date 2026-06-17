package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.payout.PayoutRun;
import com.imin.iminapi.payout.PayoutRunRepository;
import com.imin.iminapi.payout.PayoutRunStatus;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.settlement.Settlement;
import com.imin.iminapi.settlement.SettlementObjectType;
import com.imin.iminapi.settlement.SettlementRepository;
import com.imin.iminapi.settlement.SettlementStatus;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.Payout;
import com.stripe.model.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Ingests Stripe {@code transfer.*}, {@code payout.*}, {@code charge.refunded} and
 * {@code charge.dispute.*} webhook payloads into the {@link Settlement} read-model
 * (Track A). Only {@code transfer.*} and {@code payout.*} ever create rows — one row per
 * Stripe object, keyed on {@link Settlement#getStripeObjectId()} (the {@code tr_}/{@code po_}
 * id), upserted on every delivery so a replay or a second distinct event id for the same
 * object converges on the same row rather than duplicating.
 *
 * <p>{@code charge.refunded} and {@code charge.dispute.*} are NOT payout objects — they NEVER
 * mint a row. They only annotate the status of an EXISTING transfer row (found via the charge's
 * {@code source_transfer}), and never overwrite its amount; if no such row exists they log and
 * skip. This keeps the read-model from ever surfacing refund/dispute markers as payouts.
 *
 * <p>This service moves NO money and holds no ledger. It is a projection of Stripe's own
 * payout/transfer state so the {@code /payouts} endpoints can read from our DB. Org
 * attribution is the connected account the object lives on
 * ({@code organizations.stripe_account_id}); rows whose account resolves to no org are
 * skipped (logged, not thrown) because there is nothing to attribute them to.
 *
 * <p>Called only from {@link StripeWebhookService}'s {@code @Transactional} V1 dispatch,
 * inside the {@code processed_webhook_events} dedup gate — so it inherits that transaction
 * and must NOT open its own. If a write throws, the surrounding dedup INSERT rolls back
 * with it and Stripe re-delivers.
 */
@Service
public class SettlementIngestService {

    private static final Logger log = LoggerFactory.getLogger(SettlementIngestService.class);

    private final SettlementRepository settlements;
    private final OrganizationRepository orgs;
    private final PayoutRunRepository payoutRuns;

    public SettlementIngestService(SettlementRepository settlements,
                                   OrganizationRepository orgs,
                                   PayoutRunRepository payoutRuns) {
        this.settlements = settlements;
        this.orgs = orgs;
        this.payoutRuns = payoutRuns;
    }

    /**
     * Upsert a {@link Settlement} from a Stripe {@link Transfer} ({@code transfer.created} /
     * {@code transfer.reversed}). Transfers have no {@code status} field of their own — we
     * derive REVERSED from {@code reversed==true} (or any reversed amount), else PENDING.
     * Org is the transfer destination ({@code acct_...}); falls back to the event's connected
     * account when present.
     *
     * @param transfer        the deserialized Stripe Transfer (non-null).
     * @param connectedAccount the {@code event.getAccount()} fallback for org resolution; may be null.
     * @param reversedEvent   true when the source event is {@code transfer.reversed}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ingestTransfer(Transfer transfer, String connectedAccount, boolean reversedEvent) {
        if (transfer == null) return;
        String acctId = firstNonBlank(transfer.getDestination(), connectedAccount);
        Organization org = resolveOrg(acctId, "transfer", transfer.getId());
        if (org == null) return;

        boolean reversed = reversedEvent
                || Boolean.TRUE.equals(transfer.getReversed())
                || (transfer.getAmountReversed() != null && transfer.getAmountReversed() > 0);
        SettlementStatus status = reversed ? SettlementStatus.REVERSED : SettlementStatus.PENDING;

        upsert(org.getId(), transfer.getId(), SettlementObjectType.TRANSFER,
                nz(transfer.getAmount()), currency(transfer.getCurrency()),
                status, null, null, null, eventIdOf(transfer.getMetadata()));

        log.info("[settlement-ingest] transfer {} org={} amount={} {} status={}",
                transfer.getId(), org.getId(), nz(transfer.getAmount()),
                currency(transfer.getCurrency()), status.toWire());
    }

    /**
     * Upsert a {@link Settlement} from a Stripe {@link Payout} ({@code payout.created} /
     * {@code payout.paid} / {@code payout.failed}). Payouts live ON the connected account, so
     * the org is resolved from the event's connected-account id ({@code event.getAccount()}) —
     * a payout carries no destination-org field and batches many transfers, so it has no
     * event metadata to attribute. Status maps from {@code payout.status}; arrival_date and
     * failure_message are persisted when present.
     *
     * <p><b>Track B reconciliation (plan §6):</b> after upserting the settlement
     * row, find the {@link PayoutRun} this {@code po_} belongs to (an imin-TRIGGERED
     * payout) and flip it to {@code PAID}/{@code FAILED} so the trigger ledger closes
     * the loop. The run's {@code event_id} is the per-event attribution payouts
     * otherwise lack (they carry no metadata) — it is written into the settlement
     * row's {@code event_ids} so {@code /payouts} history can label the event. A
     * payout with no matching run (e.g. a Stripe-auto payout, or one not triggered by
     * imin) just upserts the settlement row with null attribution, unchanged.
     *
     * @param payout          the deserialized Stripe Payout (non-null).
     * @param connectedAccount the {@code event.getAccount()} id this payout settled on; may be null.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ingestPayout(Payout payout, String connectedAccount) {
        if (payout == null) return;
        Organization org = resolveOrg(connectedAccount, "payout", payout.getId());
        if (org == null) return;

        SettlementStatus status = SettlementStatus.fromStripe(payout.getStatus());
        Instant arrival = payout.getArrivalDate() == null
                ? null : Instant.ofEpochSecond(payout.getArrivalDate());
        String failure = firstNonBlank(payout.getFailureMessage(), payout.getFailureCode());

        // Stamp paid_at when the payout has settled, so the "this month" window
        // buckets it by when the money actually arrived. Prefer Stripe's
        // arrival_date; fall back to ingest time when it isn't carried.
        Instant paidAt = (status == SettlementStatus.PAID)
                ? (arrival != null ? arrival : Instant.now())
                : null;

        // Track B reconciliation: match this po_ back to its trigger-ledger run.
        // Resolve attribution (run.event_id) FIRST so the upsert can stamp event_ids
        // in the same write — payouts carry no metadata of their own.
        PayoutRun run = payoutRuns.findByStripePayoutId(payout.getId()).orElse(null);
        String eventIds = (run != null) ? run.getEventId().toString() : null;

        upsert(org.getId(), payout.getId(), SettlementObjectType.PAYOUT,
                nz(payout.getAmount()), currency(payout.getCurrency()),
                status, arrival, paidAt, failure, eventIds);

        if (run != null) {
            if (status == SettlementStatus.PAID) {
                run.setStatus(PayoutRunStatus.PAID);
                run.setPaidAt(arrival != null ? arrival : Instant.now());
                payoutRuns.save(run);
                log.info("[payout-recon] run {} -> PAID (po={} event={} paidAt={})",
                        run.getId(), payout.getId(), run.getEventId(), run.getPaidAt());
            } else if (status == SettlementStatus.FAILED) {
                run.setStatus(PayoutRunStatus.FAILED);
                if (failure != null) run.setFailureReason(failure);
                payoutRuns.save(run);
                log.info("[payout-recon] run {} -> FAILED (po={} event={} reason={})",
                        run.getId(), payout.getId(), run.getEventId(), failure);
            }
            // Other statuses (e.g. in_transit/pending) leave the run at SUBMITTED.
        }

        log.info("[settlement-ingest] payout {} org={} amount={} {} status={} arrival={}",
                payout.getId(), org.getId(), nz(payout.getAmount()),
                currency(payout.getCurrency()), status.toWire(), arrival);
    }

    /**
     * Annotate the read-model on a {@link Dispute} ({@code charge.dispute.*}). A dispute is NOT a
     * payout or transfer object — it must NEVER mint a settlement row and must NEVER write a
     * payout-looking positive-amount row. The only safe action is to annotate the status of an
     * EXISTING transfer settlement row that backs the disputed charge.
     *
     * <p>We resolve the disputed charge ({@code dispute.getChargeObject()}, or via the
     * {@code dispute.getCharge()} id when the charge object was expanded) and read its
     * {@code source_transfer} (tr_...). If that transfer already has a settlement row, we flip its
     * status to FAILED while funds are at risk, or back to PAID when the dispute is won/reinstated
     * — leaving {@code amountMinor}/currency/eventIds untouched. If we can't resolve to an existing
     * row (no expanded charge, no source transfer, or no settlement for it) we simply log and skip:
     * disputes are not payouts, so a phantom row is worse than no annotation.
     *
     * @param dispute          the deserialized Stripe Dispute (non-null).
     * @param connectedAccount the {@code event.getAccount()} fallback for org resolution; may be null.
     * @param eventType        the canonical {@code charge.dispute.*} type, to branch behaviour.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ingestDispute(Dispute dispute, String connectedAccount, String eventType) {
        if (dispute == null) return;

        // Resolve the disputed charge → its source transfer (tr_...). The Dispute carries no
        // transfer id directly; we can only reach it through an expanded Charge object.
        Charge charge = dispute.getChargeObject();
        if (charge == null) {
            log.info("[settlement-ingest] dispute {} ({}) has no expanded charge — cannot resolve a "
                    + "source transfer, skipping (disputes are not payouts)", dispute.getId(), eventType);
            return;
        }
        String sourceTransfer = charge.getSourceTransfer();
        if (sourceTransfer == null || sourceTransfer.isBlank()) {
            log.info("[settlement-ingest] dispute {} ({}) charge {} has no source_transfer — nothing to "
                    + "annotate, skipping", dispute.getId(), eventType, charge.getId());
            return;
        }

        Settlement existing = settlements.findByStripeObjectId(sourceTransfer).orElse(null);
        if (existing == null) {
            log.info("[settlement-ingest] dispute {} ({}) sourceTransfer={} has no settlement row — "
                    + "skipping (never create a row for a dispute)", dispute.getId(), eventType, sourceTransfer);
            return;
        }

        // won / funds_reinstated → the money is back (settled); everything else (created,
        // funds_withdrawn, lost, under review) → funds at risk/withdrawn. Status ONLY — never the
        // amount, which keeps mirroring the real transfer.
        boolean reinstated = "charge.dispute.funds_reinstated".equals(eventType)
                || "won".equals(dispute.getStatus());
        SettlementStatus status = reinstated ? SettlementStatus.PAID : SettlementStatus.FAILED;
        String reason = firstNonBlank(dispute.getReason(), dispute.getStatus());

        existing.setStatus(status);
        if (reason != null) existing.setFailureReason(reason);
        settlements.save(existing);

        log.info("[settlement-ingest] dispute {} ({}) org={} sourceTransfer={} status={} reason={} "
                        + "(amount untouched)",
                dispute.getId(), eventType, existing.getOrgId(), sourceTransfer, status.toWire(), reason);
    }

    /**
     * Annotate the read-model on a {@link Charge} {@code charge.refunded}. A refund must ONLY
     * update an EXISTING settlement row for the charge's backing destination-charge transfer
     * ({@code source_transfer}, tr_...) — it must NEVER mint a new row and NEVER overwrite the
     * amount (the row keeps mirroring the real transfer's amount; the refund lives in the status).
     *
     * <p>Behaviour:
     * <ul>
     *   <li>no {@code source_transfer} (non-destination charge) → log + skip;</li>
     *   <li>no existing settlement row for that transfer → log + skip (do NOT create one — a
     *       refund clawback only makes sense against a transfer we already mirror);</li>
     *   <li>fully refunded ({@code charge.refunded==true}) → set status REVERSED only, leaving
     *       amountMinor/currency/eventIds untouched;</li>
     *   <li>partial refund ({@code charge.refunded==false}) → annotate the reason but make NO
     *       status or amount change (partials fire repeatedly and converge on the same row).</li>
     * </ul>
     *
     * <p>Org is the connected account on the charge's {@code transfer_data.destination}
     * (this codebase uses DESTINATION charges, so the org's account is there, never
     * {@code on_behalf_of}); {@code event.getAccount()} is the fallback. The resolved org is used
     * only for logging/attribution here — the row to mutate is found by source transfer id.
     *
     * @param charge           the deserialized Stripe Charge (non-null).
     * @param connectedAccount the {@code event.getAccount()} fallback for org resolution; may be null.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ingestChargeRefunded(Charge charge, String connectedAccount) {
        if (charge == null) return;
        String sourceTransfer = charge.getSourceTransfer();
        if (sourceTransfer == null || sourceTransfer.isBlank()) {
            log.info("[settlement-ingest] charge.refunded {} has no source_transfer — not a destination charge, skipping",
                    charge.getId());
            return;
        }

        // DESTINATION charges carry the org's connected account on transfer_data.destination —
        // never on on_behalf_of. Fall back to the event's connected account.
        String destination = charge.getTransferData() == null ? null : charge.getTransferData().getDestination();
        String acctId = firstNonBlank(destination, connectedAccount);
        Organization org = resolveOrg(acctId, "charge.refunded", charge.getId());
        if (org == null) return;

        // A refund only annotates a transfer we already mirror — never mints a row.
        Settlement existing = settlements.findByStripeObjectId(sourceTransfer).orElse(null);
        if (existing == null) {
            log.info("[settlement-ingest] charge.refunded {} sourceTransfer={} has no settlement row — "
                    + "skipping (never create a row from a refund)", charge.getId(), sourceTransfer);
            return;
        }

        boolean fullyRefunded = Boolean.TRUE.equals(charge.getRefunded());
        if (fullyRefunded) {
            // Status only — amountMinor keeps mirroring the real transfer.
            existing.setStatus(SettlementStatus.REVERSED);
            existing.setFailureReason("refunded");
        } else {
            // Partial refund: do NOT change status or amount; annotate the reason only.
            existing.setFailureReason("partially_refunded");
        }
        settlements.save(existing);

        log.info("[settlement-ingest] charge.refunded {} sourceTransfer={} org={} fullyRefunded={} status={} "
                        + "(amount untouched)",
                charge.getId(), sourceTransfer, org.getId(), fullyRefunded, existing.getStatus().toWire());
    }

    // ── internals ────────────────────────────────────────────────────────────────

    /**
     * Insert-or-update the one {@link Settlement} row for {@code stripeObjectId}. The
     * {@code stripe_object_id} UNIQUE constraint is the upsert key. On update we refresh the
     * mutable fields (status / amount / arrival / failure / event ids) and never re-key org or
     * type — the first ingest establishes those. {@code eventIds} only overwrites when the new
     * value is non-null, so a later metadata-less delivery doesn't wipe an attribution we
     * previously captured.
     */
    private void upsert(UUID orgId, String stripeObjectId, SettlementObjectType type,
                        long amountMinor, String currency, SettlementStatus status,
                        Instant arrivalAt, Instant paidAt, String failureReason, String eventIds) {
        Settlement s = settlements.findByStripeObjectId(stripeObjectId).orElseGet(() -> {
            Settlement fresh = new Settlement();
            fresh.setStripeObjectId(stripeObjectId);
            fresh.setOrgId(orgId);
            fresh.setObjectType(type);
            return fresh;
        });
        s.setAmountMinor(amountMinor);
        s.setCurrency(currency);
        s.setStatus(status);
        if (arrivalAt != null) s.setArrivalAt(arrivalAt);
        if (paidAt != null) s.setPaidAt(paidAt);
        if (failureReason != null) s.setFailureReason(failureReason);
        if (eventIds != null) s.setEventIds(eventIds);
        settlements.save(s);
    }

    /** Resolve the org for a connected-account id; logs + returns null (skip) when unattributable. */
    private Organization resolveOrg(String acctId, String label, String objectId) {
        if (acctId == null || acctId.isBlank()) {
            log.warn("[settlement-ingest] {} {} has no connected-account id — cannot attribute, skipping",
                    label, objectId);
            return null;
        }
        Organization org = orgs.findByStripeAccountId(acctId).orElse(null);
        if (org == null) {
            log.warn("[settlement-ingest] {} {} account={} has no org — skipping",
                    label, objectId, acctId);
        }
        return org;
    }

    /** Read the {@code event_id} metadata stamped at checkout, as a single-value CSV; null when absent. */
    private static String eventIdOf(java.util.Map<String, String> meta) {
        if (meta == null) return null;
        String raw = meta.get("event_id");
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static String currency(String c) {
        return c == null ? "eur" : c.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
