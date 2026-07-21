package com.imin.iminapi.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * GET /api/v1/ai/quota — the caller's own AI poster-generation quota state. Anti-abuse only;
 * the FE does not render a meter, it just reads {@code resetAt} when a 429 fires.
 *
 * @param unlimited when true the org bypasses the quota entirely ({@code organizations.ai_unlimited}).
 * @param image     rolling-24h image-pipeline bucket.
 */
public record AiQuotaResponse(boolean unlimited, Bucket image) {

    /**
     * @param resetAt when the oldest in-window attempt expires and a slot frees (oldest + 24h),
     *                or null (omitted) when nothing is in the window (or the org is unlimited).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Bucket(int limit, long used, long remaining, Instant resetAt) {}
}
