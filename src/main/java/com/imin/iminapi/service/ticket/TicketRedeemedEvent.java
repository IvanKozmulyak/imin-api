package com.imin.iminapi.service.ticket;

import java.util.UUID;

/**
 * Published by {@link TicketRedeemService} after a successful atomic redemption.
 * The audience projector listens AFTER_COMMIT + @Async to recompute
 * attended/no_show/last_attended on the membership.
 *
 * <p>M1: carries orderId + eventId (NOT orgId — Ticket has no orgId field).
 * The projector loads the Order by orderId to resolve orgId + buyer email.
 * The redeemer may be a gate device (AuthPrincipal.forGate); userId may be null.
 */
public record TicketRedeemedEvent(UUID orderId, UUID eventId) {}
