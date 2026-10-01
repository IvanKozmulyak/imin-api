package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.model.WikimediaPageviewMonth;
import com.imin.iminapi.predictor.repository.WikimediaPageviewMonthRepository;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaArticles;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaArticles.Article;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Question 9.1, genre interest trend: a least-squares line through the last 12 stored months of the
 * genre article's pageviews in the country's language. Rising is an opportunity, falling a risk.
 * Reads only the synced table, so a check never waits on Wikimedia; the answer does not depend on the date.
 */
@Component
public class TrendEvaluator implements QuestionEvaluator {

    static final String ID = "9.1";
    static final String GATE = "wikimedia";
    static final int MONTHS = 12;
    private static final Set<String> PARAM_KEYS = Set.of("rising_min", "falling_min", "min_mean_views", "max_age_months");

    private final WikimediaArticles articles;
    private final WikimediaPageviewMonthRepository repository;
    private final SourceGates gates;
    private final double risingMin;
    private final double fallingMin;
    private final double minMeanViews;
    private final int maxAgeMonths;

    public TrendEvaluator(QuestionBank bank, WikimediaArticles articles, WikimediaPageviewMonthRepository repository,
                          SourceGates gates) {
        this.articles = articles;
        this.repository = repository;
        this.gates = gates;
        Question q = bank.questions().stream()
                .filter(x -> x.id().equals(ID) && x.source() == SourceKind.STRUCTURED).findFirst()
                .orElseThrow(() -> new IllegalStateException("predictor question " + ID + ": not in the bank"));
        for (String key : q.params().keySet()) {
            if (!PARAM_KEYS.contains(key)) {
                throw new IllegalStateException("predictor question " + ID + ": unknown params." + key);
            }
        }
        this.risingMin = bounded(q, "rising_min", 0, false, 5);
        this.fallingMin = bounded(q, "falling_min", 0, false, 1);
        this.minMeanViews = bounded(q, "min_mean_views", 1, true, Double.MAX_VALUE);
        double age = bounded(q, "max_age_months", 1, true, 12);
        if (age != Math.rint(age)) {
            throw new IllegalStateException("predictor question " + ID + ": params.max_age_months must be whole");
        }
        this.maxAgeMonths = (int) age;
        for (String country : q.countries()) {
            if (articles.language(country).isEmpty()) {
                throw new IllegalStateException("predictor question " + ID + ": country " + country
                        + " has no language in the Wikimedia article map");
            }
        }
    }

    /** {@code min < v <= max}, or {@code min <= v <= max} when inclusive. */
    private static double bounded(Question q, String key, double min, boolean inclusive, double max) {
        double v = Params.of(q, key).doubleValue();
        if ((inclusive ? v < min : v <= min) || v > max) {
            throw new IllegalStateException("predictor question " + ID + ": params." + key + " out of bounds: " + v);
        }
        return v;
    }

    @Override
    public SourceKind source() { return SourceKind.STRUCTURED; }

    @Override
    public Set<String> questionIds() { return Set.of(ID); }

    @Override
    public Finding evaluate(Question q, DateCheckInput in, LocalDate date) {
        if (!gates.isOn(GATE)) return Finding.notChecked(q, "source_off");
        Optional<Article> found = articles.lookup(in.country(), in.genreFamily(), in.subGenre());
        if (found.isEmpty()) return Finding.notChecked(q, "no_article");
        Article article = found.get();

        List<WikimediaPageviewMonth> rows =
                repository.findTop24ByProjectAndArticleOrderByViewMonthDesc(article.project(), article.title());
        if (rows.isEmpty()) return Finding.notChecked(q, "not_synced");
        Map<YearMonth, Long> byMonth = new HashMap<>();
        YearMonth latest = null;
        for (WikimediaPageviewMonth r : rows) {
            YearMonth m = YearMonth.from(r.getViewMonth());
            byMonth.putIfAbsent(m, r.getViews());
            if (latest == null || m.isAfter(latest)) latest = m;
        }
        long[] views = new long[MONTHS];
        for (int i = 0; i < MONTHS; i++) {
            Long v = byMonth.get(latest.minusMonths(MONTHS - 1 - i));
            if (v == null) return Finding.notChecked(q, "not_synced");
            views[i] = v;
        }
        if (latest.isBefore(YearMonth.from(in.today()).minusMonths(maxAgeMonths))) return Finding.notChecked(q, "stale");

        double mean = 0;
        for (long v : views) mean += v;
        mean /= MONTHS;
        if (mean < minMeanViews) return Finding.notChecked(q, "low_volume");

        // Slope over x = 0..11 (mean 5.5), as the change across the year relative to the mean.
        double xMean = (MONTHS - 1) / 2.0;
        double num = 0;
        double den = 0;
        for (int i = 0; i < MONTHS; i++) {
            num += (i - xMean) * (views[i] - mean);
            den += (i - xMean) * (i - xMean);
        }
        double change = (num / den) * (MONTHS - 1) / mean;
        Kind kind;
        if (change >= risingMin) kind = Kind.OPPORTUNITY;
        else if (change <= -fallingMin) kind = Kind.RISK;
        else return Finding.clear(q);

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("article", article.title());
        facts.put("project", article.project());
        facts.put("fromMonth", latest.minusMonths(MONTHS - 1).toString());
        facts.put("toMonth", latest.toString());
        facts.put("changePct", Math.round(change * 100));
        facts.put("meanViews", Math.round(mean));
        return Finding.found(q, kind, 1, facts, article.url());
    }
}
