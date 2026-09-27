package com.imin.iminapi.audienceplan.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One upcoming event and the state of its stored audience plan. {@code coverageMid}, {@code verdict} and
 * {@code computedAt} are null without a plan; {@code coverageMid} is also null in cold mode.
 */
public record AudiencePlanListItem(
        UUID eventId,
        String name,
        Instant startsAt,
        /* draft | live */
        String eventStatus,
        /* none | fresh | stale */
        String status,
        Double coverageMid,
        /* strong | medium | weak | cold */
        String verdict,
        Instant computedAt) {
}
