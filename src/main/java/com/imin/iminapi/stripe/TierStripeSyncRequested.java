package com.imin.iminapi.stripe;

import java.util.UUID;

/** Published inside an organizer tier write; {@link TierStripeSyncQueue} syncs the tier once it commits. */
public record TierStripeSyncRequested(UUID tierId) {}
