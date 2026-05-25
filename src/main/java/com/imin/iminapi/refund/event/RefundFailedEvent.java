package com.imin.iminapi.refund.event;

import java.util.UUID;

/** Published when a refund webhook confirms FAILED. Phase A: log-only listener. */
public record RefundFailedEvent(UUID refundId) {}
