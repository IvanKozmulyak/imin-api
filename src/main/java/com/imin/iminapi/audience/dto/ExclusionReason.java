package com.imin.iminapi.audience.dto;

import java.util.UUID;

/**
 * Describes why a membership was excluded from a send-gate handoff.
 * reason values: marketing_unsubscribed | marketing_suppressed | no_email | deliverability_suppressed | no_lawful_basis
 */
public record ExclusionReason(UUID membershipId, String reason) {}
