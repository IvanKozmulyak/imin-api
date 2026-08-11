package com.imin.iminapi.service.event;

import java.util.UUID;

/**
 * Published by {@link EventService} whenever a write actually changed one of the
 * venue address strings (street / city / postal code / country).
 *
 * <p>Consumed by {@link VenueGeocodingListener} {@code AFTER_COMMIT} + {@code @Async}
 * so the organizer's PATCH never waits on a third-party geocoder and a rolled-back
 * edit never leaves a coordinate behind. Carries only the id — the listener re-reads
 * the row so it geocodes the committed address, not a stale in-flight copy.
 */
public record VenueAddressChangedEvent(UUID eventId) {}
