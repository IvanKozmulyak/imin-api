package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.audienceplan.model.CityOpenData;
import com.imin.iminapi.audienceplan.opendata.OpenDataCities;
import com.imin.iminapi.audienceplan.opendata.OpenDataCity;
import com.imin.iminapi.audienceplan.opendata.OpenDataset;
import com.imin.iminapi.audienceplan.service.TribeSize.Estimate;
import com.imin.iminapi.audienceplan.service.TribeSize.Input;
import com.imin.iminapi.audienceplan.service.TribeSize.Source;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TribeSizeCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-09-27T08:00:00Z");
    private static final String METZ_URL =
            "https://api.insee.fr/melodi/data/DS_RP_TD_POPULATION_AGESEX_PRINC?GEO=COM-57463&SEX=_T&maxResult=10000";
    private static final String NANCY_URL =
            "https://api.insee.fr/melodi/data/DS_RP_TD_POPULATION_AGESEX_PRINC?GEO=COM-54395&SEX=_T&maxResult=10000";
    private static final String INSEE = "Source : Insee, recensement de la population";
    private static final String DERIVED_NOTE = "computed from CNM, confirm at M0-5";
    private static final String GENRE_FIRST_METHOD =
            "pop_18_35 × music_listeners × bar_club_concert_goers × electronic_first";
    private static final String REGULARS_METHOD = GENRE_FIRST_METHOD + " × frequent_goers";
    private static final AudiencePlanLogic LOGIC = shipped();

    private final InMemoryCityOpenDataRepository repo = new InMemoryCityOpenDataRepository();
    private final PublicDataService publicData = new PublicDataService(repo, new OpenDataCities(List.of(
            new OpenDataCity("metz", "Metz", "FR", "57463", "Moselle"),
            new OpenDataCity("nancy", "Nancy", "FR", "54395", "Meurthe-et-Moselle"))),
            List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final TribeSizeCalculator calculator = new TribeSizeCalculator(LOGIC, publicData);

    @Test
    void metzTechno_pinsTheC18Fixture() {
        repo.rows.add(census("metz", 38_065L, METZ_URL, fresh()));

        TribeSize t = calculator.estimate("house & techno", List.of("metz"));

        assertThat(t.genreKey()).isEqualTo("house & techno");
        assertThat(t.cityKeys()).containsExactly("metz");
        Estimate first = t.genreFirst();
        assertThat(first.low()).isEqualTo(1_320L);
        assertThat(first.high()).isEqualTo(1_732L);
        assertThat(first.method()).isEqualTo(GENRE_FIRST_METHOD);
        List<Input> firstInputs = List.of(
                new Input("pop_18_35", "metz", 38_065.0, 38_065.0),
                new Input("music_listeners", null, 0.94, 0.94),
                new Input("bar_club_concert_goers", null, 0.41, 0.44),
                new Input("electronic_first", null, 0.09, 0.11));
        assertThat(first.inputs()).containsExactlyElementsOf(firstInputs);
        List<Source> firstSources = List.of(
                new Source("pop_18_35", "metz", INSEE, "2023", METZ_URL, null, false),
                new Source("music_listeners", null, "CNM", null, null, DERIVED_NOTE, false),
                new Source("bar_club_concert_goers", null, "CNM", "2023", null, null, false),
                new Source("electronic_first", null, "Ekhoscènes", "2024", null, null, false));
        assertThat(first.sources()).containsExactlyElementsOf(firstSources);

        Estimate regulars = t.regulars();
        assertThat(regulars.low()).isEqualTo(462L);
        assertThat(regulars.high()).isEqualTo(606L);
        assertThat(regulars.method()).isEqualTo(REGULARS_METHOD);
        assertThat(regulars.inputs()).containsExactlyElementsOf(concat(firstInputs,
                new Input("frequent_goers", null, 0.35, 0.35)));
        assertThat(regulars.sources()).containsExactlyElementsOf(concat(firstSources,
                new Source("frequent_goers", null, "CNM", null, null, DERIVED_NOTE, false)));
    }

    @Test
    void secondGenreOnTheShare_getsTheSameSizes() {
        repo.rows.add(census("metz", 38_065L, METZ_URL, fresh()));

        TribeSize t = calculator.estimate("bass & hard dance", List.of("metz"));

        assertThat(t.genreFirst().low()).isEqualTo(1_320L);
        assertThat(t.genreFirst().high()).isEqualTo(1_732L);
        assertThat(t.regulars().low()).isEqualTo(462L);
        assertThat(t.regulars().high()).isEqualTo(606L);
    }

    @Test
    void genreWithoutAShare_isUnknownNotZero() {
        repo.rows.add(census("metz", 38_065L, METZ_URL, fresh()));

        TribeSize t = calculator.estimate("jazz & acoustic", List.of("metz"));

        Estimate none = new Estimate(null, null, TribeSizeCalculator.NO_SHARE_METHOD, List.of(), List.of());
        assertThat(t.genreFirst()).isEqualTo(none);
        assertThat(t.regulars()).isEqualTo(none);
    }

    @Test
    void shareKey_namesTheRateCoveringTheGenre_orNothing() {
        assertThat(calculator.shareKey("house & techno")).contains("electronic_first");
        assertThat(calculator.shareKey("bass & hard dance")).contains("electronic_first");
        assertThat(calculator.shareKey("pop")).isEmpty();
    }

    @Test
    void genreOutsideTheEightBuckets_isRejected() {
        assertThatThrownBy(() -> calculator.estimate("techno", List.of("metz")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("techno");
    }

    @Test
    void noCity_isRejected() {
        assertThatThrownBy(() -> calculator.estimate("house & techno", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.estimate("house & techno", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingCensus_givesNullSizesNeverZero() {
        TribeSize t = calculator.estimate("house & techno", List.of("metz"));

        assertThat(t.genreFirst().low()).isNull();
        assertThat(t.genreFirst().high()).isNull();
        assertThat(t.regulars().low()).isNull();
        assertThat(t.regulars().high()).isNull();
        assertThat(t.genreFirst().method()).isEqualTo(GENRE_FIRST_METHOD);
        assertThat(t.genreFirst().inputs().get(0)).isEqualTo(new Input("pop_18_35", "metz", null, null));
        assertThat(t.genreFirst().sources()).extracting(Source::input)
                .containsExactly("music_listeners", "bar_club_concert_goers", "electronic_first");
    }

    @Test
    void censusRowWithoutAHeadline_givesNullSizes() {
        repo.rows.add(census("metz", null, METZ_URL, fresh()));

        TribeSize t = calculator.estimate("house & techno", List.of("metz"));

        assertThat(t.genreFirst().low()).isNull();
        assertThat(t.genreFirst().high()).isNull();
        assertThat(t.regulars().low()).isNull();
        assertThat(t.regulars().high()).isNull();
        assertThat(t.genreFirst().inputs().get(0)).isEqualTo(new Input("pop_18_35", "metz", null, null));
        assertThat(t.genreFirst().sources().get(0))
                .isEqualTo(new Source("pop_18_35", "metz", INSEE, "2023", METZ_URL, null, false));
    }

    @Test
    void twoCities_sumTheirPopulations() {
        repo.rows.add(census("metz", 38_065L, METZ_URL, fresh()));
        repo.rows.add(census("nancy", 43_154L, NANCY_URL, fresh()));

        TribeSize t = calculator.estimate("house & techno", List.of("metz", "nancy"));

        // 81,219 × 0.94 × 0.41 × 0.09 = 2,817.2 and × 0.44 × 0.11 = 3,695.1; × 0.35 = 986.0 and 1,293.3
        assertThat(t.genreFirst().low()).isEqualTo(2_817L);
        assertThat(t.genreFirst().high()).isEqualTo(3_695L);
        assertThat(t.regulars().low()).isEqualTo(986L);
        assertThat(t.regulars().high()).isEqualTo(1_293L);
        assertThat(t.genreFirst().inputs().subList(0, 2)).containsExactly(
                new Input("pop_18_35", "metz", 38_065.0, 38_065.0),
                new Input("pop_18_35", "nancy", 43_154.0, 43_154.0));
        assertThat(t.genreFirst().sources()).extracting(Source::cityKey)
                .containsExactly("metz", "nancy", null, null, null);
    }

    @Test
    void oneCityMissing_givesNullNotAPartialSum() {
        repo.rows.add(census("metz", 38_065L, METZ_URL, fresh()));

        TribeSize t = calculator.estimate("house & techno", List.of("metz", "nancy"));

        assertThat(t.genreFirst().low()).isNull();
        assertThat(t.genreFirst().high()).isNull();
        assertThat(t.regulars().low()).isNull();
        assertThat(t.regulars().high()).isNull();
        assertThat(t.genreFirst().inputs().subList(0, 2)).containsExactly(
                new Input("pop_18_35", "metz", 38_065.0, 38_065.0),
                new Input("pop_18_35", "nancy", null, null));
    }

    @Test
    void staleCensus_isServedAndFlagged() {
        repo.rows.add(census("metz", 38_065L, METZ_URL, NOW));

        TribeSize t = calculator.estimate("house & techno", List.of("metz"));

        assertThat(t.genreFirst().low()).isEqualTo(1_320L);
        assertThat(t.genreFirst().high()).isEqualTo(1_732L);
        assertThat(t.genreFirst().sources().get(0))
                .isEqualTo(new Source("pop_18_35", "metz", INSEE, "2023", METZ_URL, null, true));
    }

    @Test
    void duplicateCity_isCountedOnce() {
        repo.rows.add(census("metz", 38_065L, METZ_URL, fresh()));

        TribeSize t = calculator.estimate("house & techno", List.of("metz", "metz"));

        assertThat(t.cityKeys()).containsExactly("metz");
        assertThat(t.genreFirst().low()).isEqualTo(1_320L);
        assertThat(t.genreFirst().high()).isEqualTo(1_732L);
    }

    @Test
    void priorsWithoutARequiredRate_failAtStartup() {
        AudiencePlanLogic.Priors p = LOGIC.priors();
        Map<String, AudiencePlanLogic.SourcedRate> tribe = new HashMap<>(p.tribeSize());
        tribe.remove("frequent_goers");
        AudiencePlanLogic broken = new AudiencePlanLogic(LOGIC.logic(), new AudiencePlanLogic.Priors(p.version(),
                p.classes(), p.priorStrengthInvitations(), p.genreFit(), p.noShowBefore(), p.noShowShowUpIfBuy(),
                p.ticketsPerOrder(), p.showUpPaid(), p.showUpFreeRsvp(), p.metaAds(), p.instagramOrganic(),
                Map.copyOf(tribe)), LOGIC.genres());

        assertThatThrownBy(() -> new TribeSizeCalculator(broken, publicData))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frequent_goers");
    }

    @Test
    void participationRateMarkedAsAGenreShare_failsAtStartup() {
        AudiencePlanLogic.Priors p = LOGIC.priors();
        Map<String, AudiencePlanLogic.SourcedRate> tribe = new HashMap<>(p.tribeSize());
        AudiencePlanLogic.SourcedRate listeners = tribe.get("music_listeners");
        tribe.put("music_listeners", new AudiencePlanLogic.SourcedRate(listeners.low(), listeners.high(),
                listeners.source(), listeners.year(), listeners.derived(), listeners.note(), java.util.Set.of("pop")));
        AudiencePlanLogic broken = new AudiencePlanLogic(LOGIC.logic(), new AudiencePlanLogic.Priors(p.version(),
                p.classes(), p.priorStrengthInvitations(), p.genreFit(), p.noShowBefore(), p.noShowShowUpIfBuy(),
                p.ticketsPerOrder(), p.showUpPaid(), p.showUpFreeRsvp(), p.metaAds(), p.instagramOrganic(),
                Map.copyOf(tribe)), LOGIC.genres());

        assertThatThrownBy(() -> new TribeSizeCalculator(broken, publicData))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("music_listeners.genres");
    }

    private static Instant fresh() {
        return NOW.plus(Duration.ofDays(30));
    }

    private static CityOpenData census(String cityKey, Long pop18to35, String url, Instant expiresAt) {
        CityOpenData r = new CityOpenData();
        r.setId(UUID.randomUUID());
        r.setCityKey(cityKey);
        r.setDataset(OpenDataset.INSEE_AGE.key());
        r.setRefPeriod("2023");
        r.setHeadline(pop18to35);
        r.setPayload(pop18to35 == null ? "{}" : "{\"pop_18_35\":" + pop18to35 + "}");
        r.setSourceUrl(url);
        r.setLicence(OpenDataset.INSEE_AGE.licence());
        r.setAttribution(OpenDataset.INSEE_AGE.attribution());
        r.setFetchedAt(NOW.minus(Duration.ofDays(1)));
        r.setExpiresAt(expiresAt);
        return r;
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T> head, T... tail) {
        List<T> out = new java.util.ArrayList<>(head);
        out.addAll(Arrays.asList(tail));
        return out;
    }

    private static AudiencePlanLogic shipped() {
        ClassLoader cl = TribeSizeCalculatorTest.class.getClassLoader();
        try (InputStream logic = cl.getResourceAsStream("audienceplan/logic-v1.yaml");
             InputStream priors = cl.getResourceAsStream("audienceplan/priors-v1.yaml");
             InputStream genres = cl.getResourceAsStream("audienceplan/genres-v1.yaml")) {
            return LogicLoader.parse(logic, priors, genres);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
