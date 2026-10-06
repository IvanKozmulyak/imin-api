package com.imin.iminapi.predictor.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The web-research keys: Java defaults with every yaml key absent, fallbacks for bad values, and bind failures. */
class DateCheckPropertiesTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PredictorConfig.class, DateCheckAccess.class);

    @Test
    void researchDefaultsHoldWithEveryKeyAbsent() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            DateCheckProperties props = ctx.getBean(DateCheckProperties.class);
            assertThat(props.getResearchOrgIds()).isEmpty();
            assertThat(props.getResearchDailyCapPerOrg()).isEqualTo(10);
            assertThat(props.getResearchDailyCapGlobal()).isEqualTo(100);
            assertThat(props.getResearchModel()).isEqualTo("anthropic/claude-haiku-4.5");
            assertThat(props.getResearchTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void blankOrNonPositiveResearchValuesBindDefaults() {
        runner.withPropertyValues(
                        "imin.predictor.date-check.research-org-ids=" + A + ",",
                        "imin.predictor.date-check.research-daily-cap-per-org=0",
                        "imin.predictor.date-check.research-daily-cap-global=-1",
                        "imin.predictor.date-check.research-model= ",
                        "imin.predictor.date-check.research-timeout=0s")
                .run(ctx -> {
                    DateCheckProperties props = ctx.getBean(DateCheckProperties.class);
                    assertThat(props.getResearchOrgIds()).isEqualTo(Set.of(A));
                    assertThat(props.getResearchDailyCapPerOrg()).isEqualTo(10);
                    assertThat(props.getResearchDailyCapGlobal()).isEqualTo(100);
                    assertThat(props.getResearchModel()).isEqualTo("anthropic/claude-haiku-4.5");
                    assertThat(props.getResearchTimeout()).isEqualTo(Duration.ofSeconds(30));
                });
        runner.withPropertyValues(
                        "imin.predictor.date-check.research-daily-cap-per-org=3",
                        "imin.predictor.date-check.research-daily-cap-global=7",
                        "imin.predictor.date-check.research-model= openai/gpt-4o-mini ",
                        "imin.predictor.date-check.research-timeout=5s")
                .run(ctx -> {
                    DateCheckProperties props = ctx.getBean(DateCheckProperties.class);
                    assertThat(props.getResearchDailyCapPerOrg()).isEqualTo(3);
                    assertThat(props.getResearchDailyCapGlobal()).isEqualTo(7);
                    assertThat(props.getResearchModel()).isEqualTo("openai/gpt-4o-mini");
                    assertThat(props.getResearchTimeout()).isEqualTo(Duration.ofSeconds(5));
                });
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

    @Test
    void malformedResearchOrgFailsStartup() {
        runner.withPropertyValues("imin.predictor.date-check.research-org-ids=not-a-uuid")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(findCause(ctx.getStartupFailure(), BindException.class).getMessage())
                            .contains("imin.predictor.date-check.research-org-ids");
                });
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) return type.cast(c);
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + t);
    }
}
