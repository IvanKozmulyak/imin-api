package com.imin.iminapi.marketing.dto;

import java.util.List;

/**
 * Real Momentum-engine state + activity log behind GET /api/v1/marketing/suggestions/state
 * (spec §6.4; FE type {@code MomentumEngineState} in imin-webapp marketing/types.ts). Every
 * counter is REAL (from the DB / existing services) or a literal 0 when the source genuinely
 * does not exist — nothing is a placeholder.
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
        List<LogEntry> log) {

    /** One activity-log row. icon/tone are enumerated wire strings matched by the FE. */
    public record LogEntry(String icon, String tone, String text, String sub) {}
}
