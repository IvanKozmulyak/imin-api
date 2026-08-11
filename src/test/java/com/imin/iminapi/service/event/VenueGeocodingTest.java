package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The V80 geocoding seam: what gets written, what stays null, and — crucially —
 * what happens when the provider has no answer.
 */
class VenueGeocodingTest {

    EventRepository events = mock(EventRepository.class);

    private Event berlinEvent() {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setVenueStreet("Am Wriezener Bahnhof");
        e.setVenueCity("Berlin");
        e.setVenuePostalCode("10243");
        e.setVenueCountry("DE");
        return e;
    }

    @Test
    void stores_the_point_the_geocoder_returns() {
        Event e = berlinEvent();
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        Geocoder geo = (street, city, postal, country) ->
                Optional.of(new Geocoder.GeoPoint(52.5111d, 13.4432d));

        new VenueGeocodingListener(events, geo).geocodeAndStore(e.getId());

        assertThat(e.getVenueLatitude()).isEqualTo(52.5111d);
        assertThat(e.getVenueLongitude()).isEqualTo(13.4432d);
        verify(events).save(e);
    }

    @Test
    void leaves_coordinates_null_when_the_geocoder_has_no_answer() {
        // The default path (NoOpGeocoder). Nothing is written, nothing is invented —
        // the buyer page keeps its address-only deep link.
        Event e = berlinEvent();
        when(events.findById(e.getId())).thenReturn(Optional.of(e));

        new VenueGeocodingListener(events, new NoOpGeocoder()).geocodeAndStore(e.getId());

        assertThat(e.getVenueLatitude()).isNull();
        assertThat(e.getVenueLongitude()).isNull();
        verify(events, never()).save(any(Event.class));
    }

    @Test
    void clears_a_stale_point_when_the_new_address_cannot_be_resolved() {
        // Event moved Berlin → an address the provider doesn't know. Keeping the old pin
        // would put the venue in the wrong city, which is worse than showing no map.
        Event e = berlinEvent();
        e.setVenueLatitude(52.5111d);
        e.setVenueLongitude(13.4432d);
        e.setVenueCity("Nowhere");
        when(events.findById(e.getId())).thenReturn(Optional.of(e));

        new VenueGeocodingListener(events, new NoOpGeocoder()).geocodeAndStore(e.getId());

        assertThat(e.getVenueLatitude()).isNull();
        assertThat(e.getVenueLongitude()).isNull();
        verify(events).save(e);
    }

    @Test
    void a_failing_geocoder_never_escapes_the_listener() {
        Event e = berlinEvent();
        when(events.findById(e.getId())).thenReturn(Optional.of(e));
        Geocoder exploding = (street, city, postal, country) -> {
            throw new IllegalStateException("provider down");
        };

        // The listener entry point swallows: the organizer's write already committed.
        new VenueGeocodingListener(events, exploding)
                .onVenueAddressChanged(new VenueAddressChangedEvent(e.getId()));

        assertThat(e.getVenueLatitude()).isNull();
    }

    @Test
    void a_deleted_event_is_a_no_op() {
        UUID gone = UUID.randomUUID();
        when(events.findById(gone)).thenReturn(Optional.empty());

        new VenueGeocodingListener(events, new NoOpGeocoder()).geocodeAndStore(gone);

        verify(events, never()).save(any(Event.class));
    }

    @Test
    void nominatim_query_needs_a_city_to_avoid_country_centroids() {
        // Street/postcode alone are ambiguous worldwide and a bare country resolves to a
        // centroid — a pin in a random field. Both must yield "no coordinates" instead.
        assertThat(NominatimGeocoder.buildQuery("Some Street 1", null, "10243", "DE")).isNull();
        assertThat(NominatimGeocoder.buildQuery(null, "  ", null, "DE")).isNull();
        assertThat(NominatimGeocoder.buildQuery("Am Wriezener Bahnhof", "Berlin", "10243", "DE"))
                .isEqualTo("Am Wriezener Bahnhof, Berlin, 10243, DE");
        assertThat(NominatimGeocoder.buildQuery(null, "Berlin", null, null)).isEqualTo("Berlin");
    }

    @Test
    void out_of_range_points_are_rejected() {
        assertThat(NominatimGeocoder.inRange(52.5d, 13.4d)).isTrue();
        assertThat(NominatimGeocoder.inRange(91d, 13.4d)).isFalse();
        assertThat(NominatimGeocoder.inRange(52.5d, 181d)).isFalse();
    }

    @Test
    void the_default_binding_is_the_no_op_geocoder() {
        GeocodingProperties props = new GeocodingProperties();
        assertThat(props.isEnabled()).as("geocoding must stay opt-in").isFalse();
        assertThat(new GeocodingConfig().geocoder(props)).isInstanceOf(NoOpGeocoder.class);

        props.setEnabled(true);
        assertThat(new GeocodingConfig().geocoder(props)).isInstanceOf(NominatimGeocoder.class);
    }
}
