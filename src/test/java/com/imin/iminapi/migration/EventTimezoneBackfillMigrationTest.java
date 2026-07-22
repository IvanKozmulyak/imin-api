package com.imin.iminapi.migration;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the actual {@code V75__event_timezone_backfill.sql} statement against H2 (PG-compat).
 *
 * <p>Flyway already ran V75 at startup against the empty {@code events} table (a no-op), so we
 * seed fixture rows (real org/user/events via the JPA layer), re-run the exact SQL from the
 * migration file, and assert the repair — the statement is an idempotent bounded UPDATE, so
 * running it again over fresh rows is a faithful test of it.
 *
 * <p>Runs inside a rolled-back transaction so nothing leaks into the shared in-memory DB. The
 * inserts are flushed to the DB before the raw UPDATE runs (so it sees them), and the timezones
 * are read back via JDBC to bypass the JPA first-level cache (which would return stale entities).
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional
class EventTimezoneBackfillMigrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
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
        org = new Organization();
        org.setName("Test Org");
        org.setSlug("tz-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hello@test.example");
        org.setCountry("FR");
        org = orgs.save(org);

        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

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
