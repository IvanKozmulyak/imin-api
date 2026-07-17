package com.imin.iminapi.marketing.dto;

import java.util.List;

/**
 * Org-wide "signal health" funnel for the Channels tab's Meta card (spec §8).
 *
 * <p>The card compares the org's real sales funnel against what Meta actually
 * received, so a gap surfaces silent signal loss. The Channels tab has no single
 * event in scope, so this read is org-scoped over ALL of the org's active events
 * for a rolling {@link #windowDays()}-day window.
 *
 * <h3>Stage mapping (3 real stages → Meta vocabulary)</h3>
 * Our real funnel model has exactly three stages — {@code PAGE_VIEW},
 * {@code CHECKOUT_START}, {@code PAYMENTS_COMPLETED} — sourced from the
 * {@code event_funnel_events} beacon table (V41/V43) plus the {@code orders}
 * table. We map them onto Meta's event names one-to-one:
 * <ul>
 *   <li>{@code PAGE_VIEW}          → {@code PageView}</li>
 *   <li>{@code CHECKOUT_START}     → {@code InitiateCheckout}</li>
 *   <li>{@code PAYMENTS_COMPLETED} → {@code Purchase}</li>
 * </ul>
 * Meta's canonical 4-stage funnel (PageView → ViewContent → InitiateCheckout →
 * Purchase) also has a {@code ViewContent} stage. We have <b>no</b> counterpart
 * for it in our data, so it is deliberately <b>omitted</b> rather than
 * synthesized — inventing it would fabricate a stage the org never instrumented.
 *
 * <h3>Which numbers are real</h3>
 * <ul>
 *   <li>{@link Stage#iminCount()} is <b>always</b> a real, org-scoped count:
 *       distinct beacon sessions per stage for the two upper stages, and the
 *       order count for Purchase.</li>
 *   <li>{@link Stage#metaReceivedCount()} is the Meta-side "received" count and
 *       is <b>only</b> populated where it can be honestly sourced. Today our
 *       server-side Meta CAPI outbox ({@code meta_capi_events}) emits
 *       <b>Purchase events only</b>, so Purchase carries the count of CAPI
 *       events actually delivered to Meta ({@code status = 'sent'}) in the same
 *       window — the number a signal-loss gap is measured against. PageView and
 *       InitiateCheckout have no server-side Meta record (they fire only in the
 *       browser Pixel, which we do not count per-stage org-wide), so their
 *       {@code metaReceivedCount} is {@code null}. It is never fabricated.</li>
 * </ul>
 * A {@code null} {@code metaReceivedCount} means "not measurable on the Meta
 * side", not zero. The imin-side sales funnel stands on its own and is the
 * honest deliverable.
 */
public record MetaFunnelDto(
        int windowDays,
        List<Stage> stages) {

    /**
     * One funnel stage.
     *
     * @param metaEvent         Meta event vocabulary name (PageView / InitiateCheckout / Purchase)
     * @param iminStage         our real funnel stage name (PAGE_VIEW / CHECKOUT_START / PAYMENTS_COMPLETED)
     * @param iminCount         real org-scoped imin-side count for the window (always present)
     * @param metaReceivedCount count Meta received server-side, or {@code null} where not sourceable
     */
    public record Stage(
            String metaEvent,
            String iminStage,
            long iminCount,
            Long metaReceivedCount) {}
}
