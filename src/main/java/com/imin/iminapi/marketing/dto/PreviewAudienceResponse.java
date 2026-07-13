package com.imin.iminapi.marketing.dto;

/**
 * SendGate dry-run result for the composer Audience step (spec §2.4 preview-audience).
 * No members are materialized; this is a live count only, re-run at send time in Phase 2/4.
 */
public record PreviewAudienceResponse(int sendable, Excluded excluded) {

    public record Excluded(
            int noBasis,
            int unsubscribed,
            int marketingSuppressed,
            int deliverabilitySuppressed,
            int noPhone   // always 0 for email in Phase 1; populated for SMS in Phase 3
    ) {}
}
