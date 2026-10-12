package com.imin.iminapi.dto.org;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/org/suspension}: the caller's org's active moderation holds, newest first.
 * {@code ground} and {@code reason} are null when {@code reasonWithheld}; {@code eventName} is null for an org hold,
 * a blank name or an event that is gone; {@code scope} is {@code org} or {@code event}.
 */
public record OrgSuspensionResponse(List<Hold> holds) {

    public record Hold(UUID id, String scope, UUID eventId, String eventName, boolean sales, boolean payouts,
                       String ground, String reason, boolean reasonWithheld, Instant placedAt, String appealEmail) {}
}
