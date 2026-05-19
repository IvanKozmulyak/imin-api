package com.imin.iminapi.service.ticket;

import java.util.UUID;

/**
 * Published by {@link PaidCheckoutService#issuePaidOrder} after the issuance
 * transaction commits. {@link TicketIssuanceEmailer} listens with
 * {@code AFTER_COMMIT} + {@code @Async} so the email send happens off-thread
 * and never blocks the Stripe webhook ack.
 */
public record TicketsIssuedEvent(UUID orderId) {}
