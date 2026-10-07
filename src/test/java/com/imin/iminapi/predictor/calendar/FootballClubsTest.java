package com.imin.iminapi.predictor.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.util.EventNormalization;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FootballClubsTest {

    @Test
    void everyRecordedFl1TeamIsMapped() throws Exception {
        JsonNode root = new ObjectMapper().readTree(CalendarFixtures.text("football-FL1.json"));
        for (JsonNode m : root.path("matches")) {
            assertThat(FootballClubs.mapped(m.path("homeTeam").path("id").asInt())).as(m.path("homeTeam").toString()).isTrue();
            assertThat(FootballClubs.mapped(m.path("awayTeam").path("id").asInt())).as(m.path("awayTeam").toString()).isTrue();
        }
        assertThat(FootballClubs.mapped(99999)).isFalse();
    }

    @Test
    void ofLooksUpTheNormalisedCityKey() {
        assertThat(FootballClubs.of(EventNormalization.cityKey(" Paris "))).containsExactlyInAnyOrder(524, 1045);
        assertThat(FootballClubs.of("metz")).isEmpty();
        assertThat(FootballClubs.of(null)).isEmpty();
    }
}
