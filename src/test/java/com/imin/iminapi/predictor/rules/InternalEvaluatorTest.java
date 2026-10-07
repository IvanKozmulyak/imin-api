package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PredictorRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.imin.iminapi.predictor.rules.RuleFixtures.in;
import static com.imin.iminapi.predictor.rules.RuleFixtures.q;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Internal rules 2.1, 2.2 and 2.7 against real event rows; 2.9 and 10.2 are {@link InternalEvaluatorComparablesTest}.
 * Each test has its own cities, so other tests' events never share its night.
 */
@IminIntegrationTest
class InternalEvaluatorTest {

    /** Saturday 14 Nov 2026; Paris is UTC+1. */
    private static final LocalDate D = LocalDate.of(2026, 11, 14);

    @Autowired InternalEvaluator evaluator;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final String paris = "Paris" + letters();
    private final String lyon = "Lyon" + letters();

    private UUID ownOrg;
    private UUID otherOrg;
    private UUID userId;

    @BeforeEach
    void setUp() {
        Organization own = org();
        ownOrg = own.getId();
        otherOrg = org().getId();
        userId = fx.owner(own).getId();
    }

    @AfterEach
    void tearDown() {
        PredictorRows.delete(jdbc, List.of(ownOrg, otherOrg));
    }

    private static String letters() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) b.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        return b.toString();
    }

    private Organization org() {
        Organization o = fx.org();
        o.setCountry("FR");
        return orgs.save(o);
    }

    /** The own org's input in this test's Paris. */
    private RuleFixtures.In mine() {
        return in().org(ownOrg).city(paris, "FR", "75011");
    }

    private Event ev(UUID org, String city, String genre, String startsAt, EventStatus status) {
        Event e = new Event();
        e.setOrgId(org);
        e.setCreatedBy(userId);
        e.setName("Night " + UUID.randomUUID().toString().substring(0, 4));
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVenueCity(city);
        e.setVenueName("Rex Club");
        e.setGenre(genre);
        e.setStartsAt(Instant.parse(startsAt));
        e.setStatus(status);
        if (status != EventStatus.DRAFT) e.setPublishedAt(Instant.parse("2026-09-01T10:00:00Z"));
        return events.save(e);
    }

    private Event other(String startsAt) {
        return ev(otherOrg, paris, "House & Techno", startsAt, EventStatus.LIVE);
    }

    private Event own(String startsAt, EventStatus status) {
        return ev(ownOrg, paris, "House & Techno", startsAt, status);
    }

    private DateCheckInput paris() {
        return mine().build();
    }

    private Finding eval(String id, DateCheckInput in) {
        return evaluator.evaluate(q(id, SourceKind.INTERNAL), in, D);
    }

    // --- 2.1 / 2.2 ---

    @Test
    void sameNightSameGenreStrength3() {
        Event e = other("2026-11-15T02:00:00Z"); // 03:00 local: still the night of the 14th

        Finding f = eval("2.1", paris());

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(3);
        assertThat(f.facts()).containsEntry("date", "2026-11-14").containsEntry("name", e.getName())
                .containsEntry("venue", "Rex Club").containsEntry("count", 1);
        assertThat(eval("2.2", paris()).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void adjacentNightStrength2() {
        other("2026-11-15T22:00:00Z");

        Finding f = eval("2.1", paris());

        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("date", "2026-11-15");
        assertThat(eval("2.2", paris()).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void weekEventOnlyIn22() {
        Event e = other("2026-11-18T22:00:00Z");
        other("2026-11-22T22:00:00Z"); // 8 nights away: outside the week

        assertThat(eval("2.1", paris()).status()).isEqualTo(Status.CLEAR);
        Finding f = eval("2.2", paris());
        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("count", 1).containsEntry("name", e.getName());

        e.setSubGenre("Techno");
        events.save(e);
        DateCheckInput techno = mine().genre("house & techno", "techno").build();
        assertThat(eval("2.2", techno).strength()).isEqualTo(3);
    }

    @Test
    void otherGenreClear() {
        ev(otherOrg, paris, "Pop", "2026-11-14T22:00:00Z", EventStatus.LIVE);
        ev(otherOrg, lyon, "House & Techno", "2026-11-14T22:00:00Z", EventStatus.LIVE);

        assertThat(eval("2.1", paris()).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void ownOrgExcludedFrom21() {
        own("2026-11-14T22:00:00Z", EventStatus.LIVE);

        assertThat(eval("2.1", paris()).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void cancelledAndDraftIgnored() {
        own("2026-11-14T22:00:00Z", EventStatus.LIVE); // the city has a public listing
        ev(otherOrg, paris, "House & Techno", "2026-11-14T22:00:00Z", EventStatus.CANCELLED);
        ev(otherOrg, paris, "House & Techno", "2026-11-14T22:00:00Z", EventStatus.DRAFT);

        assertThat(eval("2.1", paris()).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void privateEventOfAnotherOrgIgnored() {
        own("2026-11-14T22:00:00Z", EventStatus.LIVE); // the city has a public listing
        Event hidden = other("2026-11-14T22:00:00Z");
        hidden.setVisibility(EventVisibility.PRIVATE);
        events.save(hidden);

        assertThat(eval("2.1", paris()).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void cityWithOnlyCancelledOrPrivateEventsNotChecked() {
        ev(otherOrg, paris, "House & Techno", "2026-11-14T22:00:00Z", EventStatus.CANCELLED);
        Event hidden = other("2026-11-14T22:00:00Z");
        hidden.setVisibility(EventVisibility.PRIVATE);
        events.save(hidden);

        assertThat(eval("2.1", paris()).facts()).containsEntry("reason", "no_imin_events_in_city");
    }

    @Test
    void blankCityNotProvided() {
        DateCheckInput blank = mine().city("  ", "FR", null).build();

        Finding night = eval("2.1", blank);
        Finding week = eval("2.2", blank);

        assertThat(night.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(night.facts()).containsEntry("reason", "not_provided");
        assertThat(week.facts()).containsEntry("reason", "not_provided");
    }

    @Test
    void cityWithNoIminEventsNotChecked() {
        other("2025-06-01T22:00:00Z"); // more than a year before today

        Finding f = eval("2.1", paris());

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_imin_events_in_city");
        assertThat(eval("2.2", mine().city(lyon, "FR", "69001").build()).facts())
                .containsEntry("reason", "no_imin_events_in_city");
    }

    @Test
    void noGenreNotChecked() {
        other("2026-11-14T22:00:00Z");

        Finding f = eval("2.1", mine().genre(null, null).build());

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "not_provided");
    }

    // --- 2.7 ---

    @Test
    void ownEventWithin14Found() {
        Event e = own("2026-11-24T22:00:00Z", EventStatus.LIVE);

        Finding f = eval("2.7", paris());

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("date", "2026-11-24").containsEntry("name", e.getName())
                .containsEntry("sharedArtists", List.of());
    }

    @Test
    void ownEventWithSharedHeadlinerRaisesStrength() {
        Event nearer = own("2026-11-15T22:00:00Z", EventStatus.LIVE);
        nearer.setName("Benny's night");
        events.save(nearer);
        Event shared = own("2026-11-20T22:00:00Z", EventStatus.DRAFT);
        shared.setDescription("Headliner: Amelie Lens, with residents.");
        events.save(shared);
        DateCheckInput in = mine().lineup("amelie lens", "DJ", "Ben").build();

        Finding f = eval("2.7", in);

        assertThat(f.strength()).isEqualTo(3);
        assertThat(f.facts()).containsEntry("name", shared.getName())
                .containsEntry("sharedArtists", List.of("amelie lens"));
    }

    @Test
    void ownEventDay15Clear() {
        own("2026-11-29T22:00:00Z", EventStatus.LIVE);          // night 29 Nov, D+15
        own("2026-10-31T04:30:00Z", EventStatus.LIVE);          // 05:30 local: night 30 Oct, D-15
        other("2026-11-14T22:00:00Z");

        assertThat(eval("2.7", paris()).status()).isEqualTo(Status.CLEAR);
        own("2026-10-31T05:30:00Z", EventStatus.LIVE);          // 06:30 local: night 31 Oct, D-14
        assertThat(eval("2.7", paris()).status()).isEqualTo(Status.FOUND);
    }

    @Test
    void ownEventsBlankCityNotProvided() {
        own("2026-11-14T22:00:00Z", EventStatus.LIVE);

        Finding f = eval("2.7", mine().city("", "FR", null).build());

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "not_provided");
    }

    @Test
    void checkedEventItselfIsNotAnOwnEventMatch() {
        Event self = own("2026-11-14T22:00:00Z", EventStatus.DRAFT);

        assertThat(eval("2.7", paris()).status()).isEqualTo(Status.FOUND);
        assertThat(eval("2.7", mine().excludeEvent(self.getId()).build()).status())
                .isEqualTo(Status.CLEAR);
        Event other = own("2026-11-20T22:00:00Z", EventStatus.LIVE);
        Finding f = eval("2.7", mine().excludeEvent(self.getId()).build());
        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.facts()).containsEntry("name", other.getName());
    }

    @Test
    void ownCancelledIgnored() {
        own("2026-11-14T22:00:00Z", EventStatus.CANCELLED);

        assertThat(eval("2.7", paris()).status()).isEqualTo(Status.CLEAR);
    }
}
