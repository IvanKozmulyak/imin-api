package com.imin.iminapi.audience.service;

import java.util.UUID;

/**
 * A membership's consent or profiling objection changed; published inside the changing transaction.
 * {@code deferrable} marks bulk changes (imports, the global master toggle) the nightly recompute covers.
 */
public record ConsentChanged(UUID orgId, UUID membershipId, boolean deferrable) {}
