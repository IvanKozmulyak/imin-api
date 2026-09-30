package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.RelaxationLevel;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.service.ComparableCorpusService;
import com.imin.iminapi.predictor.service.ComparableCorpusService.ComparableCorpus;
import com.imin.iminapi.predictor.service.ComparableCorpusService.ForeignAggregate;
import com.imin.iminapi.predictor.service.ComparableCorpusService.OwnEvent;
import com.imin.iminapi.predictor.service.PacingCurveService;
import com.imin.iminapi.predictor.service.PacingCurveService.CurveMatch;
import com.imin.iminapi.predictor.service.PacingEngine.Curve;
import com.imin.iminapi.predictor.service.PacingEngine.CurvePoint;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.imin.iminapi.predictor.rules.RuleFixtures.TODAY;
import static com.imin.iminapi.predictor.rules.RuleFixtures.in;
import static com.imin.iminapi.predictor.rules.RuleFixtures.q;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Internal rules against real event rows (H2); the corpus and curve services are stubbed for 2.9 and 10.2. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class InternalEvaluatorTest {

    /** Saturday 14 Nov 2026; Paris is UTC+1. */
    private static final LocalDate D = LocalDate.of(2026, 11, 14);

    @Autowired InternalEvaluator evaluator;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @MockitoBean ComparableCorpusService corpus;
    @MockitoBean PacingCurveService curves;

    private UUID ownOrg;
    private UUID otherOrg;
    private UUID userId;

    @BeforeEach
    void setUp() {
        wipe();
        ownOrg = org("Own");
        otherOrg = org("Other");
        User u = new User();
        u.setEmail("o-" + UUID.randomUUID() + "@example.com");
        u.setOrgId(ownOrg);
        u.setRole(UserRole.OWNER);
        userId = users.save(u).getId();
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private UUID org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("h@test.example");
        o.setCountry("FR");
        return orgs.save(o).getId();
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
        return ev(otherOrg, "Paris", "House & Techno", startsAt, EventStatus.LIVE);
    }

    private Event own(String startsAt, EventStatus status) {
        return ev(ownOrg, "Paris", "House & Techno", startsAt, status);
    }

    private DateCheckInput paris() {
        return in().org(ownOrg).build();
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
        DateCheckInput techno = in().org(ownOrg).genre("house & techno", "techno").build();
        assertThat(eval("2.2", techno).strength()).isEqualTo(3);
    }

    @Test
    void otherGenreClear() {
        ev(otherOrg, "Paris", "Pop", "2026-11-14T22:00:00Z", EventStatus.LIVE);
        ev(otherOrg, "Lyon", "House & Techno", "2026-11-14T22:00:00Z", EventStatus.LIVE);

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
        ev(otherOrg, "Paris", "House & Techno", "2026-11-14T22:00:00Z", EventStatus.CANCELLED);
        ev(otherOrg, "Paris", "House & Techno", "2026-11-14T22:00:00Z", EventStatus.DRAFT);

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
        ev(otherOrg, "Paris", "House & Techno", "2026-11-14T22:00:00Z", EventStatus.CANCELLED);
        Event hidden = other("2026-11-14T22:00:00Z");
        hidden.setVisibility(EventVisibility.PRIVATE);
        events.save(hidden);

        assertThat(eval("2.1", paris()).facts()).containsEntry("reason", "no_imin_events_in_city");
    }

    @Test
    void blankCityNotProvided() {
        DateCheckInput blank = in().org(ownOrg).city("  ", "FR", null).build();

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
        assertThat(eval("2.2", in().org(ownOrg).city("Lyon", "FR", "69001").build()).facts())
                .containsEntry("reason", "no_imin_events_in_city");
    }

    @Test
    void noGenreNotChecked() {
        other("2026-11-14T22:00:00Z");

        Finding f = eval("2.1", in().org(ownOrg).genre(null, null).build());

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
        DateCheckInput in = in().org(ownOrg).lineup("amelie lens", "DJ", "Ben").build();

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

        Finding f = eval("2.7", in().org(ownOrg).city("", "FR", null).build());

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "not_provided");
    }

    @Test
    void checkedEventItselfIsNotAnOwnEventMatch() {
        Event self = own("2026-11-14T22:00:00Z", EventStatus.DRAFT);

        assertThat(eval("2.7", paris()).status()).isEqualTo(Status.FOUND);
        assertThat(eval("2.7", in().org(ownOrg).excludeEvent(self.getId()).build()).status())
                .isEqualTo(Status.CLEAR);
        Event other = own("2026-11-20T22:00:00Z", EventStatus.LIVE);
        Finding f = eval("2.7", in().org(ownOrg).excludeEvent(self.getId()).build());
        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.facts()).containsEntry("name", other.getName());
    }

    @Test
    void ownCancelledIgnored() {
        own("2026-11-14T22:00:00Z", EventStatus.CANCELLED);

        assertThat(eval("2.7", paris()).status()).isEqualTo(Status.CLEAR);
    }

    // --- 2.9 ---

    private void corpusReturns(int own, int ownSellOuts, ForeignAggregate foreign) {
        List<OwnEvent> ownEvents = new ArrayList<>();
        for (int i = 0; i < own; i++) {
            ownEvents.add(new OwnEvent(UUID.randomUUID(), "Own " + i, 200, 100_000L, i < ownSellOuts, 200, 200,
                    Instant.parse("2025-11-14T22:00:00Z")));
        }
        int foreignCount = foreign == null ? 0 : foreign.count();
        when(corpus.retrieve(any(), any(), any(), any(), any(), any())).thenReturn(new ComparableCorpus(
                RelaxationLevel.NONE, own + foreignCount, own, foreignCount, ownEvents, foreign));
    }

    private static ForeignAggregate foreign(int count, double rate) {
        return new ForeignAggregate(count, 200, 200, 100_000L, rate);
    }

    @Test
    void comparablesSellOutOpportunity() {
        corpusReturns(2, 1, foreign(5, 0.4));

        Finding f = eval("2.9", paris());

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.OPPORTUNITY);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("sellOutRate", 0.45).containsEntry("n", 7)
                .containsEntry("relaxation", "NONE");
        verify(corpus).retrieve(ownOrg, "paris", "FR", "house & techno", CapacityBand.B101_300,
                Season.AUTUMN);
    }

    @Test
    void lowSellOutRisk() {
        corpusReturns(0, 0, foreign(10, 0.0));

        Finding f = eval("2.9", paris());

        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(2);
    }

    @Test
    void middleClear() {
        corpusReturns(0, 0, foreign(10, 0.2));

        assertThat(eval("2.9", paris()).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void nullCapacityNotChecked() {
        Finding f = eval("2.9", in().org(ownOrg).capacity(null).build());

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "not_provided");
        assertThat(eval("10.2", in().org(ownOrg).capacity(null).build()).facts()).containsEntry("reason", "not_provided");
        verifyNoInteractions(corpus, curves);
    }

    @Test
    void underMinEventsNotChecked() {
        corpusReturns(4, 4, null); // foreign cluster suppressed for privacy: only own events count

        Finding f = eval("2.9", paris());

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_data");
    }

    // --- 10.2 ---

    private void curveReturns(CurvePoint... points) {
        when(curves.lookup(any(), any(), any(), any(), any())).thenReturn(
                Optional.of(new CurveMatch(RelaxationLevel.NONE, new Curve(12, List.of(points)))));
    }

    @Test
    void noCurveNotChecked() {
        when(curves.lookup(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        Finding f = eval("10.2", paris());

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_data");
    }

    @Test
    void shortLeadMissesBuyersRisk() {
        // lead is 45 days: the first point at or beyond it is daysOut 50
        curveReturns(new CurvePoint(60, 0.2, 0.1, 0.3), new CurvePoint(50, 0.3, 0.2, 0.4),
                new CurvePoint(30, 0.6, 0.5, 0.7));

        Finding f = eval("10.2", paris());

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("leadDays", 45L).containsEntry("soldShareBefore", 0.3);
        verify(curves).lookup("paris", "FR", "house & techno", CapacityBand.B101_300, Season.AUTUMN);

        curveReturns(new CurvePoint(50, 0.55, 0.4, 0.6));
        assertThat(eval("10.2", paris()).strength()).isEqualTo(3);
    }

    @Test
    void longLeadClear() {
        curveReturns(new CurvePoint(40, 0.3, 0.2, 0.4));
        assertThat(eval("10.2", paris()).status()).isEqualTo(Status.CLEAR); // no point as far out as 45 days

        curveReturns(new CurvePoint(50, 0.2, 0.1, 0.3));
        assertThat(eval("10.2", paris()).status()).isEqualTo(Status.CLEAR);
    }
}
