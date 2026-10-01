package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FootballDataPropertiesTest {

    private static FootballDataProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("imin.predictor.football", FootballDataProperties.class);
    }

    @Test
    void defaultsWithYamlKeysAbsent() {
        FootballDataProperties props = bind(Map.of());

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getApiKey()).isEmpty();
        assertThat(props.isOn()).isFalse();
    }

    @Test
    void blankEnabledStaysOff() {
        FootballDataProperties props = bind(Map.of("imin.predictor.football.enabled", "",
                "imin.predictor.football.api-key", "abc"));

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.isOn()).isFalse();
    }

    @Test
    void blankKeyBindsEmpty() {
        FootballDataProperties props = bind(Map.of("imin.predictor.football.api-key", "   "));
        FootballDataProperties padded = bind(Map.of("imin.predictor.football.api-key", " abc "));

        assertThat(props.getApiKey()).isEmpty();
        assertThat(padded.getApiKey()).isEqualTo("abc");
        FootballDataProperties direct = new FootballDataProperties();
        direct.setApiKey(null);
        assertThat(direct.getApiKey()).isEmpty();
    }

    @Test
    void onNeedsEnabledAndKey() {
        assertThat(props(false, "")).isFalse();
        assertThat(props(false, "abc")).isFalse();
        assertThat(props(true, "")).isFalse();
        assertThat(props(true, "abc")).isTrue();
    }

    private static boolean props(boolean enabled, String key) {
        return bind(Map.of("imin.predictor.football.enabled", String.valueOf(enabled),
                "imin.predictor.football.api-key", key)).isOn();
    }

    @Test
    void keyNeverInToString() {
        FootballDataProperties props = bind(Map.of("imin.predictor.football.api-key", "secret-abc"));

        assertThat(props.toString()).doesNotContain("secret-abc");
    }

    @Configuration
    @EnableConfigurationProperties({PredictorProperties.class, DateCheckProperties.class, WikimediaProperties.class})
    static class GateProperties {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CalendarConfig.class, GateProperties.class, SourceGates.class);

    @Test
    void enabledWithoutKeyFailsStartup() {
        runner.withPropertyValues("imin.predictor.football.enabled=true", "imin.predictor.football.api-key=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("PREDICTOR_FOOTBALL_ENABLED")
                            .hasMessageContaining("FOOTBALL_DATA_API_KEY");
                });
        runner.withPropertyValues("imin.predictor.football.enabled=true", "imin.predictor.football.api-key=abc")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(FootballFixturesSync.class));
        runner.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(FootballFixturesSync.class));
    }

    @Test
    void syncFollowsTheFullGateNotJustFlagAndKey() {
        // date check off: flag and key alone must not make the sync call football-data
        runner.withPropertyValues("imin.predictor.football.enabled=true", "imin.predictor.football.api-key=abc",
                        "imin.predictor.date-check.enabled=false")
                .run(ctx -> {
                    FootballFixturesSync sync = ctx.getBean(FootballFixturesSync.class);
                    assertThat(sync.scopePrefix()).isNull();
                    // returns before any HTTP call; a call here would reach the real API and fail the run
                    assertThat(sync.fetch(java.time.LocalDate.of(2026, 10, 1))).isEmpty();
                });
        runner.withPropertyValues("imin.predictor.football.enabled=true", "imin.predictor.football.api-key=abc",
                        "imin.predictor.date-check.enabled=true", "imin.predictor.calendar.sync-enabled=false")
                .run(ctx -> assertThat(ctx.getBean(FootballFixturesSync.class).scopePrefix()).isNull());
        runner.withPropertyValues("imin.predictor.football.enabled=true", "imin.predictor.football.api-key=abc",
                        "imin.predictor.date-check.enabled=true")
                .run(ctx -> assertThat(ctx.getBean(FootballFixturesSync.class).scopePrefix())
                        .isEqualTo("https://api.football-data.org/v4/competitions/"));
    }
}
