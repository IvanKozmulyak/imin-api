package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.EventSalesDaily;
import com.imin.iminapi.predictor.model.RelaxationLevel;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.EventSalesDailyRepository;
import com.imin.iminapi.predictor.repository.PacingCurveRepository;
import com.imin.iminapi.predictor.service.PacingCurveService;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1 (pacing curve persistence + lookup, 86cav479d): the daily rebuild builds a curve only
 * for segments at/above the min-curve-events floor, and {@code lookup} walks the relaxation
 * ladder. Floor set to 3 here so the fixture stays small.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = "imin.predictor.min-curve-events=3")
class PacingCurveServiceTest {

    @Autowired PacingCurveService service;
    @Autowired PacingCurveRepository curves;
    @Autowired EventOutcomeRepository outcomes;
    @Autowired EventSalesDailyRepository daily;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private UUID orgId;
    private UUID ownerId;
    private static final Instant STARTS = Instant.parse("2026-03-11T20:00:00Z");

    @BeforeEach
    void setUp() {
        wipe();
        Organization o = new Organization();
        o.setName("Org");
        o.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("h@test.example");
        o.setCountry("NL");
        orgId = orgs.save(o).getId();

        User owner = new User();
        owner.setEmail("o-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(orgId);
        owner.setRole(UserRole.OWNER);
        ownerId = users.save(owner).getId();
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        curves.deleteAll();
        daily.deleteAll();
        outcomes.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    /** Completed event in a fixed segment, with a 3-point normalized-able trajectory. */
    private void completedEvent(String genre, double p10, double p5) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("E");
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setTimezone("UTC");
        e.setStartsAt(STARTS);
        e.setCreatedBy(ownerId);
        e = events.save(e);

        EventOutcome oc = new EventOutcome();
        oc.setEventId(e.getId());
        oc.setOrgId(orgId);
        oc.setCity("Amsterdam");
        oc.setCountry("NL");
        oc.setGenreFamily(genre);
        oc.setCapacityBand(CapacityBand.B101_300);
        oc.setSeason(Season.SPRING);
        oc.setCapacity(200);
        oc.setFinalizedAt(Instant.now());
        outcomes.save(oc);

        UUID tier = UUID.randomUUID();
        int total = 100;
        addDaily(e.getId(), tier, "2026-03-01", (int) Math.round(p10 * total), (int) Math.round(p10 * total));
        addDaily(e.getId(), tier, "2026-03-06", (int) Math.round((p5 - p10) * total), (int) Math.round(p5 * total));
        addDaily(e.getId(), tier, "2026-03-11", (int) Math.round((1.0 - p5) * total), total);
    }

    private void addDaily(UUID eventId, UUID tier, String date, int dailySold, int cumulative) {
        EventSalesDaily d = new EventSalesDaily();
        d.setEventId(eventId);
        d.setTierId(tier);
        d.setSalesDate(LocalDate.parse(date));
        d.setDailySold(dailySold);
        d.setCumulativeSold(cumulative);
        daily.save(d);
    }

    @Test
    void rebuildBuildsCurveOnlyForSegmentsAtOrAboveFloor() {
        // techno: 3 completed events (>= floor 3) → curve. house: 2 events (< floor) → no curve.
        completedEvent("techno", 0.2, 0.5);
        completedEvent("techno", 0.3, 0.6);
        completedEvent("techno", 0.4, 0.7);
        completedEvent("house", 0.2, 0.5);
        completedEvent("house", 0.3, 0.6);

        int persisted = service.rebuildAll();
        // techno qualifies at NONE (city), CITY_TO_COUNTRY (country) and DROP_SEASON → 3 rows.
        assertThat(persisted).isEqualTo(3);

        Optional<PacingCurveService.CurveMatch> techno =
                service.lookup("Amsterdam", "NL", "techno", CapacityBand.B101_300, Season.SPRING);
        assertThat(techno).isPresent();
        assertThat(techno.get().relaxation()).isEqualTo(RelaxationLevel.NONE); // least-relaxed rung wins
        assertThat(techno.get().curve().eventsCount()).isEqualTo(3);
        assertThat(techno.get().curve().points()).isNotEmpty();

        // house is below the floor at every rung → no curve anywhere.
        assertThat(service.lookup("Amsterdam", "NL", "house", CapacityBand.B101_300, Season.SPRING)).isEmpty();
    }

    @Test
    void lookupFallsBackToCountryWhenNoCityCurve() {
        // 3 techno events but in different cities → no single city meets the floor; country does.
        completedEventInCity("techno", "Amsterdam");
        completedEventInCity("techno", "Rotterdam");
        completedEventInCity("techno", "Utrecht");

        service.rebuildAll();

        // A draft in a city with no own curve still resolves at the country rung.
        Optional<PacingCurveService.CurveMatch> m =
                service.lookup("Groningen", "NL", "techno", CapacityBand.B101_300, Season.SPRING);
        assertThat(m).isPresent();
        assertThat(m.get().relaxation()).isEqualTo(RelaxationLevel.CITY_TO_COUNTRY);
    }

    private void completedEventInCity(String genre, String city) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setName("E");
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setTimezone("UTC");
        e.setStartsAt(STARTS);
        e.setCreatedBy(ownerId);
        e = events.save(e);

        EventOutcome oc = new EventOutcome();
        oc.setEventId(e.getId());
        oc.setOrgId(orgId);
        oc.setCity(city);
        oc.setCountry("NL");
        oc.setGenreFamily(genre);
        oc.setCapacityBand(CapacityBand.B101_300);
        oc.setSeason(Season.SPRING);
        oc.setCapacity(200);
        oc.setFinalizedAt(Instant.now());
        outcomes.save(oc);

        UUID tier = UUID.randomUUID();
        addDaily(e.getId(), tier, "2026-03-01", 20, 20);
        addDaily(e.getId(), tier, "2026-03-06", 30, 50);
        addDaily(e.getId(), tier, "2026-03-11", 50, 100);
    }
}
