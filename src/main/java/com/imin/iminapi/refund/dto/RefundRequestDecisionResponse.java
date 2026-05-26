package com.imin.iminapi.refund.dto;

import java.util.UUID;

public record RefundRequestDecisionResponse(String status, UUID refundId, String refundStatus) {}
