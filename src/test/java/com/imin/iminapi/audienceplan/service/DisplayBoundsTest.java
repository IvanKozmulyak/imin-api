package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse;
import com.imin.iminapi.audienceplan.service.DisplayBounds.Range;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One case per branch of the webapp's displayRange and planConfidence, as ported. */
class DisplayBoundsTest {

    @Test
    void prior_countRoundsOutwardTo5() {
        assertThat(DisplayBounds.count(26, 93, "prior")).isEqualTo(new Range(25, 95));
        assertThat(DisplayBounds.count(25, 95, "prior")).isEqualTo(new Range(25, 95));
    }

    @Test
    void prior_equalBoundsWidenByOneStep() {
        assertThat(DisplayBounds.count(20, 20, "prior")).isEqualTo(new Range(20, 25));
        assertThat(DisplayBounds.count(0, 0, "prior")).isEqualTo(new Range(0, 5));
    }

    @Test
    void prior_percentWideningPast100_clampsTo95To100() {
        assertThat(DisplayBounds.percent(1.0, 1.0, "prior")).isEqualTo(new Range(95, 100));
    }

    @Test
    void prior_percentEqualBoundsBelow100_widenUp() {
        assertThat(DisplayBounds.percent(0.20, 0.20, "prior")).isEqualTo(new Range(20, 25));
    }

    @Test
    void prior_percentConvertsThenRoundsOutward() {
        assertThat(DisplayBounds.percent(0.03, 0.12, "prior")).isEqualTo(new Range(0, 15));
    }

    @Test
    void own_andImin_roundToWhole_withoutWidening() {
        assertThat(DisplayBounds.count(26, 93, "own")).isEqualTo(new Range(26, 93));
        assertThat(DisplayBounds.count(20, 20, "imin")).isEqualTo(new Range(20, 20));
        // 0.035 → 3.5 % → 4 (half up, as JavaScript's Math.round).
        assertThat(DisplayBounds.percent(0.035, 0.124, "own")).isEqualTo(new Range(4, 12));
    }

    @Test
    void missingBound_staysNull_andTheOtherIsStillShaped() {
        assertThat(DisplayBounds.count(null, 93, "prior")).isEqualTo(new Range(null, 95));
        assertThat(DisplayBounds.percent(0.12, null, "own")).isEqualTo(new Range(12, null));
        assertThat(DisplayBounds.percent(Double.NaN, Double.POSITIVE_INFINITY, "own")).isEqualTo(new Range(null, null));
    }

    @Test
    void planConfidence_isTheLeastSureSegment() {
        assertThat(DisplayBounds.planConfidence(List.of(seg("own"), seg("prior"), seg("imin")))).isEqualTo("prior");
        assertThat(DisplayBounds.planConfidence(List.of(seg("own"), seg("imin")))).isEqualTo("imin");
        assertThat(DisplayBounds.planConfidence(List.of(seg("own")))).isEqualTo("own");
    }

    @Test
    void planConfidence_withoutSegments_isPrior() {
        assertThat(DisplayBounds.planConfidence(List.of())).isEqualTo("prior");
    }

    @Test
    void gap_coldWithEqualBounds_staysTheWholeTarget() {
        assertThat(DisplayBounds.gap(SummaryFixtures.cold(), "prior")).isEqualTo(new Range(255, 255));
    }

    @Test
    void gap_coldWithARange_orWarm_isRoundedLikeAnyRange() {
        AudiencePlanResponse coldRange = SummaryFixtures.withGap(SummaryFixtures.cold(), 251, 255, java.util.List.of());
        assertThat(DisplayBounds.gap(coldRange, "prior")).isEqualTo(new Range(250, 255));
        AudiencePlanResponse warmEqual = SummaryFixtures.withGap(SummaryFixtures.warm(), 40, 40, java.util.List.of());
        assertThat(DisplayBounds.gap(warmEqual, "prior")).isEqualTo(new Range(40, 45));
    }

    private static AudiencePlanResponse.Segment seg(String confidence) {
        return new AudiencePlanResponse.Segment("loyal", "same", 10, new AudiencePlanResponse.Rate(0.1, 0.2, 0.3), 1.6,
                new AudiencePlanResponse.TicketRange(1, 2, 3), confidence, null);
    }
}
