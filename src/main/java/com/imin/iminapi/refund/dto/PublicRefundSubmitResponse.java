package com.imin.iminapi.refund.dto;

import java.time.Instant;
import java.util.UUID;

public record PublicRefundSubmitResponse(UUID id, String status, Instant submittedAt) {}
