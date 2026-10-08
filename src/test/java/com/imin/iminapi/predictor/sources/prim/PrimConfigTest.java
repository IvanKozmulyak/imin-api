package com.imin.iminapi.predictor.sources.prim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PrimConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PrimConfig.class);

    @Test
    void absentKeysStillStartTheContext() {
        // without a key the source stays off rather than failing startup
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(PrimProperties.class).getStopRadiusM()).isEqualTo(800);
        });
    }

    @ParameterizedTest(name = "radius {0} fails startup")
    @ValueSource(ints = {99, 2001})
    void radiusOutsideBoundsFailsStartup(int radius) {
        runner.withPropertyValues("imin.predictor.prim.stop-radius-m=" + radius).run(ctx ->
                assertThat(ctx).getFailure().rootCause().hasMessageContaining("PREDICTOR_PRIM_STOP_RADIUS_M"));
    }

    @ParameterizedTest(name = "radius {0} starts")
    @ValueSource(ints = {100, 2000})
    void radiusAtBoundsStarts(int radius) {
        runner.withPropertyValues("imin.predictor.prim.stop-radius-m=" + radius).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(PrimProperties.class).getStopRadiusM()).isEqualTo(radius);
        });
    }

    @Test
    void nonHttpsBaseFailsStartup() {
        runner.withPropertyValues("imin.predictor.prim.base-url=http://prim.iledefrance-mobilites.fr").run(ctx ->
                assertThat(ctx).getFailure().rootCause().hasMessageContaining("PREDICTOR_PRIM_BASE_URL must be an https URL"));
    }
}
