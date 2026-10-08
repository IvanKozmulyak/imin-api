package com.imin.iminapi.migration;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-runs the exact {@code V75__event_timezone_backfill.sql} UPDATE over seeded events on the shared Postgres,
 * inside a rolled-back transaction. Timezones are read back via JDBC to bypass the JPA first-level cache.
 */
@IminIntegrationTest
@Transactional
class EventTimezoneBackfillMigrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired EventRepository events;
    @Autowired IminFixtures fx;
    @Autowired EntityManager em;

    private String v75Sql() throws Exception {
        try (var in = new ClassPathResource("db/migration/V75__event_timezone_backfill.sql").getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
    }

    private Organization org;
    private User owner;

    private UUID insertEvent(String country, String timezone) {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Test Night");
        e.setSlug("tz-" + UUID.randomUUID().toString().substring(0, 12));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.DRAFT);
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        e.setTimezone(timezone);
        e.setVenueCountry(country);
        return events.save(e).getId();
    }

    private String tzOf(UUID id) {
        return jdbc.queryForObject("SELECT timezone FROM events WHERE id = ?", String.class, id);
    }

    @Test
    void repairs_utc_rows_with_known_country_and_leaves_everything_else() throws Exception {
        org = fx.org();
        owner = fx.owner(org);

        UUID fr = insertEvent("FR", "UTC");
        UUID de = insertEvent("DE", "UTC");
        UUID us = insertEvent("US", "UTC");
        UUID unmapped = insertEvent("ZZ", "UTC");                 // country not in the map
        UUID noCountry = insertEvent(null, "UTC");                // country unknown
        UUID explicit = insertEvent("FR", "America/New_York");    // organizer already chose a zone
        em.flush(); // push the inserts to the DB so the raw UPDATE sees them

        jdbc.execute(v75Sql());

        // Derived from venue country.
        assertThat(tzOf(fr)).isEqualTo("Europe/Paris");
        assertThat(tzOf(de)).isEqualTo("Europe/Berlin");
        assertThat(tzOf(us)).isEqualTo("America/New_York");
        // Not derivable from stored data -> left on the UTC default (documented gap).
        assertThat(tzOf(unmapped)).isEqualTo("UTC");
        assertThat(tzOf(noCountry)).isEqualTo("UTC");
        // Explicit non-UTC choice is never overwritten.
        assertThat(tzOf(explicit)).isEqualTo("America/New_York");
    }
}
