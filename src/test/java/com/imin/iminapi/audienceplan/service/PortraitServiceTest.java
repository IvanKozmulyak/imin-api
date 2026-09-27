package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.NewPeopleGroup;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.PortraitSource;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.PortraitTown;
import com.imin.iminapi.audienceplan.dto.AudiencePortraitResponse.SizeRange;
import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs over the committed seed rows (INSEE, MESR, Wikidata), so every size traces to a shipped figure. */
class PortraitServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant SEEDED = Instant.parse("2026-09-27T00:00:00Z");
    private static final String HOUSE = "house & techno";
    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final List<String> FR_METZ = List.of("metz", "thionville", "nancy");
    private static final String INSEE = "Source : Insee, recensement de la population";
    private static final String MESR = "Source : MESR, Atlas régional des effectifs d'étudiants";
    private static final String LO = "Licence Ouverte 2.0";

    private final InMemoryCityOpenDataRepository repo = seeded();
    private final AudiencePlanProperties props = new AudiencePlanProperties();

    // ── Metz, the checked catchment ─────────────────────────────────────────

    @Test
    void metzHouse_sumsTheFrenchTownsOfTheCatchment_andListsTheTownsAbroadOutOfScope() {
        AudiencePortraitResponse p = service(70, NOW).forCity(HOUSE, "metz");

        assertThat(p.genre()).isEqualTo(HOUSE);
        assertThat(p.cityKey()).isEqualTo("metz");
        assertThat(p.versions().priors()).isEqualTo(1);
        assertThat(p.catchment().radiusKm()).isEqualTo(70);
        assertThat(p.catchment().scope()).isEqualTo("fr_catchment");
        assertThat(p.catchment().towns()).extracting(PortraitTown::cityKey)
                .containsExactly("metz", "thionville", "nancy", "luxembourg", "saarbrücken");
        assertThat(p.catchment().towns()).extracting(PortraitTown::inScope)
                .containsExactly(true, true, true, false, false);
        assertThat(p.catchment().towns().get(3).country()).isEqualTo("LU");
        assertThat(p.catchment().towns().get(0).name()).isEqualTo("Metz");
        assertThat(p.catchment().towns().get(0).kmStraight()).isZero();

        assertThat(p.groups()).extracting(NewPeopleGroup::key).containsExactly("genre_first", "regulars", "students");
        assertThat(p.groups()).extracting(NewPeopleGroup::kind).containsExactly("audience", "audience", "context");
        assertThat(p.groups()).allSatisfy(g -> {
            assertThat(g.origin()).isEqualTo("open_data");
            assertThat(g.scope()).isEqualTo("fr_catchment");
            assertThat(g.cityKeys()).containsExactlyElementsOf(FR_METZ);
        });
        // 91,692 aged 18-35 (38,065 + 10,473 + 43,154) × 0.94 × 0.41..0.44 × 0.09..0.11; regulars × 0.35.
        NewPeopleGroup first = p.groups().get(0);
        assertThat(first.size()).isEqualTo(new SizeRange(3_180, 4_172));
        assertThat(first.method()).isEqualTo("electronic_first");
        assertThat(first.sources()).containsExactly(
                census("metz", "https://api.insee.fr/melodi/data/DS_RP_TD_POPULATION_AGESEX_PRINC?GEO=COM-57463&SEX=_T&maxResult=10000"),
                census("thionville", "https://api.insee.fr/melodi/data/DS_RP_TD_POPULATION_AGESEX_PRINC?GEO=COM-57672&SEX=_T&maxResult=10000"),
                census("nancy", "https://api.insee.fr/melodi/data/DS_RP_TD_POPULATION_AGESEX_PRINC?GEO=COM-54395&SEX=_T&maxResult=10000"),
                rate("music_listeners", "CNM", null, "computed from CNM, confirm at M0-5"),
                rate("bar_club_concert_goers", "CNM", "2023", null),
                rate("electronic_first", "Ekhoscènes", "2024", null));

        NewPeopleGroup regulars = p.groups().get(1);
        assertThat(regulars.size()).isEqualTo(new SizeRange(1_113, 1_460));
        assertThat(regulars.method()).isEqualTo("electronic_first");
        assertThat(regulars.sources()).hasSize(7);
        assertThat(regulars.sources().get(6)).isEqualTo(rate("frequent_goers", "CNM", null,
                "computed from CNM, confirm at M0-5"));

        NewPeopleGroup students = p.groups().get(2);
        assertThat(students.size()).isEqualTo(new SizeRange(52_096, 52_096)); // 20,588 + 605 + 30,903
        assertThat(students.method()).isEqualTo("mesr_students");
        assertThat(students.sources()).extracting(PortraitSource::cityKey).containsExactlyElementsOf(FR_METZ);
        assertThat(students.sources().get(0)).satisfies(s -> {
            assertThat(s.input()).isEqualTo("students");
            assertThat(s.dataset()).isEqualTo("students");
            assertThat(s.label()).isEqualTo(MESR);
            assertThat(s.period()).isEqualTo("2024-25");
            assertThat(s.licence()).isEqualTo(LO);
            assertThat(s.url()).startsWith("https://data.enseignementsup-recherche.gouv.fr/");
            assertThat(s.updatedAt()).isEqualTo(SEEDED);
            assertThat(s.stale()).isFalse();
        });
    }

    @Test
    void secondBucketOnTheSameShare_showsTheSameSizes_neverASum() {
        AudiencePortraitResponse p = service(70, NOW).forCity("bass & hard dance", "metz");

        assertThat(p.groups().get(0).size()).isEqualTo(new SizeRange(3_180, 4_172));
        assertThat(p.groups().get(1).size()).isEqualTo(new SizeRange(1_113, 1_460));
        assertThat(p.groups().get(0).method()).isEqualTo("electronic_first");
    }

    @Test
    void genreWithoutAShareRate_hasNullTribeSizes_studentsStillSized() {
        AudiencePortraitResponse p = service(70, NOW).forCity("jazz & acoustic", "metz");

        assertThat(p.groups()).extracting(NewPeopleGroup::key).containsExactly("genre_first", "regulars", "students");
        for (NewPeopleGroup g : p.groups().subList(0, 2)) {
            assertThat(g.size()).isNull();
            assertThat(g.method()).isEqualTo("no_genre_share_rate");
            assertThat(g.sources()).isEmpty();
            assertThat(g.cityKeys()).containsExactlyElementsOf(FR_METZ);
        }
        assertThat(p.groups().get(2).size()).isEqualTo(new SizeRange(52_096, 52_096));
    }

    @Test
    void oneFrenchTownWithoutCensus_givesNullTribeSizes_notAPartialSum() {
        repo.rows.removeIf(r -> r.getCityKey().equals("thionville") && r.getDataset().equals("insee_age"));

        AudiencePortraitResponse p = service(70, NOW).forCity(HOUSE, "metz");

        assertThat(p.groups().get(0).size()).isNull();
        assertThat(p.groups().get(1).size()).isNull();
        assertThat(p.groups().get(0).sources()).extracting(PortraitSource::cityKey)
                .containsExactly("metz", "nancy", null, null, null);
        assertThat(p.groups().get(2).size()).isEqualTo(new SizeRange(52_096, 52_096));
    }

    @Test
    void oneFrenchTownWithoutStudents_givesNullStudents() {
        repo.rows.removeIf(r -> r.getCityKey().equals("nancy") && r.getDataset().equals("students"));

        NewPeopleGroup students = service(70, NOW).forCity(HOUSE, "metz").groups().get(2);

        assertThat(students.size()).isNull();
        assertThat(students.sources()).extracting(PortraitSource::cityKey).containsExactly("metz", "thionville");
    }

    @Test
    void expiredCensusRow_isServedAndFlaggedStale() {
        Instant later = Instant.parse("2028-01-01T00:00:00Z");

        PortraitSource metz = service(70, later).forCity(HOUSE, "metz").groups().get(0).sources().get(0);

        assertThat(metz.cityKey()).isEqualTo("metz");
        assertThat(metz.stale()).isTrue();
    }

    // ── no data: null, never 0 ──────────────────────────────────────────────

    @Test
    void unknownCity_hasNoCatchment_andNullSizes() {
        AudiencePortraitResponse p = service(70, NOW).forCity(HOUSE, "lyon");

        assertThat(p.cityKey()).isEqualTo("lyon");
        assertThat(p.catchment()).isNull();
        assertThat(p.groups()).extracting(NewPeopleGroup::key).containsExactly("genre_first", "regulars", "students");
        assertThat(p.groups()).allSatisfy(g -> {
            assertThat(g.size()).isNull();
            assertThat(g.cityKeys()).isEmpty();
            assertThat(g.sources()).isEmpty();
        });
        assertThat(p.groups().get(0).method()).isEqualTo("electronic_first");
    }

    @Test
    void catchmentWithOnlyTownsAbroad_hasNullSizes() {
        // 20 km around Luxembourg holds only Luxembourg itself, which has no French census.
        AudiencePortraitResponse p = service(20, NOW).forCity(HOUSE, "luxembourg");

        assertThat(p.catchment().towns()).extracting(PortraitTown::cityKey).containsExactly("luxembourg");
        assertThat(p.catchment().towns().get(0).inScope()).isFalse();
        assertThat(p.groups()).allSatisfy(g -> {
            assertThat(g.size()).isNull();
            assertThat(g.cityKeys()).isEmpty();
        });
    }

    @Test
    void cityAbroad_countsOnlyTheFrenchTownsAroundIt() {
        AudiencePortraitResponse p = service(70, NOW).forCity(HOUSE, "luxembourg");

        assertThat(p.groups().get(0).cityKeys()).doesNotContain("luxembourg", "saarbrücken").contains("metz");
        assertThat(p.groups().get(0).size()).isNotNull();
    }

    @Test
    void centroidMissingACoordinate_isNoCatchment() {
        repo.findByCityKeyAndDataset("metz", "centroid").orElseThrow().setPayload("{\"lat_e6\":null,\"lon_e6\":6176944}");

        AudiencePortraitResponse p = service(70, NOW).forCity(HOUSE, "metz");

        assertThat(p.catchment()).isNull();
        assertThat(p.groups()).allSatisfy(g -> assertThat(g.size()).isNull());
    }

    @Test
    void forCity_rejectsAGenreOutsideTheBuckets() {
        assertThatThrownBy(() -> service(70, NOW).forCity("techno", "metz"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("techno");
    }

    // ── the endpoint's checks ───────────────────────────────────────────────

    @Test
    void portrait_foldsCaseAndSpacesOfGenreAndCity() {
        AudiencePortraitResponse p = service(70, NOW).portrait(ORG, "  House &  Techno ", " Metz ");

        assertThat(p.genre()).isEqualTo(HOUSE);
        assertThat(p.cityKey()).isEqualTo("metz");
        assertThat(p.groups().get(1).size()).isEqualTo(new SizeRange(1_113, 1_460));
    }

    @Test
    void portrait_genreOutsideTheBuckets_is400() {
        assertInvalid(() -> service(70, NOW).portrait(ORG, "techno", "metz"), "genre");
    }

    @Test
    void portrait_missingGenre_is400() {
        assertInvalid(() -> service(70, NOW).portrait(ORG, null, "metz"), "genre");
    }

    @Test
    void portrait_blankOrMissingCity_is400() {
        assertInvalid(() -> service(70, NOW).portrait(ORG, HOUSE, "   "), "city");
        assertInvalid(() -> service(70, NOW).portrait(ORG, HOUSE, null), "city");
    }

    @Test
    void portrait_cityOver100Chars_is400_100IsAccepted() {
        assertThat(service(70, NOW).portrait(ORG, HOUSE, "a".repeat(100)).catchment()).isNull();
        assertInvalid(() -> service(70, NOW).portrait(ORG, HOUSE, "a".repeat(101)), "city");
    }

    @Test
    void portrait_cityWithAControlCharacter_is400() {
        assertInvalid(() -> service(70, NOW).portrait(ORG, HOUSE, "me\u0000tz"), "city");
    }

    @Test
    void portrait_killSwitchOff_is404_beforeAnyValidation() {
        props.setEnabled(false);

        assertThatThrownBy(() -> service(70, NOW).portrait(ORG, "techno", ""))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    @Test
    void regulars_readsTheRegularsGroup_orNullWhenAbsent() {
        List<NewPeopleGroup> groups = service(70, NOW).forCity(HOUSE, "metz").groups();

        assertThat(PortraitService.regulars(groups)).isEqualTo(new SizeRange(1_113, 1_460));
        assertThat(PortraitService.regulars(List.of())).isNull();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo(ErrorCode.FIELD_INVALID);
            assertThat(e.fields()).containsKey(field);
        });
    }

    private static PortraitSource census(String city, String url) {
        return new PortraitSource("pop_18_35", city, "insee_age", INSEE, "2023", LO, url, null, SEEDED, false);
    }

    private static PortraitSource rate(String input, String label, String period, String note) {
        return new PortraitSource(input, null, null, label, period, null, null, note, null, false);
    }

    private PortraitService service(int radiusKm, Instant now) {
        AudiencePlanLogic logic = logic(radiusKm);
        PublicDataService publicData = new PublicDataService(repo, OpenDataCities.load(), List.of(),
                Clock.fixed(now, ZoneOffset.UTC));
        return new PortraitService(new AudiencePlanAccess(props), logic, publicData,
                new CatchmentService(publicData, logic), new TribeSizeCalculator(logic, publicData));
    }

    private static InMemoryCityOpenDataRepository seeded() {
        InMemoryCityOpenDataRepository repo = new InMemoryCityOpenDataRepository();
        new CityOpenDataSeeder(repo, OpenDataCities.load()).seed();
        return repo;
    }

    private static AudiencePlanLogic logic(int radiusKm) {
        String yaml = read("audienceplan/logic-v1.yaml");
        String edited = yaml.replace("radius_km: 70", "radius_km: " + radiusKm);
        assertThat(edited).contains("radius_km: " + radiusKm);
        return LogicLoader.parse(stream(edited), stream(read("audienceplan/priors-v1.yaml")),
                stream(read("audienceplan/genres-v1.yaml")));
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(String location) {
        try (InputStream in = PortraitServiceTest.class.getClassLoader().getResourceAsStream(location)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
