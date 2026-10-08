package com.imin.iminapi.predictor.sources.prim;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PrimConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PrimConfig.class);

    @Test
    void absentKeysStillStartTheContext() {
        // without a key the source stays off rather than failing startup
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void nonHttpsBaseFailsStartup() {
        runner.withPropertyValues("imin.predictor.prim.base-url=http://prim.iledefrance-mobilites.fr").run(ctx ->
                assertThat(ctx).getFailure().rootCause().hasMessageContaining("PREDICTOR_PRIM_BASE_URL must be an https URL"));
    }
}
