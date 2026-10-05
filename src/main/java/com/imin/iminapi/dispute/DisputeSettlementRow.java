package com.imin.iminapi.dispute;

import java.util.UUID;

/**
 * {@link DisputeOrderRow} plus what Stripe settled for the order (null when the dispute has no
 * order or the order is not stamped yet). The payout-only queries' row.
 */
public record DisputeSettlementRow(UUID eventId, UUID orderId, Long totalMinor, Long feeMinor,
                                   String settlementCurrency, Long settlementGrossMinor,
                                   Long settlementFeeMinor, Long disputedMinor) {}
