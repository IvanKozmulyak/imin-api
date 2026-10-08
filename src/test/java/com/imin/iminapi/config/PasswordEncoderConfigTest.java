package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderConfigTest {

    // OS env and JVM properties are dropped so a developer's IMIN_AUTH_BCRYPTSTRENGTH cannot decide a branch.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ctx -> {
                ctx.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                ctx.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withUserConfiguration(PasswordEncoderConfig.class);

    @Test
    void defaultsToCostTwelve() {
        assertThat(PasswordEncoderConfig.DEFAULT_STRENGTH).isEqualTo(12);
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(BCryptPasswordEncoder.class).encode("x")).startsWith("$2a$12$");
        });
    }

    @Test
    void refusesALowStrengthOutsideTheTestProfile() {
        runner.withPropertyValues("imin.auth.bcrypt-strength=4").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("imin.auth.bcrypt-strength=4");
        });
    }

    @Test
    void aLowCostEncoderStillVerifiesCostTwelveHashes() {
        runner.withPropertyValues("imin.auth.bcrypt-strength=4", "spring.profiles.active=test").run(ctx -> {
            String prodHash = new BCryptPasswordEncoder(12).encode("pw");
            assertThat(ctx.getBean(BCryptPasswordEncoder.class).matches("pw", prodHash)).isTrue();
        });
    }
}
