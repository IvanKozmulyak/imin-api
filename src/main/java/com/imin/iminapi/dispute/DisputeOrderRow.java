package com.imin.iminapi.dispute;

import java.util.UUID;

/**
 * One order's withholding disputes, summed: the order's total and booking fee (null when the
 * dispute has no order) and the disputed amount. Built by JPQL constructor expressions.
 */
public record DisputeOrderRow(UUID eventId, UUID orderId, Long totalMinor, Long feeMinor,
                              Long disputedMinor) {}
