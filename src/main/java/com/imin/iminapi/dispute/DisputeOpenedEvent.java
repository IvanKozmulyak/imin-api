package com.imin.iminapi.dispute;

import java.util.UUID;

/**
 * Published when a dispute transitions INTO {@link DisputeStatus#OPEN} — i.e. on the first
 * {@code charge.dispute.created}, not on every redelivery. Consumed by
 * {@link DisputeNotifier} after the ingest transaction commits.
 */
public record DisputeOpenedEvent(UUID disputeId) {}
