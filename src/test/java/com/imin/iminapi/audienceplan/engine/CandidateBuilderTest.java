package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Exclusion;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Input;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Result;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Segment;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Confidence;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CandidateBuilderTest {

    private static final AudiencePlanLogic LOGIC = shipped();
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String HOUSE = "house & techno";
    private static final String CLUB = "club / open format";
    private static final String POP = "pop";
    private static final String JAZZ = "jazz & acoustic";
    private static final Map<String, Double> LIKES_HOUSE = Map.of(HOUSE, 1.0);
    private static final Map<String, Double> LIKES_JAZZ = Map.of(JAZZ, 1.0);
    private static final double EPS = 1e-9;

    // ── exclusions ─────────────────────────────────────────────────────────

    @Test
    void boughtThisEvent_isExcluded() {
        Result r = build(List.of(person("loyal", LIKES_HOUSE, 0, true, false, 0, 0)));
        assertThat(r.segments()).isEmpty();
        assertThat(r.exclusions()).containsEntry(Exclusions.BOUGHT_THIS_EVENT, 1);
    }

    @Test
    void contactedWithinTheFloor_isExcluded() {
        Result r = build(List.of(person("loyal", LIKES_HOUSE, 0, false, true, 0, 0)));
        assertThat(r.exclusions()).containsEntry(Exclusions.CONTACTED_48H, 1);
    }

    @Test
    void twoSendsThisEvent_areExcluded_oneIsKept() {
        List<Person> people = new ArrayList<>(same("loyal", 10));
        people.add(person("loyal", LIKES_HOUSE, 0, false, false, 2, 0));
        people.set(0, person("loyal", LIKES_HOUSE, 0, false, false, 1, 0));

        Result r = build(people);

        assertThat(r.exclusions()).containsEntry(Exclusions.EVENT_CAP, 1);
        assertThat(r.segments()).singleElement().extracting(Segment::mailable).isEqualTo(10);
    }

    @Test
    void fourSendsIn30Days_areExcluded_threeAreKept() {
        List<Person> people = new ArrayList<>(same("loyal", 10));
        people.add(person("loyal", LIKES_HOUSE, 0, false, false, 0, 4));
        people.set(0, person("loyal", LIKES_HOUSE, 0, false, false, 0, 3));

        Result r = build(people);

        assertThat(r.exclusions()).containsEntry(Exclusions.MONTHLY_CAP, 1);
        assertThat(r.segments()).singleElement().extracting(Segment::mailable).isEqualTo(10);
    }

    @Test
    void severalReasons_countOnlyTheFirstInLogicOrder() {
        Result r = build(List.of(person("loyal", LIKES_HOUSE, 0, true, true, 5, 9)));
        assertThat(r.exclusions()).containsEntry(Exclusions.BOUGHT_THIS_EVENT, 1)
                .containsEntry(Exclusions.CONTACTED_48H, 0)
                .containsEntry(Exclusions.EVENT_CAP, 0)
                .containsEntry(Exclusions.MONTHLY_CAP, 0);
    }

    @Test
    void anExclusionNotListedInTheLogicFile_isNotApplied() {
        AudiencePlanLogic noMonthlyCap = withExclusions(List.of(Exclusion.CONSENT_GATE_FALSE,
                Exclusion.BOUGHT_THIS_EVENT, Exclusion.EMAILED_LAST_48H, Exclusion.SENDS_THIS_EVENT_GTE_2));
        List<Person> people = new ArrayList<>(same("loyal", 9));
        people.add(person("loyal", LIKES_HOUSE, 0, false, false, 0, 7));

        Result r = builder(noMonthlyCap).build(input(HOUSE, 255, people));

        assertThat(r.exclusions()).containsEntry(Exclusions.MONTHLY_CAP, 0);
        assertThat(r.segments()).singleElement().extracting(Segment::mailable).isEqualTo(10);
    }

    @Test
    void noClassOrAnUnknownClass_isCountedAsNoClass() {
        Result r = build(List.of(person(null, LIKES_HOUSE, 0, false, false, 0, 0),
                person("none", LIKES_HOUSE, 0, false, false, 0, 0),
                person("vip", LIKES_HOUSE, 0, false, false, 0, 0)));
        assertThat(r.exclusions()).containsEntry(Exclusions.NO_CLASS, 3);
        assertThat(r.segments()).isEmpty();
    }

    @Test
    void aDuplicatedPerson_isCountedOnce() {
        Person p = person("loyal", LIKES_HOUSE, 0, true, false, 0, 0);
        assertThat(build(List.of(p, p)).exclusions()).containsEntry(Exclusions.BOUGHT_THIS_EVENT, 1);
    }

    // ── one person, one segment ────────────────────────────────────────────

    @Test
    void personMatchingTwoSegments_landsInTheHigherRateOne() {
        // pop is adjacent to club, jazz is not: a pop/jazz tie matches adjacent (×0.5) and other (×0.2).
        Map<String, Double> tie = Map.of(POP, 0.5, JAZZ, 0.5);
        List<Person> people = new ArrayList<>();
        for (int i = 0; i < 10; i++) people.add(person("loyal", tie, 0, false, false, 0, 0));

        Result r = builder(LOGIC).build(input(CLUB, 1000, people));

        assertThat(r.segments()).singleElement().satisfies(s -> {
            assertThat(s.fit()).isEqualTo(Fit.ADJACENT);
            assertThat(s.mailable()).isEqualTo(10);
        });
        assertThat(r.otherGenreHeldBack()).isZero();
    }

    @Test
    void fits_topBucketAgainstTheEventBucket() {
        AudiencePlanLogic.Genres g = LOGIC.genres();
        assertThat(CandidateBuilder.fits(Map.of(HOUSE, 0.7, POP, 0.3), HOUSE, g)).containsExactly(Fit.SAME);
        assertThat(CandidateBuilder.fits(Map.of(CLUB, 0.7, HOUSE, 0.3), HOUSE, g)).containsExactly(Fit.ADJACENT);
        assertThat(CandidateBuilder.fits(Map.of(JAZZ, 0.7, HOUSE, 0.3), HOUSE, g)).containsExactly(Fit.OTHER);
        assertThat(CandidateBuilder.fits(Map.of(HOUSE, 0.5, CLUB, 0.5), HOUSE, g))
                .isEqualTo(EnumSet.of(Fit.SAME, Fit.ADJACENT));
    }

    @Test
    void equalMid_keepsTheCloserFit() {
        AudiencePlanLogic flat = withGenreFit(new AudiencePlanLogic.GenreFit(0.5, 0.5, 0.2, 1.0));
        List<Person> people = same("loyal", 10, Map.of(HOUSE, 0.5, CLUB, 0.5));

        Result r = builder(flat).build(input(HOUSE, 1000, people));

        assertThat(r.segments()).singleElement().extracting(Segment::fit).isEqualTo(Fit.SAME);
    }

    @Test
    void personWithEmptyTaste_getsFitUnknown() {
        assertThat(CandidateBuilder.fits(Map.of(), HOUSE, LOGIC.genres())).containsExactly(Fit.UNKNOWN);
        assertThat(CandidateBuilder.fits(null, HOUSE, LOGIC.genres())).containsExactly(Fit.UNKNOWN);
        assertThat(CandidateBuilder.fits(Map.of(), null, LOGIC.genres())).containsExactly(Fit.UNKNOWN);

        Result r = builder(LOGIC).build(input(HOUSE, 1000, same("imported", 10, Map.of())));
        assertThat(r.segments()).singleElement().satisfies(s -> {
            assertThat(s.classKey()).isEqualTo("imported");
            assertThat(s.fit()).isEqualTo(Fit.UNKNOWN);
            // Neutral ×1.0: the imported class prior itself, never 0.
            assertThat(s.rate()).isEqualTo(new Band(0.002, 0.006, 0.015));
        });
    }

    @Test
    void unknownFit_isShownWhenOtherIsHeldBack_andIsItsOwnSegmentPerClass() {
        // 1558 first_timer/same → coverage 0.15, so other is held back; unknown is not gated.
        List<Person> people = new ArrayList<>(same("first_timer", 1558));
        people.addAll(same("loyal", 10, LIKES_JAZZ));
        people.addAll(same("loyal", 10, Map.of()));
        people.addAll(same("imported", 10, Map.of()));

        Result r = builder(LOGIC).build(input(HOUSE, 1000, people));

        assertThat(r.otherGenreInvited()).isFalse();
        assertThat(r.otherGenreHeldBack()).isEqualTo(10);
        assertThat(r.segments()).extracting(Segment::classKey, Segment::fit).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("first_timer", Fit.SAME),
                org.assertj.core.groups.Tuple.tuple("loyal", Fit.UNKNOWN),
                org.assertj.core.groups.Tuple.tuple("imported", Fit.UNKNOWN));
    }

    @Test
    void unknownFit_doesNotCountTowardOtherCoverage() {
        // 1557 → 149 of 1000; 100 loyal/unknown would add 100 × 0.25 × 1.6 = 40 tickets if counted.
        List<Person> people = new ArrayList<>(same("first_timer", 1557));
        people.addAll(same("loyal", 100, Map.of()));
        people.addAll(same("loyal", 10, LIKES_JAZZ));

        assertThat(builder(LOGIC).build(input(HOUSE, 1000, people)).otherGenreInvited()).isTrue();
    }

    @Test
    void smallUnknownSegment_isHiddenAndCounted() {
        Result r = builder(LOGIC).build(input(HOUSE, 1000, same("imported", 9, Map.of())));
        assertThat(r.segments()).isEmpty();
        assertThat(r.smallGroupsNotShown()).isEqualTo(1);
        assertThat(r.exclusions()).containsEntry(Exclusions.SMALL_GROUP, 9);
    }

    @Test
    void eventWithoutAWhitelistedGenre_givesEveryoneFitOther() {
        assertThat(CandidateBuilder.fits(LIKES_HOUSE, "", LOGIC.genres())).containsExactly(Fit.OTHER);
        assertThat(CandidateBuilder.fits(LIKES_HOUSE, null, LOGIC.genres())).containsExactly(Fit.OTHER);
        assertThat(CandidateBuilder.fits(LIKES_HOUSE, "techno", LOGIC.genres())).containsExactly(Fit.OTHER);
    }

    // ── other genre only when needed ──────────────────────────────────────

    @Test
    void other_isOmittedAtCoverage015() {
        // first_timer/same: 0.06 × 1.6 = 0.096 per person; 1558 → 149.568 → 150 of 1000 = 0.15.
        List<Person> people = new ArrayList<>(same("first_timer", 1558));
        people.addAll(same("loyal", 10, LIKES_JAZZ));

        Result r = builder(LOGIC).build(input(HOUSE, 1000, people));

        assertThat(r.otherGenreInvited()).isFalse();
        assertThat(r.otherGenreHeldBack()).isEqualTo(10);
        assertThat(r.segments()).extracting(Segment::fit).containsExactly(Fit.SAME);
    }

    @Test
    void other_isIncludedAtCoverage0149() {
        // 1557 → 149.472 → 149 of 1000 = 0.149.
        List<Person> people = new ArrayList<>(same("first_timer", 1557));
        people.addAll(same("loyal", 10, LIKES_JAZZ));

        Result r = builder(LOGIC).build(input(HOUSE, 1000, people));

        assertThat(r.otherGenreInvited()).isTrue();
        assertThat(r.otherGenreHeldBack()).isZero();
        assertThat(r.segments()).extracting(Segment::classKey, Segment::fit)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("first_timer", Fit.SAME),
                        org.assertj.core.groups.Tuple.tuple("loyal", Fit.OTHER));
    }

    @Test
    void adjacentSegments_countTowardOtherCoverage() {
        // first_timer/adjacent: 0.06 × 0.5 × 1.6 = 0.048 per person; 3125 → 150 of 1000, 3114 → 149.472 → 149.
        List<Person> atThreshold = new ArrayList<>(same("first_timer", 3125, Map.of(CLUB, 1.0)));
        atThreshold.addAll(same("loyal", 10, LIKES_JAZZ));
        List<Person> below = new ArrayList<>(same("first_timer", 3114, Map.of(CLUB, 1.0)));
        below.addAll(same("loyal", 10, LIKES_JAZZ));

        assertThat(builder(LOGIC).build(input(HOUSE, 1000, atThreshold)).otherGenreInvited()).isFalse();
        assertThat(builder(LOGIC).build(input(HOUSE, 1000, below)).otherGenreInvited()).isTrue();
    }

    @Test
    void coverage_countsOnlyShownSameAndAdjacentSegments() {
        // 9 hidden loyal/same would add 9 × 0.4 = 3.6 tickets and push 149 to 153; hidden, they do not.
        List<Person> people = new ArrayList<>(same("first_timer", 1557));
        people.addAll(same("loyal", 9));
        people.addAll(same("loyal", 10, LIKES_JAZZ));

        assertThat(builder(LOGIC).build(input(HOUSE, 1000, people)).otherGenreInvited()).isTrue();
    }

    @Test
    void smallOtherSegment_whenInvited_isHiddenAndCounted() {
        Result r = builder(LOGIC).build(input(HOUSE, 1000, same("loyal", 9, LIKES_JAZZ)));
        assertThat(r.otherGenreInvited()).isTrue();
        assertThat(r.segments()).isEmpty();
        assertThat(r.smallGroupsNotShown()).isEqualTo(1);
        assertThat(r.exclusions()).containsEntry(Exclusions.SMALL_GROUP, 9);
    }

    // ── small groups ───────────────────────────────────────────────────────

    @Test
    void nineMemberSegment_isHiddenAndCounted_tenIsShown() {
        List<Person> people = new ArrayList<>(same("loyal", 9));
        people.addAll(same("repeat", 10));

        Result r = build(people);

        assertThat(r.segments()).singleElement().satisfies(s -> {
            assertThat(s.classKey()).isEqualTo("repeat");
            assertThat(s.mailable()).isEqualTo(10);
        });
        assertThat(r.smallGroupsNotShown()).isEqualTo(1);
        assertThat(r.exclusions()).containsEntry(Exclusions.SMALL_GROUP, 9);
    }

    // ── output ─────────────────────────────────────────────────────────────

    @Test
    void warmFixture_segmentsRatesAndExpectedTickets() {
        List<Person> people = new ArrayList<>(same("first_timer", 235));
        people.addAll(same("loyal", 40));
        people.addAll(same("repeat", 70));

        Result r = build(people);

        assertThat(r.segments()).extracting(Segment::classKey).containsExactly("loyal", "repeat", "first_timer");
        Segment loyal = r.segments().get(0);
        assertThat(loyal.mailable()).isEqualTo(40);
        assertThat(loyal.fit()).isEqualTo(Fit.SAME);
        assertThat(loyal.confidence()).isEqualTo(Confidence.PRIOR);
        assertThat(loyal.rate()).isEqualTo(new Band(0.12, 0.25, 0.40));
        assertBand(loyal.expectedTickets(), 7.68, 16.00, 25.60);
        assertBand(r.segments().get(1).expectedTickets(), 6.72, 13.44, 22.40);
        assertBand(r.segments().get(2).expectedTickets(), 11.28, 22.56, 45.12);
        assertThat(r.otherGenreInvited()).isFalse();
    }

    @Test
    void expectedTickets_applyEachPersonsNoShowBand_segmentRateDoesNot() {
        List<Person> people = new ArrayList<>(same("loyal", 9));
        people.add(person("loyal", LIKES_HOUSE, 1, false, false, 0, 0));

        Segment s = build(people).segments().get(0);

        assertThat(s.rate()).isEqualTo(new Band(0.12, 0.25, 0.40));
        // 9 × band + 1 × band × (0.4, 0.7, 1.0), all × 1.6.
        assertBand(s.expectedTickets(), (9 * 0.12 + 0.048) * 1.6, (9 * 0.25 + 0.175) * 1.6, 10 * 0.40 * 1.6);
    }

    @Test
    void segmentIds_areSorted() {
        Segment s = build(same("loyal", 12)).segments().get(0);
        assertThat(s.membershipIds()).isSorted();
    }

    @Test
    void breakdownCounts_equalTheExcludedTotalsPerReason() {
        Map<String, Integer> gate = new LinkedHashMap<>();
        for (String reason : ConsentGate.REASONS) gate.put(reason, 0);
        gate.put(ConsentGate.LEGACY_UNPROVEN, 7);
        gate.put(ConsentGate.UNSUBSCRIBED, 2);

        List<Person> people = new ArrayList<>(same("loyal", 12));
        people.add(person("loyal", LIKES_HOUSE, 0, true, false, 0, 0));
        people.add(person("loyal", LIKES_HOUSE, 0, true, false, 0, 0));
        people.add(person("loyal", LIKES_HOUSE, 0, false, true, 0, 0));
        people.add(person("loyal", LIKES_HOUSE, 0, false, false, 3, 0));
        people.add(person("loyal", LIKES_HOUSE, 0, false, false, 0, 5));
        people.add(person("none", LIKES_HOUSE, 0, false, false, 0, 0));
        people.addAll(same("repeat", 4));
        people.addAll(same("dormant", 3, Map.of(JAZZ, 1.0)));

        // 12 loyal/same → 12 × 0.25 × 1.6 = 4.8 → 5 of 30 ≥ 0.15, so the dormant/other three are held back.
        Result r = builder(LOGIC).build(new Input(ORG, HOUSE, 30, 1.6, gate, people));

        assertThat(r.exclusions()).containsExactly(
                Map.entry(ConsentGate.ERASE_PENDING, 0), Map.entry(ConsentGate.NO_EMAIL, 0),
                Map.entry(ConsentGate.UNSUBSCRIBED, 2), Map.entry(ConsentGate.SUPPRESSED, 0),
                Map.entry(ConsentGate.OBJECTED, 0), Map.entry(ConsentGate.NO_BASIS, 0),
                Map.entry(ConsentGate.LEGACY_UNPROVEN, 7), Map.entry(ConsentGate.RETENTION_3Y, 0),
                Map.entry(Exclusions.BOUGHT_THIS_EVENT, 2), Map.entry(Exclusions.CONTACTED_48H, 1),
                Map.entry(Exclusions.EVENT_CAP, 1), Map.entry(Exclusions.MONTHLY_CAP, 1),
                Map.entry(Exclusions.NO_CLASS, 1), Map.entry(Exclusions.SMALL_GROUP, 4));
        int invited = r.segments().stream().mapToInt(Segment::mailable).sum();
        int excluded = r.exclusions().values().stream().mapToInt(Integer::intValue).sum();
        int members = people.size() + 9;
        assertThat(invited).isEqualTo(12);
        assertThat(r.otherGenreHeldBack()).isEqualTo(3);
        assertThat(invited + excluded + r.otherGenreHeldBack()).isEqualTo(members);
    }

    @Test
    void emptyInput_givesAllZeroBuilderReasons() {
        Result r = build(List.of());
        assertThat(r.segments()).isEmpty();
        assertThat(r.smallGroupsNotShown()).isZero();
        for (String reason : Exclusions.BUILDER_REASONS) assertThat(r.exclusions()).containsEntry(reason, 0);
    }

    @Test
    void nonPositiveTargetOrTicketsPerOrder_areRefused() {
        assertThatThrownBy(() -> builder(LOGIC).build(input(HOUSE, 0, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder(LOGIC).build(new Input(ORG, HOUSE, 255, 0, Map.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder(LOGIC).build(new Input(ORG, HOUSE, 255, Double.NaN, Map.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static Result build(List<Person> people) {
        return builder(LOGIC).build(input(HOUSE, 255, people));
    }

    private static CandidateBuilder builder(AudiencePlanLogic logic) {
        return new CandidateBuilder(logic, new ResponseModel(logic, CalibrationSource.NONE));
    }

    private static Input input(String genre, int target, List<Person> people) {
        return new Input(ORG, genre, target, 1.6, Map.of(), people);
    }

    private static List<Person> same(String classKey, int n) {
        return same(classKey, n, LIKES_HOUSE);
    }

    private static List<Person> same(String classKey, int n, Map<String, Double> taste) {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(person(classKey, taste, 0, false, false, 0, 0));
        return out;
    }

    private static Person person(String classKey, Map<String, Double> taste, int noShowN, boolean bought,
                                 boolean contacted, int sendsThisEvent, int sends30d) {
        return new Person(UUID.randomUUID(), classKey, taste, noShowN, bought, contacted, sendsThisEvent, sends30d);
    }

    private static void assertBand(Band b, double low, double mid, double high) {
        assertThat(b.low()).isCloseTo(low, within(EPS));
        assertThat(b.mid()).isCloseTo(mid, within(EPS));
        assertThat(b.high()).isCloseTo(high, within(EPS));
    }

    private static AudiencePlanLogic withExclusions(List<Exclusion> exclusions) {
        AudiencePlanLogic.Logic o = LOGIC.logic();
        return new AudiencePlanLogic(new AudiencePlanLogic.Logic(o.version(), o.modes(), o.targetDefaultPct(),
                o.minSegmentToShow(), o.tasteHalfLifeDays(), o.classes(), o.inviteOtherGenreOnlyIfCoverageBelow(),
                exclusions, o.coverageVerdict(), o.experiments(), o.legal()), LOGIC.priors(), LOGIC.genres());
    }

    private static AudiencePlanLogic withGenreFit(AudiencePlanLogic.GenreFit fit) {
        AudiencePlanLogic.Priors p = LOGIC.priors();
        return new AudiencePlanLogic(LOGIC.logic(), new AudiencePlanLogic.Priors(p.version(), p.classes(),
                p.priorStrengthInvitations(), fit, p.noShowBefore(), p.noShowShowUpIfBuy(), p.ticketsPerOrder(),
                p.showUpPaid(), p.showUpFreeRsvp(), p.metaAds(), p.instagramOrganic(), p.tribeSize()), LOGIC.genres());
    }

    private static AudiencePlanLogic shipped() {
        try (InputStream logic = resource("audienceplan/logic-v1.yaml");
             InputStream priors = resource("audienceplan/priors-v1.yaml");
             InputStream genres = resource("audienceplan/genres-v1.yaml")) {
            return LogicLoader.parse(logic, priors, genres);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InputStream resource(String path) {
        return CandidateBuilderTest.class.getClassLoader().getResourceAsStream(path);
    }
}
