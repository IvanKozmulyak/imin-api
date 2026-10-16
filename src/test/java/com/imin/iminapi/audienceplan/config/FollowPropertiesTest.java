package com.imin.iminapi.audienceplan.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The follow path's CORS list takes exact origins; a wildcard would open it to any site, so startup fails. */
class FollowPropertiesTest {

    @EnableConfigurationProperties(FollowProperties.class)
    static class Binding {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.imin.wtf", "https://imin.wtf,*"})
    void aWildcardOrigin_stopsStartup(String origins) {
        runner.withPropertyValues("imin.follow.allowed-origins=" + origins).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("takes exact origins");
        });
    }
}
