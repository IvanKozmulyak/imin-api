package com.imin.iminapi.marketing.dto;

/**
 * Whole-log recipient counts for one campaign — backs the campaign-detail filter chips
 * (All / Opened / Clicked / Skipped / Issues). Every field is a real SQL aggregate over
 * ALL of the campaign's {@code campaign_recipients} rows, never a tally of the loaded page,
 * so a chip count stays true regardless of which page the client is on.
 *
 * <p>{@code opened}/{@code clicked} are ORTHOGONAL to the lifecycle status: they are derived
 * from {@code opened_at IS NOT NULL} / {@code clicked_at IS NOT NULL}, so one row can be
 * counted in {@code total}, {@code delivered}-ish statuses AND {@code opened} at once. The
 * buckets therefore do NOT sum to {@code total} — that is deliberate, not a bug.
 *
 * <p>The "Issues" chip is left to the client to compose from the atomic status buckets
 * ({@code bounced} + {@code failed} + {@code complained}, say) rather than being invented
 * here — summing server-side aggregates is still honest, and the API does not guess at the
 * design's definition.
 */
public record RecipientCounts(
        long total,
        long opened,
        long clicked,
        long skipped,
        long bounced,
        long failed,
        long complained,
        long unsubscribed
) {}
