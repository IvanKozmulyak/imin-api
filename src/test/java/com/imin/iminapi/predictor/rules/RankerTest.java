package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.DateResult.Verdict;
import com.imin.iminapi.predictor.rules.Ranker.Bucket;
import com.imin.iminapi.predictor.rules.Ranker.Candidate;
import com.imin.iminapi.predictor.rules.Ranker.Ranked;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RankerTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 6);

    private static Candidate c(int dayOffset, String coverage, Verdict v, int risk, int opp, int lead) {
        return new Candidate(D.plusDays(dayOffset), new DateResult(v, risk, opp, new BigDecimal(coverage), List.of()),
                lead);
    }

    @Test
    void rankingSkipsDifferentCoverageBuckets() {
        List<Ranked> out = Ranker.rank(List.of(c(0, "0.900", Verdict.ADJUST, 5, 0, 30),
                c(1, "0.700", Verdict.GOOD, 0, 0, 30)));

        assertThat(out).containsExactly(new Ranked(D, Bucket.HIGH, 1), new Ranked(D.plusDays(1), Bucket.MID, 1));
    }

    @Test
    void bucketEdges() {
        List<Ranked> out = Ranker.rank(List.of(c(0, "0.800", Verdict.GOOD, 0, 0, 30),
                c(1, "0.799", Verdict.GOOD, 0, 0, 30), c(2, "0.600", Verdict.GOOD, 0, 0, 30)));

        assertThat(out).extracting(Ranked::bucket).containsExactly(Bucket.HIGH, Bucket.MID, Bucket.MID);
    }

    @Test
    void riskThenOppThenLeadOrder() {
        List<Ranked> out = Ranker.rank(List.of(
                c(0, "1.000", Verdict.GOOD, 1, 9, 90),
                c(1, "1.000", Verdict.GOOD, 0, 2, 90),
                c(2, "1.000", Verdict.GOOD, 0, 5, 10),
                c(3, "1.000", Verdict.GOOD, 0, 5, 20)));

        assertThat(out).extracting(Ranked::date)
                .containsExactly(D, D.plusDays(1), D.plusDays(2), D.plusDays(3));
        assertThat(out).extracting(Ranked::rank).containsExactly(4, 3, 2, 1);
    }

    @Test
    void belowMinCoverageUnranked() {
        List<Ranked> out = Ranker.rank(List.of(c(0, "0.500", Verdict.NOT_ENOUGH_DATA, 0, 0, 30),
                c(1, "0.300", Verdict.MOVE, 8, 0, 30), c(2, "0.900", Verdict.MOVE, 8, 0, 30)));

        assertThat(out).containsExactly(new Ranked(D, Bucket.NONE, null),
                new Ranked(D.plusDays(1), Bucket.NONE, null), new Ranked(D.plusDays(2), Bucket.HIGH, 1));
    }

    @Test
    void notEnoughDataInsideMidBucketUnranked() {
        List<Ranked> out = Ranker.rank(List.of(c(0, "0.700", Verdict.NOT_ENOUGH_DATA, 0, 0, 30),
                c(1, "0.700", Verdict.GOOD, 2, 0, 30)));

        assertThat(out).containsExactly(new Ranked(D, Bucket.MID, null), new Ranked(D.plusDays(1), Bucket.MID, 1));
    }

    @Test
    void fullTieBrokenByDate() {
        List<Ranked> out = Ranker.rank(List.of(c(2, "1.000", Verdict.GOOD, 1, 1, 30),
                c(0, "1.000", Verdict.GOOD, 1, 1, 30), c(1, "1.000", Verdict.GOOD, 1, 1, 30)));

        assertThat(out).extracting(Ranked::rank).containsExactly(3, 1, 2);
    }
}
