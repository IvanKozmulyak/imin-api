package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.service.ReforecastMilestones;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ReforecastMilestonesRadarTest {

    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 15);

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void notDueOnOrAfterTheNight(long daysOut) {
        assertThat(ReforecastMilestones.radarMilestoneDue(daysOut)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"1,2", "2,2", "3,7", "7,7", "8,14", "14,14", "15,30", "30,30"})
    void milestoneForDaysOut(long daysOut, int milestone) {
        assertThat(ReforecastMilestones.radarMilestoneDue(daysOut)).hasValue(milestone);
    }

    @Test
    void notDueBeyondThirtyDays() {
        assertThat(ReforecastMilestones.radarMilestoneDue(31)).isEmpty();
    }

    @Test
    void checkedBeforeWindowOpensIsNotDone() {
        assertThat(ReforecastMilestones.radarDone(NIGHT.minusDays(15), NIGHT, 14)).isFalse();
    }

    @Test
    void checkedOnOpeningDayIsDone() {
        assertThat(ReforecastMilestones.radarDone(NIGHT.minusDays(14), NIGHT, 14)).isTrue();
    }

    @Test
    void milestonesMatchSchemaCheck() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V167__date_check_radar.sql"),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("radar_milestone IN \\(([0-9, ]+)\\)").matcher(sql);
        assertThat(m.find()).as("V167 radar_milestone CHECK").isTrue();
        Set<Integer> schema = new TreeSet<>();
        for (String v : m.group(1).split(",")) schema.add(Integer.parseInt(v.trim()));
        assertThat(new TreeSet<>(ReforecastMilestones.RADAR_DAYS_OUT)).isEqualTo(schema).containsExactly(2, 7, 14, 30);
    }
}
