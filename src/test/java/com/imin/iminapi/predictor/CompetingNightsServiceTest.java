package com.imin.iminapi.predictor;

import com.imin.iminapi.model.*;
import com.imin.iminapi.predictor.service.CompetingNightsService;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PredictorRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task scope A (competing nights): real same-city platform events within ±1 day are counted,
 * their capacity summed, and genre overlap flagged; other cities / out-of-window / draft events
 * are excluded. Each test has its own city, so other tests' events never share its night.
 */
@IminIntegrationTest
class CompetingNightsServiceTest {

    @Autowired CompetingNightsService service;
    @Autowired EventRepository events;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired TicketTierRepository tiers;
    @Autowired OrganizationRepository orgs;
    @Autowired IminFixtures fx;

    private UUID orgId;
    private UUID ownerId;
    private static final Instant NIGHT = Instant.parse("2026-09-12T20:00:00Z");
    private final String city = "Amsterdam" + DateCheckControllerTest.letters();
    private final String otherCity = "Rotterdam" + DateCheckControllerTest.letters();

    @BeforeEach
    void setUp() {
        Organization o = fx.org();
        o.setCountry("NL");
        orgId = orgs.save(o).getId();
        ownerId = fx.owner(o).getId();
    }

    @AfterEach
    void tearDown() {
        PredictorRows.delete(jdbc, List.of(orgId));
    }

    private Event ev(String city, String genre, Instant starts, boolean published, int capacity) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(ownerId);
        e.setName("E");
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVenueCity(city);
        e.setGenre(genre);
        e.setStartsAt(starts);
        e.setStatus(published ? EventStatus.LIVE : EventStatus.DRAFT);
        if (published) e.setPublishedAt(Instant.now());
        e = events.save(e);
        TicketTier t = new TicketTier();
        t.setEventId(e.getId());
        t.setName("GA");
        t.setPriceMinor(2000);
        t.setQuantity(capacity);
        tiers.save(t);
        return e;
    }

    @Test
    void countsSameCityWithinWindowSumsCapacityFlagsGenreOverlap() {
        Event subject = ev(city, "techno", NIGHT, true, 300);
        ev(city, "techno", NIGHT.plus(12, ChronoUnit.HOURS), true, 150); // same night, same genre
        ev(city, "house", NIGHT.minus(20, ChronoUnit.HOURS), true, 100);  // within ±1d, diff genre
        ev(city, "techno", NIGHT.plus(5, ChronoUnit.DAYS), true, 500);    // out of window
        ev(otherCity, "techno", NIGHT, true, 400);                             // other city
        ev(city, "techno", NIGHT, false, 999);                            // draft, excluded

        CompetingNightsService.CompetingNights cn = service.compute(subject);
        assertThat(cn.count()).isEqualTo(2);            // the 150 + 100 cap events
        assertThat(cn.totalCapacity()).isEqualTo(250);  // 150 + 100
        assertThat(cn.genreOverlap()).isTrue();         // the techno one overlaps
    }

    @Test
    void betweenReturnsPublishedCityEventsOnly() {
        Event live = ev(city, "Techno", NIGHT, true, 100);
        live.setSubGenre("minimal");
        live.setVenueName("Shelter");
        live.setDescription("All night long");
        events.save(live);
        Event past = ev(city.toUpperCase(java.util.Locale.ROOT), "house", NIGHT.plus(1, ChronoUnit.DAYS), true, 100);
        past.setStatus(EventStatus.PAST);
        events.save(past);
        Event cancelled = ev(city, "techno", NIGHT, true, 100);
        cancelled.setStatus(EventStatus.CANCELLED);
        events.save(cancelled);
        Event deleted = ev(city, "techno", NIGHT, true, 100);
        // deleted_at is not updatable through the entity; only a bulk write sets it.
        jdbc.update("UPDATE events SET deleted_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now()), deleted.getId());
        Event hidden = ev(city, "techno", NIGHT, true, 100);
        hidden.setVisibility(EventVisibility.PRIVATE);
        events.save(hidden);
        ev(city, "techno", NIGHT, false, 100);                              // draft
        ev(otherCity, "techno", NIGHT, true, 100);                               // other city
        ev(city, "techno", NIGHT.plus(2, ChronoUnit.DAYS), true, 100);      // at the exclusive end

        List<CompetingNightsService.CityEvent> out =
                service.between(city.toLowerCase(java.util.Locale.ROOT), NIGHT.minus(1, ChronoUnit.HOURS), NIGHT.plus(2, ChronoUnit.DAYS));

        assertThat(out).extracting(CompetingNightsService.CityEvent::id).containsExactly(live.getId(), past.getId());
        assertThat(out.get(0)).isEqualTo(new CompetingNightsService.CityEvent(live.getId(), orgId, "E", "All night long",
                "techno", "minimal", NIGHT, "Shelter"));
        assertThat(service.between("", NIGHT.minus(1, ChronoUnit.DAYS), NIGHT.plus(1, ChronoUnit.DAYS))).isEmpty();
    }

    @Test
    void noneWhenNoCityOrDate() {
        Event e = ev(city, "techno", NIGHT, true, 300);
        e.setVenueCity(null);
        assertThat(service.compute(e)).isEqualTo(CompetingNightsService.CompetingNights.NONE);
    }
}
