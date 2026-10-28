package com.imin.iminapi.service.event;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The re-arm gap is what keeps a fan to one email per 6 h: the Java and shipped defaults agree, and no value can remove it. */
class NotifyPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NotifyConfig.class);

    @Test
    void rearmGap_defaultsToSixHours_andAZeroOrNegativeValueCannotSwitchItOff() {
        runner.run(ctx -> assertThat(ctx.getBean(NotifyProperties.class).getRearmMinGap()).isEqualTo(Duration.ofHours(6)));
        shippedYaml().run(ctx ->
                assertThat(ctx.getBean(NotifyProperties.class).getRearmMinGap()).isEqualTo(Duration.ofHours(6)));
        for (String value : new String[] {"PT0S", "-PT1H"}) {
            runner.withPropertyValues("imin.notify.rearm-min-gap=" + value).run(ctx ->
                    assertThat(ctx.getBean(NotifyProperties.class).getRearmMinGap()).isEqualTo(Duration.ofHours(6)));
        }
        runner.withPropertyValues("imin.notify.rearm-min-gap=PT12H").run(ctx ->
                assertThat(ctx.getBean(NotifyProperties.class).getRearmMinGap()).isEqualTo(Duration.ofHours(12)));
    }

    private ApplicationContextRunner shippedYaml() {
        return runner.withInitializer(ctx -> {
            var sources = ctx.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            try {
                new YamlPropertySourceLoader()
                        .load("shipped", new FileSystemResource("src/main/resources/application.yaml"))
                        .forEach(sources::addLast);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
