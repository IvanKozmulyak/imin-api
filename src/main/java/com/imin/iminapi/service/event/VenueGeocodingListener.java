package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Populates {@code events.venue_latitude/longitude} after a venue address write.
 *
 * <p>{@code AFTER_COMMIT} + {@code @Async}: the organizer's create/patch response is
 * never blocked on a geocoder, and an edit that rolls back never geocodes. Failures are
 * logged and swallowed — there is nothing to fail back to, and a missing coordinate is
 * a supported state everywhere downstream.
 *
 * <p><b>Clearing is as important as setting.</b> When the geocoder has no answer the
 * pair is set to NULL rather than left at its previous value: an event moved from Berlin
 * to Metz must not keep pointing at the Berlin venue. A stale pin is a wrong pin.
 */
@Component
public class VenueGeocodingListener {

    private static final Logger log = LoggerFactory.getLogger(VenueGeocodingListener.class);

    private final EventRepository events;
    private final Geocoder geocoder;

    public VenueGeocodingListener(EventRepository events, Geocoder geocoder) {
        this.events = events;
        this.geocoder = geocoder;
    }

    /**
     * Bound to the single-threaded {@code venueGeocodingExecutor} BY NAME. An unqualified
     * {@code @Async} would resolve to {@code SimpleAsyncTaskExecutor} — a new unbounded platform
     * thread per event — because {@code AsyncConfig}'s {@code Executor} beans make Boot's
     * task-executor auto-configuration back off. See {@code AsyncConfig.venueGeocodingExecutor}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("venueGeocodingExecutor")
    public void onVenueAddressChanged(VenueAddressChangedEvent ev) {
        try {
            geocodeAndStore(ev.eventId());
        } catch (Exception e) {
            log.warn("[geocode] failed for event {}: {}", ev.eventId(), e.toString());
        }
    }

    /**
     * Package-private so tests can drive it synchronously.
     *
     * <p>Deliberately NOT {@code @Transactional}: the read and the write each get their
     * own short transaction from the repository, and the provider call — up to a
     * multi-second timeout plus the rate-limit sleep — happens BETWEEN them rather than
     * inside one, so a slow third party can never pin a pooled DB connection. Last write
     * wins, which is correct here: the newest address is the one worth a pin.
     *
     * <p><b>The write is a targeted two-column UPDATE, never {@code save(entity)}.</b> That
     * is a direct consequence of the paragraph above: because there is no transaction spanning
     * the read and the write, {@code e} is a DETACHED snapshot taken up to ~9.4s ago, and
     * {@code save()} on a detached entity is {@code em.merge()} — it would write every field of
     * that stale snapshot back over the live row. {@code Event} carries no {@code @Version}, so
     * nothing would object: an organizer who hit Publish (or Delete, or whose event the status
     * sweeper just moved to PAST, or who sold a ticket) during the geocode call would have that
     * silently undone. "Last write wins" applies to the COORDINATES only — see
     * {@link EventRepository#updateVenueCoordinates}.
     */
    void geocodeAndStore(java.util.UUID eventId) {
        Event e = events.findById(eventId).orElse(null);
        if (e == null) return;

        Optional<Geocoder.GeoPoint> point = geocoder.geocode(
                e.getVenueStreet(), e.getVenueCity(), e.getVenuePostalCode(), e.getVenueCountry());

        Double lat = point.map(Geocoder.GeoPoint::latitude).orElse(null);
        Double lon = point.map(Geocoder.GeoPoint::longitude).orElse(null);

        boolean unchanged = java.util.Objects.equals(lat, e.getVenueLatitude())
                && java.util.Objects.equals(lon, e.getVenueLongitude());
        if (unchanged) return;

        events.updateVenueCoordinates(eventId, lat, lon);
        if (lat == null) {
            log.debug("[geocode] no coordinates for event {} — cleared, FE falls back to the maps link", eventId);
        } else {
            log.debug("[geocode] event {} resolved to {},{}", eventId, lat, lon);
        }
    }
}
