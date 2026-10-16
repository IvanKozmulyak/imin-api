package com.imin.iminapi.email;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Blank must mean nobody (the test config shadows the yaml, so the Java default is what every test sees), a typo must not boot. */
class TeamAlertConfigTest {

    // OS env and system properties removed so a developer's own value cannot change the result.
    private final ApplicationContextRunner isolated = new ApplicationContextRunner()
            .withUserConfiguration(ResendConfig.class)
            .withInitializer(ctx -> {
                var sources = ctx.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            });

    private ApplicationContextRunner shippedYaml() {
        return isolated.withInitializer(ctx -> {
            try {
                new YamlPropertySourceLoader()
                        .load("shipped", new FileSystemResource("src/main/resources/application.yaml"))
                        .forEach(ctx.getEnvironment().getPropertySources()::addLast);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private enum Source { JAVA_DEFAULT, SHIPPED_YAML }

    static Stream<Arguments> inboxLists() {
        return Stream.of(
                Arguments.of("no yaml at all: the Java default", Source.JAVA_DEFAULT, new String[0], List.of()),
                Arguments.of("shipped yaml, env var unset", Source.SHIPPED_YAML, new String[0], List.of()),
                Arguments.of("shipped yaml, env var empty", Source.SHIPPED_YAML,
                        new String[] {"IMIN_TEAM_ALERT_EMAILS="}, List.of()),
                Arguments.of("a blank element and a trailing comma are dropped", Source.SHIPPED_YAML,
                        new String[] {"IMIN_TEAM_ALERT_EMAILS=alerts@imin.test, ,ivan@imin.test,"},
                        List.of("alerts@imin.test", "ivan@imin.test")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inboxLists")
    void the_inbox_list_is_empty_unless_the_env_var_names_addresses(
            String label, Source source, String[] env, List<String> expected) {
        ApplicationContextRunner runner = source == Source.SHIPPED_YAML ? shippedYaml() : isolated;

        runner.withPropertyValues(env).run(ctx ->
                assertThat(ctx.getBean(TeamAlertProperties.class).getEmails()).containsExactlyElementsOf(expected));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ivan", "ivan@imin.test;", "Ivan <ivan@imin.test>", "ivan@imin@test", "@imin.test", "ivan@"})
    void an_entry_that_is_not_one_address_stops_startup(String entry) {
        isolated.withPropertyValues("imin.team-alerts.emails=alerts@imin.test," + entry).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .hasStackTraceContaining("imin.team-alerts.emails")
                    .rootCause().hasMessageContaining(entry);
        });
    }
}
