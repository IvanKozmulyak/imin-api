package com.imin.iminapi.refund.event;

import java.util.UUID;

/** Published after a refund webhook confirms SUCCEEDED + inventory is released. */
public record RefundConfirmedEvent(UUID refundId) {}
