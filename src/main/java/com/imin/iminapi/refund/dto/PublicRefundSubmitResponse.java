package com.imin.iminapi.refund.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Receipt for a submitted refund request.
 *
 * <p>{@code reference} is the short code the buyer quotes to support
 * ({@code REQ-8K2M-26}); {@code id} stays the machine identifier. Show the
 * reference on the receipt — a UUID fragment is not something a person can read
 * out, and it is not unique either.
 */
public record PublicRefundSubmitResponse(UUID id, String reference, String status, Instant submittedAt) {}
