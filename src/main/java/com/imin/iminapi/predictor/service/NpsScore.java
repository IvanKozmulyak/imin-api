package com.imin.iminapi.predictor.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Event Net Promoter Score: (promoters − detractors) / answers × 100, scale 3, half-up. Promoters score 9–10,
 * detractors 0–6 (Reichheld's bands). Withheld below {@link #MIN_ANSWERS} answers, so one person's score is
 * never restated as a figure.
 */
public final class NpsScore {

    public static final int MIN_ANSWERS = 5;

    private NpsScore() {}

    public static BigDecimal of(long promoters, long detractors, long answers) {
        if (answers < MIN_ANSWERS) return null;
        return BigDecimal.valueOf((promoters - detractors) * 100L)
                .divide(BigDecimal.valueOf(answers), 3, RoundingMode.HALF_UP);
    }
}
