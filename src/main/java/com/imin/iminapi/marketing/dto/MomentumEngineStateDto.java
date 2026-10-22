package com.imin.iminapi.marketing.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Real Momentum-engine state + activity log behind GET /api/v1/marketing/suggestions/state
 * (spec §6.4; FE type {@code MomentumEngineState} in imin-webapp marketing/types.ts). Every
 * counter is REAL (from the DB / existing services) or a literal 0 when the source genuinely
 * does not exist — nothing is a placeholder. No field carries prose; the webapp words it.
 *
 * <ul>
 *   <li>{@code watching} — the org's events currently eligible for Momentum evaluation
 *       (same candidate selection as {@code MomentumEvaluator.runOnce}).</li>
 *   <li>{@code waiting} — the org's live {@code suggested} suggestions.</li>
 *   <li>{@code approved30d} / {@code dismissed30d} — acted-on in the last 30 days.</li>
 *   <li>{@code attributedMinor} — ticket sales in minor units from the org's {@code origin='momentum'}
 *       campaigns created (= approved) in the last 30 days: per campaign, the lifetime sum of live orders
 *       tagged with it, each less succeeded refunds and lost chargebacks, floored at 0
 *       ({@code CampaignAttributionService.attributedRevenueMinorByCampaign}). {@code 0} when none.</li>
 *   <li>{@code minAudienceFloor} / {@code cooldownDays} — from {@code MomentumThresholds}.</li>
 *   <li>{@code log} — the 10 most recent non-{@code suggested} suggestions, newest first.</li>
 *   <li>{@code belowFloor} — moments of the org's watched events that the last pass, within 2 hours, could not
 *       draft because fewer than {@code floor} people can get the email.</li>
 *   <li>{@code rules} — the trigger thresholds the engine runs with now.</li>
 *   <li>{@code triggers} — the trigger wire values the engine implements, in declaration order.</li>
 * </ul>
 */
public record MomentumEngineStateDto(
        int watching,
        int waiting,
        int approved30d,
        int dismissed30d,
        long attributedMinor,
        int minAudienceFloor,
        int cooldownDays,
        List<LogEntry> log,
        List<FloorSkipEntry> belowFloor,
        Rules rules,
        List<String> triggers) {

    /**
     * One activity-log row. {@code at} is when it was acted on (when it was suggested for a row without one);
     * {@code eventName} is null when the event row is gone.
     */
    public record LogEntry(String status, String triggerType, UUID eventId, String eventName, Instant at) {}

    /** A moment the engine skipped: {@code sendable} of the {@code floor} needed can get the email. */
    public record FloorSkipEntry(UUID eventId, String eventName, String triggerType, int sendable, int floor,
                                 Instant checkedAt) {}

    /** Trigger thresholds from {@code MomentumThresholds}, plus the fixed clicked_no_buy window. */
    public record Rules(int launchAfterHours, int launchMaxSellThroughPct, int slumpMinDaysOut,
                        int slumpMinSellThroughPct, int slumpTargetPct, int urgencyBeforeHours,
                        int urgencyMinSellThroughPct, int urgencyMaxSellThroughPct, int clickedNoBuyDays) {}
}
