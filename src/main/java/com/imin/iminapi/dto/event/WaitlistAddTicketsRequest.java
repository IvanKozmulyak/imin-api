package com.imin.iminapi.dto.event;

import java.util.UUID;

/** {@code POST /api/v1/events/{id}/waitlist/tickets}: raise the tier's quantity by {@code addTickets} (1–500). */
public record WaitlistAddTicketsRequest(UUID tierId, Integer addTickets) {
}
