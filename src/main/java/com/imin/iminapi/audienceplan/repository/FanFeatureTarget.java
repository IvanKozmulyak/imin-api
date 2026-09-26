package com.imin.iminapi.audienceplan.repository;

import java.util.UUID;

/** One membership to (re)compute, with the buyer email its orders are keyed on. */
public record FanFeatureTarget(UUID membershipId, UUID orgId, String normalizedEmail, boolean objectedProfiling) {}
