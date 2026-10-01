package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.model.WikimediaPageviewMonth;
import com.imin.iminapi.predictor.repository.WikimediaPageviewMonthRepository;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaArticles;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static com.imin.iminapi.predictor.rules.RuleFixtures.BANK;
import static com.imin.iminapi.predictor.rules.RuleFixtures.in;
import static com.imin.iminapi.predictor.rules.RuleFixtures.q;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrendEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 14);
    private static final YearMonth LATEST = YearMonth.of(2026, 9);
    private static final String PARAMS = "params: { rising_min: 0.25, falling_min: 0.25, min_mean_views: 100, max_age_months: 2 }";

    private static final String ARTICLES_YAML = """
            version: 1
            verified_on: 2026-10-01
            languages: { FR: fr, NL: nl, DE: de, ES: es, UA: uk }
            buckets:
              "house & techno":
                sub_genres:
                  techno: { fr: Techno }
              "pop":
                article: { fr: Pop (musique) }
            """;

    private final WikimediaPageviewMonthRepository repository = mock(WikimediaPageviewMonthRepository.class);
    private final SourceGates gates = mock(SourceGates.class);
    private final Question q91 = q("9.1", SourceKind.STRUCTURED);

    {
        when(gates.isOn("wikimedia")).thenReturn(true);
    }

    private static WikimediaArticles articles(String yaml) {
        return WikimediaArticles.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), BANK);
    }

    private TrendEvaluator evaluator() {
        return new TrendEvaluator(BANK, articles(ARTICLES_YAML), repository, gates);
    }

    private static DateCheckInput input(String bucket, String subGenre) {
        RuleFixtures.In b = in().genre(bucket, subGenre);
        b.today = TODAY;
        return b.build();
    }

    private Finding evaluate(String bucket, String subGenre) {
        return evaluator().evaluate(q91, input(bucket, subGenre), DATE);
    }

    /** Rows newest first, as the repository returns them; views run oldest to newest ending at {@code latest}. */
    private void stored(String project, String article, YearMonth latest, long... views) {
        List<WikimediaPageviewMonth> rows = new ArrayList<>();
        for (int i = views.length - 1; i >= 0; i--) {
            WikimediaPageviewMonth m = new WikimediaPageviewMonth();
            m.setProject(project);
            m.setArticle(article);
            m.setViewMonth(latest.minusMonths(views.length - 1 - i).atDay(1));
            m.setViews(views[i]);
            rows.add(m);
        }
        when(repository.findTop24ByProjectAndArticleOrderByViewMonthDesc(project, article)).thenReturn(rows);
    }

    private static long[] series(long first, long step) {
        long[] out = new long[12];
        for (int i = 0; i < 12; i++) out[i] = first + step * i;
        return out;
    }

    private static QuestionBank bankWithParams(String params) throws IOException {
        String bank;
        try (InputStream in = TrendEvaluatorTest.class.getResourceAsStream("/predictor/question-bank-v2.yaml")) {
            bank = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(bank).contains(PARAMS);
        return QuestionBankLoader.parse(new ByteArrayInputStream(bank.replace(PARAMS, params).getBytes(StandardCharsets.UTF_8)),
                TrendEvaluatorTest.class.getResourceAsStream("/predictor/genre-profiles-v1.yaml"));
    }

    @Test
    void claimsOnlyNinePointOneAsStructured() {
        assertThat(evaluator().source()).isEqualTo(SourceKind.STRUCTURED);
        assertThat(evaluator().questionIds()).containsExactly("9.1");
    }

    @Test
    void gateOffNotChecked() {
        when(gates.isOn("wikimedia")).thenReturn(false);
        stored("fr.wikipedia", "Techno", LATEST, series(100, 10));

        Finding f = evaluate("house & techno", "techno");

        assertThat(f.status()).isEqualTo(Finding.Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "source_off");
        verify(repository, never()).findTop24ByProjectAndArticleOrderByViewMonthDesc(anyString(), anyString());
    }

    @Test
    void missingArticleNotChecked() {
        Finding f = evaluate("house & techno", "house");

        assertThat(f.status()).isEqualTo(Finding.Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_article");
        verify(gates).isOn("wikimedia");
    }

    @Test
    void subGenreWithoutArticleFallsBackToBucket() {
        stored("fr.wikipedia", "Pop_(musique)", LATEST, series(100, 10));

        Finding f = evaluate("pop", "k-pop");

        assertThat(f.status()).isEqualTo(Finding.Status.FOUND);
        assertThat(f.facts()).containsEntry("article", "Pop_(musique)");
        assertThat(f.url()).isEqualTo("https://fr.wikipedia.org/wiki/Pop_%28musique%29");
    }

    @Test
    void noRowsNotChecked() {
        when(repository.findTop24ByProjectAndArticleOrderByViewMonthDesc("fr.wikipedia", "Techno")).thenReturn(List.of());

        Finding f = evaluate("house & techno", "techno");

        assertThat(f.facts()).containsEntry("reason", "not_synced");
        verify(repository).findTop24ByProjectAndArticleOrderByViewMonthDesc("fr.wikipedia", "Techno");
    }

    @Test
    void gapInTwelveMonthsNotChecked() {
        stored("fr.wikipedia", "Techno", LATEST, series(100, 10));
        List<WikimediaPageviewMonth> rows = new ArrayList<>(
                repository.findTop24ByProjectAndArticleOrderByViewMonthDesc("fr.wikipedia", "Techno"));
        rows.remove(5);
        when(repository.findTop24ByProjectAndArticleOrderByViewMonthDesc("fr.wikipedia", "Techno")).thenReturn(rows);

        assertThat(evaluate("house & techno", "techno").facts()).containsEntry("reason", "not_synced");

        // eleven months only
        stored("fr.wikipedia", "Techno", LATEST, 100, 110, 120, 130, 140, 150, 160, 170, 180, 190, 200);
        assertThat(evaluate("house & techno", "techno").facts()).containsEntry("reason", "not_synced");
    }

    @Test
    void staleLatestMonthNotChecked() {
        stored("fr.wikipedia", "Techno", YearMonth.of(2026, 7), series(100, 10));

        assertThat(evaluate("house & techno", "techno").facts()).containsEntry("reason", "stale");

        // 2026-08 is exactly max_age_months (2) back from October and still fresh
        stored("fr.wikipedia", "Techno", YearMonth.of(2026, 8), series(100, 10));
        assertThat(evaluate("house & techno", "techno").status()).isEqualTo(Finding.Status.FOUND);
    }

    @Test
    void lowVolumeNotChecked() {
        long[] fifty = new long[12];
        java.util.Arrays.fill(fifty, 50);
        stored("fr.wikipedia", "Techno", LATEST, fifty);

        assertThat(evaluate("house & techno", "techno").facts()).containsEntry("reason", "low_volume");
    }

    @Test
    void meanEqualToMinimumIsNotLowVolume() {
        stored("fr.wikipedia", "Techno", LATEST, series(100, 0));

        Finding f = evaluate("house & techno", "techno");

        assertThat(f.status()).isEqualTo(Finding.Status.CLEAR);
    }

    @Test
    void risingTrendIsOpportunity() {
        // 100..210: slope 10, mean 155, change 10 * 11 / 155 = 0.7097
        stored("fr.wikipedia", "Techno", LATEST, series(100, 10));

        Finding f = evaluate("house & techno", "techno");

        assertThat(f.status()).isEqualTo(Finding.Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.OPPORTUNITY);
        assertThat(f.strength()).isEqualTo(1);
        assertThat(f.sourceKind()).isEqualTo(SourceKind.STRUCTURED);
        assertThat(f.facts()).containsEntry("changePct", 71L)
                .containsEntry("article", "Techno")
                .containsEntry("project", "fr.wikipedia")
                .containsEntry("fromMonth", "2025-10")
                .containsEntry("toMonth", "2026-09")
                .containsEntry("meanViews", 155L);
        assertThat(f.url()).isEqualTo("https://fr.wikipedia.org/wiki/Techno");
    }

    @Test
    void fallingTrendIsRisk() {
        stored("fr.wikipedia", "Techno", LATEST, series(210, -10));

        Finding f = evaluate("house & techno", "techno");

        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.status()).isEqualTo(Finding.Status.FOUND);
        assertThat(f.strength()).isEqualTo(1);
        assertThat(f.facts()).containsEntry("changePct", -71L).containsEntry("meanViews", 155L);
    }

    @Test
    void flatTrendIsClear() {
        stored("fr.wikipedia", "Techno", LATEST, series(150, 0));

        Finding f = evaluate("house & techno", "techno");

        assertThat(f.status()).isEqualTo(Finding.Status.CLEAR);
        assertThat(f.strength()).isZero();
    }

    @Test
    void thresholdIsInclusive() {
        // 385..495: slope exactly 10, mean 440, change 110 / 440 = 0.25
        stored("fr.wikipedia", "Techno", LATEST, series(385, 10));
        assertThat(evaluate("house & techno", "techno").kind()).isEqualTo(Kind.OPPORTUNITY);
        assertThat(evaluate("house & techno", "techno").facts()).containsEntry("changePct", 25L);

        // 495..385: change exactly -0.25
        stored("fr.wikipedia", "Techno", LATEST, series(495, -10));
        Finding falling = evaluate("house & techno", "techno");
        assertThat(falling.status()).isEqualTo(Finding.Status.FOUND);
        assertThat(falling.kind()).isEqualTo(Kind.RISK);
    }

    @Test
    void unknownParamKeyFailsConstruction() throws IOException {
        QuestionBank bank = bankWithParams(
                "params: { rising_min: 0.25, falling_min: 0.25, min_mean_views: 100, max_age_months: 2, rising_mn: 1 }");

        assertThatThrownBy(() -> new TrendEvaluator(bank, articles(ARTICLES_YAML), repository, gates))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rising_mn");
    }

    @Test
    void missingParamFailsConstruction() throws IOException {
        QuestionBank bank = bankWithParams("params: { rising_min: 0.25, falling_min: 0.25, min_mean_views: 100 }");

        assertThatThrownBy(() -> new TrendEvaluator(bank, articles(ARTICLES_YAML), repository, gates))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max_age_months");
    }

    @Test
    void paramOutOfBoundsFailsConstruction() throws IOException {
        for (String bad : List.of(
                "params: { rising_min: 0, falling_min: 0.25, min_mean_views: 100, max_age_months: 2 }",
                "params: { rising_min: 5.5, falling_min: 0.25, min_mean_views: 100, max_age_months: 2 }",
                "params: { rising_min: 0.25, falling_min: 1.5, min_mean_views: 100, max_age_months: 2 }",
                "params: { rising_min: 0.25, falling_min: 0.25, min_mean_views: 0, max_age_months: 2 }",
                "params: { rising_min: 0.25, falling_min: 0.25, min_mean_views: 100, max_age_months: 13 }",
                "params: { rising_min: 0.25, falling_min: 0.25, min_mean_views: 100, max_age_months: 0 }")) {
            QuestionBank bank = bankWithParams(bad);
            assertThatThrownBy(() -> new TrendEvaluator(bank, articles(ARTICLES_YAML), repository, gates))
                    .as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("9.1");
        }
    }

    @Test
    void bankCountryWithoutLanguageFailsConstruction() {
        WikimediaArticles noUa = articles(ARTICLES_YAML.replace(", UA: uk", ""));

        assertThatThrownBy(() -> new TrendEvaluator(BANK, noUa, repository, gates))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("UA");
    }
}
