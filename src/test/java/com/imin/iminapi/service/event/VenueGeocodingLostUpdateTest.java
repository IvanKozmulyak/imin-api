package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The concurrency contract of the V80 geocoding write, against a real database.
 *
 * <p>{@code VenueGeocodingTest} mocks the repository, so no assertion there can see what the
 * write actually does to the row. This test can, and it is the one that would have caught the
 * lost update: the listener is deliberately non-transactional (a ~9.4s provider call must not
 * sit on a pooled connection), so the {@code Event} it holds is a DETACHED snapshot taken
 * before that call, and {@code save()} on a detached entity is {@code em.merge()} — every field
 * of the stale snapshot written back over whatever the row became meanwhile. {@code Event} has
 * no {@code @Version}, so nothing objects.
 *
 * <p>The geocoder stub below is the window: it commits the organizer's concurrent write while the
 * listener holds its detached snapshot, which is exactly the state production is in when the
 * HTTP response finally lands. Every write commits, as the non-transactional listener's do.
 */
@IminIntegrationTest
class VenueGeocodingLostUpdateTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired EventRepository events;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID orgId;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        Organization org = new Organization();
        org.setName("Test Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("org@example.com");
        org.setCountry("DE");
        org = organizations.save(org);
        orgId = org.getId();

        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Great Event");
        e.setSlug("event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(NOW.plusSeconds(86_400));
        e.setCreatedBy(owner.getId());
        e.setVenueStreet("Am Wriezener Bahnhof");
        e.setVenueCity("Berlin");
        e.setVenuePostalCode("10243");
        e.setVenueCountry("DE");
        eventId = events.save(e).getId();
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, List.of(orgId));
    }

    /**
     * A geocoder that spends its "network time" letting the organizer publish, sell and then
     * delete the event — and leaves the listener's snapshot detached, as production does.
     */
    private Geocoder rivalWritesDuringTheCall() {
        return (street, city, postal, country) -> {
            jdbc.update("""
                    UPDATE events
                       SET published_at = ?, deleted_at = ?, status = 'PAST', sold = 7, revenue_minor = 12345
                     WHERE id = ?
                    """, Timestamp.from(NOW), Timestamp.from(NOW), eventId);
            return Optional.of(new Geocoder.GeoPoint(52.5111d, 13.4432d));
        };
    }

    @Test
    void a_concurrent_publish_delete_and_sale_survive_the_geocode_write() {
        new VenueGeocodingListener(events, rivalWritesDuringTheCall()).geocodeAndStore(eventId);

        Event after = events.findById(eventId).orElseThrow();

        // The coordinates ARE written — that is the whole point of the listener.
        assertThat(after.getVenueLatitude()).isEqualTo(52.5111d);
        assertThat(after.getVenueLongitude()).isEqualTo(13.4432d);

        // ...and NOTHING else moved. Each of these was a real consequence of the merge:
        assertThat(after.getPublishedAt()).as("publish must not be undone").isNotNull();
        assertThat(after.getDeletedAt())
                .as("a deleted event must not resurrect in the public feed").isNotNull();
        assertThat(after.getStatus()).as("the sweeper's LIVE->PAST must not revert")
                .isEqualTo(EventStatus.PAST);
        assertThat(after.getSold()).as("sold must not regress").isEqualTo(7);
        assertThat(after.getRevenueMinor()).as("revenue must not regress").isEqualTo(12345L);
    }

    /**
     * Proves the test above has teeth: the write the listener USED to do, replayed by hand
     * against the same window, does silently undo every one of those facts. If this ever stops
     * failing-by-clobbering, the scenario has stopped being reproducible and the test above is
     * no longer evidence of anything.
     */
    @Test
    void the_old_save_merge_path_is_exactly_what_clobbered_the_row() {
        Event stale = events.findById(eventId).orElseThrow();

        jdbc.update("UPDATE events SET deleted_at = ?, sold = 7 WHERE id = ?", Timestamp.from(NOW), eventId);

        stale.setVenueLatitude(52.5111d);
        stale.setVenueLongitude(13.4432d);
        events.save(stale);

        Event after = events.findById(eventId).orElseThrow();
        // deleted_at is mapped updatable=false, so even this merge can no longer resurrect the event.
        assertThat(after.getDeletedAt()).as("deleted_at is out of merge's reach").isNotNull();
        assertThat(after.getSold()).as("merge regresses sold").isZero();
    }

    @Test
    void staleSnapshotSavedAfterGeocode_keepsCoordinates() {
        Event stale = events.findById(eventId).orElseThrow();

        new VenueGeocodingListener(events, fixedPoint()).geocodeAndStore(eventId);
        stale.setName("Renamed");
        events.save(stale);

        Event after = events.findById(eventId).orElseThrow();
        assertThat(after.getName()).isEqualTo("Renamed");
        assertThat(after.getVenueLatitude()).isEqualTo(52.5111d);
        assertThat(after.getVenueLongitude()).isEqualTo(13.4432d);
    }

    @Test
    void saveThenGeocode_writesCoordinates() {
        Event loaded = events.findById(eventId).orElseThrow();
        loaded.setName("Saved First");
        events.save(loaded);

        new VenueGeocodingListener(events, fixedPoint()).geocodeAndStore(eventId);

        Event after = events.findById(eventId).orElseThrow();
        assertThat(after.getName()).isEqualTo("Saved First");
        assertThat(after.getVenueLatitude()).isEqualTo(52.5111d);
        assertThat(after.getVenueLongitude()).isEqualTo(13.4432d);
    }

    @Test
    void insertCarriesCoordinates() {
        Event template = events.findById(eventId).orElseThrow();
        Event e = new Event();
        e.setOrgId(template.getOrgId());
        e.setName("Inserted");
        e.setSlug("event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(NOW.plusSeconds(86_400));
        e.setCreatedBy(template.getCreatedBy());
        e.setVenueLatitude(48.1196d);
        e.setVenueLongitude(6.1702d);
        UUID id = events.save(e).getId();

        var row = jdbc.queryForMap("select venue_latitude, venue_longitude from events where id = ?", id);
        assertThat(((Number) row.get("venue_latitude")).doubleValue()).isEqualTo(48.1196d);
        assertThat(((Number) row.get("venue_longitude")).doubleValue()).isEqualTo(6.1702d);
    }

    private static Geocoder fixedPoint() {
        return (street, city, postal, country) -> Optional.of(new Geocoder.GeoPoint(52.5111d, 13.4432d));
    }
}
