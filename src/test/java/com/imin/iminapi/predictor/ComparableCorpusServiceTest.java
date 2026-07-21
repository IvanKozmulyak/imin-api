package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.RelaxationLevel;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.service.ComparableCorpusService;
import com.imin.iminapi.predictor.service.ComparableCorpusService.ComparableCorpus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4 (comparable corpus) — relaxation ladder order, &lt;5 cluster suppression, privacy
 * rounding, and the own-events exemption. Spec §6.4, gate item 5.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class ComparableCorpusServiceTest {

    @Autowired ComparableCorpusService corpus;
    @Autowired EventOutcomeRepository outcomes;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private static final String GENRE = "House & Techno";
    private static final CapacityBand BAND = CapacityBand.B301_800;

    private Organization ownOrg;
    private User owner;

    @BeforeEach
    void setUp() {
        wipe();
        ownOrg = new Organization();
        ownOrg.setName("Own Org");
        ownOrg.setSlug("own-" + UUID.randomUUID().toString().substring(0, 8));
        ownOrg.setContactEmail("own@test.example");
        ownOrg.setCountry("NL");
        ownOrg = orgs.save(ownOrg);

        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(ownOrg.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        outcomes.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private EventOutcome base(UUID orgId, UUID eventId, String city, String country,
                              Season season, int attendance, long revenue, boolean sellOut) {
        EventOutcome o = new EventOutcome();
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setCity(city);
        o.setCountry(country);
        o.setGenreFamily(GENRE);
        o.setCapacityBand(BAND);
        o.setSeason(season);
        o.setAttendance(attendance);
        o.setGrossRevenueMinor(revenue);
        o.setSellOut(sellOut);
        o.setSoldTotal(attendance);
        o.setCapacity(500);
        o.setFinalizedAt(Instant.now());
        return o;
    }

    private void foreign(String city, String country, Season season, int attendance, long revenue, boolean sellOut) {
        outcomes.save(base(UUID.randomUUID(), UUID.randomUUID(), city, country, season, attendance, revenue, sellOut));
    }

    private UUID own(String city, String country, Season season, int attendance, long revenue, boolean sellOut) {
        Event e = new Event();
        e.setOrgId(ownOrg.getId());
        e.setName("Own Event " + UUID.randomUUID().toString().substring(0, 4));
        e.setSlug("own-ev-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.PAST);
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        UUID eventId = events.save(e).getId();
        outcomes.save(base(ownOrg.getId(), eventId, city, country, season, attendance, revenue, sellOut));
        return eventId;
    }

    @Test
    void exactCitySegment_aggregatesForeign_roundsPrivacy_andExemptsOwn() {
        // 6 foreign in the exact city segment (Amsterdam, NL, WINTER): attendance 297, revenue 52340.
        for (int i = 0; i < 6; i++) foreign("Amsterdam", "NL", Season.WINTER, 297, 52_340L, i < 3);
        // 2 own events in the same segment — must be returned by name with EXACT figures.
        own("Amsterdam", "NL", Season.WINTER, 297, 52_340L, true);
        own("Amsterdam", "NL", Season.WINTER, 297, 52_340L, false);

        ComparableCorpus c = corpus.retrieve(ownOrg.getId(), "Amsterdam", "NL", GENRE, BAND, Season.WINTER);

        assertThat(c.appliedRelaxation()).isEqualTo(RelaxationLevel.NONE); // 8 >= 5, no relaxation
        assertThat(c.densityTotal()).isEqualTo(8);
        assertThat(c.ownCount()).isEqualTo(2);
        assertThat(c.foreignCount()).isEqualTo(6);

        // own exemption: returned individually, by name, with UNROUNDED figures
        assertThat(c.ownEvents()).hasSize(2);
        assertThat(c.ownEvents()).allSatisfy(oe -> {
            assertThat(oe.name()).isNotBlank();
            assertThat(oe.attendance()).isEqualTo(297);          // exact, not rounded to 300
            assertThat(oe.grossRevenueMinor()).isEqualTo(52_340L);
        });

        // foreign privacy aggregate: rounded, anonymous
        assertThat(c.hasBenchmark()).isTrue();
        var agg = c.foreignAggregate();
        assertThat(agg.count()).isEqualTo(6);
        assertThat(agg.avgAttendanceRounded()).isEqualTo(300);   // 297 -> nearest 10
        assertThat(agg.medianAttendanceRounded()).isEqualTo(300);
        assertThat(agg.avgRevenueMinorRounded()).isEqualTo(50_000L); // 52340 -> nearest €100
        assertThat(agg.sellOutRate()).isEqualTo(0.5);            // 3 of 6
    }

    @Test
    void relaxesCityToCountry_whenCityClusterSparse() {
        // nothing in Amsterdam; 6 foreign elsewhere in NL, same genre/band/season
        for (int i = 0; i < 6; i++) foreign("Rotterdam", "NL", Season.WINTER, 400, 80_000L, false);

        ComparableCorpus c = corpus.retrieve(ownOrg.getId(), "Amsterdam", "NL", GENRE, BAND, Season.WINTER);

        assertThat(c.appliedRelaxation()).isEqualTo(RelaxationLevel.CITY_TO_COUNTRY);
        assertThat(c.foreignCount()).isEqualTo(6);
        assertThat(c.hasBenchmark()).isTrue();
    }

    @Test
    void dropsSeasonLast_whenSeasonMismatch() {
        // 6 foreign in NL but a DIFFERENT season — only reachable after season is dropped
        for (int i = 0; i < 6; i++) foreign("Rotterdam", "NL", Season.SUMMER, 350, 70_000L, false);

        ComparableCorpus c = corpus.retrieve(ownOrg.getId(), "Amsterdam", "NL", GENRE, BAND, Season.WINTER);

        assertThat(c.appliedRelaxation()).isEqualTo(RelaxationLevel.DROP_SEASON);
        assertThat(c.foreignCount()).isEqualTo(6);
        assertThat(c.hasBenchmark()).isTrue();
    }

    @Test
    void suppressesForeignAggregate_whenClusterUnderFive() {
        // 4 foreign in the exact segment — every relaxation level still returns only these 4
        for (int i = 0; i < 4; i++) foreign("Amsterdam", "NL", Season.WINTER, 300, 60_000L, true);

        ComparableCorpus c = corpus.retrieve(ownOrg.getId(), "Amsterdam", "NL", GENRE, BAND, Season.WINTER);

        assertThat(c.foreignCount()).isEqualTo(4);
        assertThat(c.hasBenchmark()).isFalse();          // no cluster under 5
        assertThat(c.foreignAggregate()).isNull();
        assertThat(c.densityTotal()).isEqualTo(4);       // density still exposed for the language ladder
    }
}
