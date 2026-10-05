package com.imin.iminapi.refund;

import java.util.UUID;

/** SUCCEEDED refunds of one order: amount and booking-fee part, in minor units. */
public record RefundOrderSums(UUID orderId, Long refundedMinor, Long feeRefundedMinor) {}
