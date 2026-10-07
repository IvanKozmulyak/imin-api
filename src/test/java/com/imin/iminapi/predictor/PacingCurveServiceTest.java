package com.imin.iminapi.predictor;

import com.imin.iminapi.model.*;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.EventSalesDaily;
import com.imin.iminapi.predictor.model.RelaxationLevel;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.EventSalesDailyRepository;
import com.imin.iminapi.predictor.service.PacingCurveService;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PredictorRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1 (pacing curve persistence + lookup, 86cav479d): the daily rebuild builds a curve only
 * for segments at/above the min-curve-events floor, and {@code lookup} walks the relaxation
 * ladder. Floor flipped to 3 so the fixture stays small. The rebuild reads every org's outcomes and its
 * country rungs pool cities, so each test has its own genres and asserts only the curves keyed on them.
 */
@IminIntegrationTest
class PacingCurveServiceTest {

    @Autowired PacingCurveService service;
    @Autowired EventOutcomeRepository outcomes;
    @Autowired EventSalesDailyRepository daily;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired IminFixtures fx;
    @Autowired PropertyFlips flips;
    @Autowired PredictorProperties predictorProps;
    @Autowired JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private static final Instant STARTS = Instant.parse("2026-03-11T20:00:00Z");
    private final String techno = "techno" + DateCheckControllerTest.letters();
    private final String house = "house" + DateCheckControllerTest.letters();
    private final String amsterdam = "Amsterdam" + DateCheckControllerTest.letters();

    @BeforeEach
    void setUp() {
        flips.set(predictorProps, "minCurveEvents", 3);
        Organization o = fx.org();
        o.setCountry("NL");
        orgId = orgs.save(o).getId();
        ownerId = fx.owner(o).getId();
    }

    /** Rebuilds once more at the shipped floor, so no floor-3 curve of another segment outlives the test. */
    @AfterEach
    void tearDown() {
        Throwable primary = null;
        try {
            PredictorRows.delete(jdbc, List.of(orgId));
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            try {
                flips.restoreAll();
                service.rebuildAll();
            } catch (Throwable t) {
                if (primary == null) throw t;
                primary.addSuppressed(t);
            }
        }
    }

    private List<String> ownCurveKeys(String genre) {
        return jdbc.queryForList("select segment_key from pacing_curves where segment_key like ?", String.class,
                "%|" + genre + "|%");
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
        oc.setCity(amsterdam);
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
        completedEvent(techno, 0.2, 0.5);
        completedEvent(techno, 0.3, 0.6);
        completedEvent(techno, 0.4, 0.7);
        completedEvent(house, 0.2, 0.5);
        completedEvent(house, 0.3, 0.6);

        int persisted = service.rebuildAll();
        // techno qualifies at NONE (city), CITY_TO_COUNTRY (country) and DROP_SEASON → 3 rows.
        assertThat(ownCurveKeys(techno)).extracting(k -> k.substring(0, k.indexOf('|')))
                .containsExactlyInAnyOrder("NONE", "CITY_TO_COUNTRY", "DROP_SEASON");
        assertThat(persisted).isGreaterThanOrEqualTo(3);

        Optional<PacingCurveService.CurveMatch> technoCurve =
                service.lookup(amsterdam, "NL", techno, CapacityBand.B101_300, Season.SPRING);
        assertThat(technoCurve).isPresent();
        assertThat(technoCurve.get().relaxation()).isEqualTo(RelaxationLevel.NONE); // least-relaxed rung wins
        assertThat(technoCurve.get().curve().eventsCount()).isEqualTo(3);
        assertThat(technoCurve.get().curve().points()).isNotEmpty();

        // house is below the floor at every rung → no curve anywhere.
        assertThat(service.lookup(amsterdam, "NL", house, CapacityBand.B101_300, Season.SPRING)).isEmpty();
        assertThat(ownCurveKeys(house)).isEmpty();
    }

    @Test
    void lookupFallsBackToCountryWhenNoCityCurve() {
        // 3 techno events but in different cities → no single city meets the floor; country does.
        completedEventInCity(techno, amsterdam);
        completedEventInCity(techno, "Rotterdam" + DateCheckControllerTest.letters());
        completedEventInCity(techno, "Utrecht" + DateCheckControllerTest.letters());

        service.rebuildAll();

        // A draft in a city with no own curve still resolves at the country rung.
        Optional<PacingCurveService.CurveMatch> m =
                service.lookup("Groningen" + DateCheckControllerTest.letters(), "NL", techno,
                        CapacityBand.B101_300, Season.SPRING);
        assertThat(m).isPresent();
        assertThat(m.get().relaxation()).isEqualTo(RelaxationLevel.CITY_TO_COUNTRY);
    }

    @Test
    void anOverlongCityDoesNotStopEveryOtherSegmentsCurve() {
        // events.venue_city is VARCHAR(255) and nothing clamps it upstream, but segment_key is the
        // VARCHAR(200) primary key of pacing_curves. One such city used to fail the insert and roll
        // back the whole @Transactional delete-all + reinsert: platform-wide curves stop updating.
        String longCity = "Ci" + "t".repeat(216) + "y"; // 219 chars
        completedEventInCity(techno, amsterdam);
        completedEventInCity(techno, amsterdam);
        completedEventInCity(techno, amsterdam);
        completedEventInCity(techno, longCity);
        completedEventInCity(techno, longCity);
        completedEventInCity(techno, longCity);

        service.rebuildAll();

        assertThat(service.lookup(amsterdam, "NL", techno, CapacityBand.B101_300, Season.SPRING)).isPresent();
        // and the long-city segment still resolves — writer and reader clamp the key identically.
        assertThat(service.lookup(longCity, "NL", techno, CapacityBand.B101_300, Season.SPRING)).isPresent();
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
