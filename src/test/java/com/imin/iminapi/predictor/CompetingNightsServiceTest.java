package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.predictor.service.CompetingNightsService;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task scope A (competing nights): real same-city platform events within ±1 day are counted,
 * their capacity summed, and genre overlap flagged; other cities / out-of-window / draft events
 * are excluded.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CompetingNightsServiceTest {

    @Autowired CompetingNightsService service;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private UUID orgId;
    private UUID ownerId;
    private static final Instant NIGHT = Instant.parse("2026-09-12T20:00:00Z");

    @BeforeEach
    void setUp() {
        wipe();
        Organization o = new Organization();
        o.setName("Org");
        o.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("h@test.example");
        o.setCountry("NL");
        orgId = orgs.save(o).getId();
        User u = new User();
        u.setEmail("o-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        ownerId = users.save(u).getId();
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
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
        Event subject = ev("Amsterdam", "techno", NIGHT, true, 300);
        ev("Amsterdam", "techno", NIGHT.plus(12, ChronoUnit.HOURS), true, 150); // same night, same genre
        ev("Amsterdam", "house", NIGHT.minus(20, ChronoUnit.HOURS), true, 100);  // within ±1d, diff genre
        ev("Amsterdam", "techno", NIGHT.plus(5, ChronoUnit.DAYS), true, 500);    // out of window
        ev("Rotterdam", "techno", NIGHT, true, 400);                             // other city
        ev("Amsterdam", "techno", NIGHT, false, 999);                            // draft, excluded

        CompetingNightsService.CompetingNights cn = service.compute(subject);
        assertThat(cn.count()).isEqualTo(2);            // the 150 + 100 cap events
        assertThat(cn.totalCapacity()).isEqualTo(250);  // 150 + 100
        assertThat(cn.genreOverlap()).isTrue();         // the techno one overlaps
    }

    @Test
    void noneWhenNoCityOrDate() {
        Event e = ev("Amsterdam", "techno", NIGHT, true, 300);
        e.setVenueCity(null);
        assertThat(service.compute(e)).isEqualTo(CompetingNightsService.CompetingNights.NONE);
    }
}
