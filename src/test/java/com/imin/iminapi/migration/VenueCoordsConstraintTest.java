package com.imin.iminapi.migration;

import com.imin.iminapi.support.SharedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ck_events_venue_coords_valid on a fresh, fully migrated database: a pair or nothing, inside the globe. */
class VenueCoordsConstraintTest {

    private JdbcTemplate jdbc;
    private UUID event;

    @BeforeEach
    void migrateAndSeed() {
        DataSource ds = SharedPostgres.freshDatabase("coords");
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

    /** A lone coordinate, on update or insert, or a coordinate off the globe; the row keeps no half pair. */
    @ParameterizedTest(name = "{0} lat={1} lon={2}")
    @CsvSource({
            "update, , 13.44",
            "update, 52.5, ",
            "insert, , 13.44",
            "update, 91.0, 13.44",
            "update, 52.5, 181.0",
    })
    void anIncompleteOrOffTheGlobePair_isRejected(String via, Double lat, Double lon) {
        if (via.equals("update")) {
            assertThatThrownBy(() -> setCoords(lat, lon)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(storedCoords()).containsEntry("venue_latitude", null).containsEntry("venue_longitude", null);
            return;
        }
        UUID org = jdbc.queryForObject("select org_id from events where id = ?", UUID.class, event);
        UUID user = jdbc.queryForObject("select created_by from events where id = ?", UUID.class, event);
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("insert into events (id, org_id, slug, created_by, venue_latitude, venue_longitude)"
                + " values (?, ?, ?, ?, ?, ?)", other, org, "coords-" + other, user, lat, lon))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Clearing a stored pair, or storing a pair inside the globe. */
    @ParameterizedTest(name = "lat={0} lon={1}")
    @CsvSource({
            ", ",
            "52.5, 13.44",
    })
    void aPairInBoundsOrNothing_isAccepted(Double lat, Double lon) {
        setCoords(10.0, 20.0);
        assertThat(setCoords(lat, lon)).isEqualTo(1);
        assertThat(storedCoords()).containsEntry("venue_latitude", lat).containsEntry("venue_longitude", lon);
    }
}
