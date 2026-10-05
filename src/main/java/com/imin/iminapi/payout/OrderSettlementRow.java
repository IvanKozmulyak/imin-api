package com.imin.iminapi.payout;

import java.util.UUID;

/**
 * One live order of an event for the payout net: presentment total and fee, what Stripe settled
 * (null until stamped), and its SUCCEEDED refunds summed. Built by a JPQL constructor expression.
 */
public record OrderSettlementRow(UUID orderId, long totalMinor, long feeMinor, String settlementCurrency,
                                 Long settlementGrossMinor, Long settlementFeeMinor,
                                 long refundedMinor, long feeRefundedMinor) {}
