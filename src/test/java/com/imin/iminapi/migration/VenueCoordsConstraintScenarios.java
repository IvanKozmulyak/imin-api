package com.imin.iminapi.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ck_events_venue_coords_valid on a fresh, fully migrated database: a pair or nothing, inside the globe. */
abstract class VenueCoordsConstraintScenarios {

    /** A new, empty database per call. */
    abstract DataSource freshDatabase();

    private JdbcTemplate jdbc;
    private UUID event;

    @BeforeEach
    void migrateAndSeed() {
        DataSource ds = freshDatabase();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);

        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        event = UUID.randomUUID();
        jdbc.update("insert into organizations (id, name, slug, contact_email, country) values (?, 'Org', ?, 'o@example.com', 'DE')",
                org, "coords-" + org);
        jdbc.update("insert into users (id, org_id, email, email_lower, role) values (?, ?, 'u@example.com', 'u@example.com', 'OWNER')",
                user, org);
        jdbc.update("insert into events (id, org_id, slug, created_by) values (?, ?, ?, ?)", event, org, "coords-" + event, user);
    }

    private int setCoords(Double lat, Double lon) {
        return jdbc.update("update events set venue_latitude = ?, venue_longitude = ? where id = ?", lat, lon, event);
    }

    private Map<String, Object> storedCoords() {
        return jdbc.queryForMap("select venue_latitude, venue_longitude from events where id = ?", event);
    }

    @Test
    void aLoneLongitude_isRejected() {
        assertThatThrownBy(() -> setCoords(null, 13.44)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(storedCoords()).containsEntry("venue_latitude", null).containsEntry("venue_longitude", null);
    }

    @Test
    void aLoneLatitude_isRejected() {
        assertThatThrownBy(() -> setCoords(52.5, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(storedCoords()).containsEntry("venue_latitude", null).containsEntry("venue_longitude", null);
    }

    @Test
    void aLoneLongitude_isRejectedOnInsertToo() {
        UUID org = jdbc.queryForObject("select org_id from events where id = ?", UUID.class, event);
        UUID user = jdbc.queryForObject("select created_by from events where id = ?", UUID.class, event);
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("insert into events (id, org_id, slug, created_by, venue_latitude, venue_longitude)"
                + " values (?, ?, ?, ?, NULL, 13.44)", other, org, "coords-" + other, user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bothNull_isAccepted() {
        setCoords(52.5, 13.44);
        assertThat(setCoords(null, null)).isEqualTo(1);
        assertThat(storedCoords()).containsEntry("venue_latitude", null).containsEntry("venue_longitude", null);
    }

    @Test
    void aPairInBounds_isAccepted() {
        assertThat(setCoords(52.5, 13.44)).isEqualTo(1);
        assertThat(storedCoords()).containsEntry("venue_latitude", 52.5).containsEntry("venue_longitude", 13.44);
    }

    @Test
    void aLatitudeOffTheGlobe_isRejected() {
        assertThatThrownBy(() -> setCoords(91.0, 13.44)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aLongitudeOffTheGlobe_isRejected() {
        assertThatThrownBy(() -> setCoords(52.5, 181.0)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
