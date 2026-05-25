package com.imin.iminapi.dto.gate;

import com.imin.iminapi.model.Event;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal event projection returned by {@code GET /api/v1/gate/events}.
 *
 * <p>The gate scanner only needs three things to render its event picker:
 * the id (to scope subsequent {@code /tickets/redeem} calls), a human title,
 * and the start time. Everything else (venue, ticket counts, currency, etc.)
 * is deliberately omitted — the gate UI never displays them and gate tokens
 * have no legitimate need to leak unrelated org data.
 */
public record GateEventSummary(UUID id, String title, Instant startsAt) {

    public static GateEventSummary from(Event e) {
        return new GateEventSummary(e.getId(), e.getName(), e.getStartsAt());
    }
}
