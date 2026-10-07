package com.imin.iminapi.predictor.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Blank env vars bind safe defaults, bad values fall back, and an over-long research model fails startup. */
class DateCheckPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PredictorConfig.class, DateCheckAccess.class);

    @Test
    void blankEnvVarsBindSafeDefaults() {
        // The shipped placeholders, with each env var stubbed to empty so a local value cannot leak in.
        runner.withPropertyValues(
                        "PREDICTOR_DATE_CHECK_ENABLED=",
                        "PREDICTOR_DATE_CHECK_ALL_ORGS=",
                        "PREDICTOR_DATE_CHECK_RADAR_ENABLED=",
                        "PREDICTOR_DATE_CHECK_MAX_DATES=",
                        "PREDICTOR_DATE_CHECK_MAX_HORIZON_MONTHS=",
                        "PREDICTOR_DATE_CHECK_RESEARCH_DAILY_CAP_PER_ORG=",
                        "PREDICTOR_DATE_CHECK_RESEARCH_DAILY_CAP_GLOBAL=",
                        "PREDICTOR_DATE_CHECK_RESEARCH_MODEL=",
                        "PREDICTOR_DATE_CHECK_RESEARCH_TIMEOUT=",
                        "imin.predictor.date-check.enabled=${PREDICTOR_DATE_CHECK_ENABLED:false}",
                        "imin.predictor.date-check.all-orgs=${PREDICTOR_DATE_CHECK_ALL_ORGS:false}",
                        "imin.predictor.date-check.radar-enabled=${PREDICTOR_DATE_CHECK_RADAR_ENABLED:false}",
                        "imin.predictor.date-check.max-dates=${PREDICTOR_DATE_CHECK_MAX_DATES:5}",
                        "imin.predictor.date-check.max-horizon-months=${PREDICTOR_DATE_CHECK_MAX_HORIZON_MONTHS:18}",
                        "imin.predictor.date-check.research-daily-cap-per-org=${PREDICTOR_DATE_CHECK_RESEARCH_DAILY_CAP_PER_ORG:10}",
                        "imin.predictor.date-check.research-daily-cap-global=${PREDICTOR_DATE_CHECK_RESEARCH_DAILY_CAP_GLOBAL:100}",
                        "imin.predictor.date-check.research-model=${PREDICTOR_DATE_CHECK_RESEARCH_MODEL:anthropic/claude-haiku-4.5}",
                        "imin.predictor.date-check.research-timeout=${PREDICTOR_DATE_CHECK_RESEARCH_TIMEOUT:30s}")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    DateCheckProperties props = ctx.getBean(DateCheckProperties.class);
                    assertThat(props.getEnabled()).isFalse();
                    assertThat(props.getAllOrgs()).isFalse();
                    assertThat(props.getRadarEnabled()).isFalse();
                    assertThat(props.getMaxDates()).isEqualTo(5);
                    assertThat(props.getMaxHorizonMonths()).isEqualTo(18);
                    assertThat(props.getResearchDailyCapPerOrg()).isEqualTo(10);
                    assertThat(props.getResearchDailyCapGlobal()).isEqualTo(100);
                    assertThat(props.getResearchModel()).isEqualTo("anthropic/claude-haiku-4.5");
                    assertThat(props.getResearchTimeout()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    static Stream<Arguments> boundValues() {
        Function<DateCheckProperties, Object> maxDates = DateCheckProperties::getMaxDates;
        Function<DateCheckProperties, Object> horizon = DateCheckProperties::getMaxHorizonMonths;
        Function<DateCheckProperties, Object> perOrg = DateCheckProperties::getResearchDailyCapPerOrg;
        Function<DateCheckProperties, Object> global = DateCheckProperties::getResearchDailyCapGlobal;
        Function<DateCheckProperties, Object> model = DateCheckProperties::getResearchModel;
        Function<DateCheckProperties, Object> timeout = DateCheckProperties::getResearchTimeout;
        return Stream.of(
                Arguments.of("max-dates=0", maxDates, 5),
                Arguments.of("max-dates=3", maxDates, 3),
                Arguments.of("max-horizon-months=-1", horizon, 18),
                Arguments.of("max-horizon-months=12", horizon, 12),
                Arguments.of("research-daily-cap-per-org=0", perOrg, 10),
                Arguments.of("research-daily-cap-per-org=3", perOrg, 3),
                Arguments.of("research-daily-cap-global=-1", global, 100),
                Arguments.of("research-daily-cap-global=7", global, 7),
                Arguments.of("research-model= ", model, "anthropic/claude-haiku-4.5"),
                Arguments.of("research-model= openai/gpt-4o-mini ", model, "openai/gpt-4o-mini"),
                Arguments.of("research-timeout=0s", timeout, Duration.ofSeconds(30)),
                Arguments.of("research-timeout=5s", timeout, Duration.ofSeconds(5)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundValues")
    void nonPositiveOrBlankFallsBackToDefault(String keyValue, Function<DateCheckProperties, Object> getter,
                                              Object expected) {
        runner.withPropertyValues("imin.predictor.date-check." + keyValue)
                .run(ctx -> assertThat(getter.apply(ctx.getBean(DateCheckProperties.class))).isEqualTo(expected));
    }

    @Test
    void researchModelLongerThanTheLedgerColumnFailsStartup() {
        runner.withPropertyValues("imin.predictor.date-check.research-model=" + "m".repeat(129))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(findCause(ctx.getStartupFailure(), BindException.class)).isNotNull();
                    assertThat(findCause(ctx.getStartupFailure(), IllegalArgumentException.class).getMessage())
                            .isEqualTo("research-model is longer than 128 characters");
                });
        runner.withPropertyValues("imin.predictor.date-check.research-model=" + "m".repeat(128))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) return type.cast(c);
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + t);
    }
}
