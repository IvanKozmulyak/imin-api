package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.service.NpsScore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class NpsScoreTest {

    /** Promoters 9-10, detractors 0-6; (p − d) / n × 100 at scale 3, half-up; withheld below 5 answers. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", value = {
            "'four answers [10,10,10,10]', 4, 0, 4, null",
            "'[10,9,8,6,0]', 2, 2, 5, 0.000",
            "'[10,10,9,7,3]', 3, 1, 5, 40.000",
            "'[10,9,9,9,8,2]', 4, 1, 6, 50.000",
            "'3 promoters, 1 detractor, 7 answers', 3, 1, 7, 28.571",
            "'all detractors [0,1,2,3,4]', 0, 5, 5, -100.000"})
    void eventNps(String label, long promoters, long detractors, long answers, BigDecimal expected) {
        assertThat(NpsScore.of(promoters, detractors, answers)).isEqualTo(expected);
    }
}
