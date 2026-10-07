package com.imin.iminapi.predictor.sources;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.imin.iminapi.predictor.calendar.CalendarSyncProperties;
import com.imin.iminapi.predictor.calendar.FootballDataProperties;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsProperties;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the shipped {@code application.yaml} (not the test copy) against the exact Railway variable names,
 * with the machine's own environment removed, so a renamed placeholder closes its gate here.
 */
class SourceGatesBindingTest {

    private static final Set<String> ALL_GATES =
            Set.of("date-check", "weather", "wikimedia", "football", "openagenda", "quefaireaparis");

    /** Every variable an open-data gate reads, set to open it. */
    private static final Map<String, String> ALL_ON = Map.of(
            "PREDICTOR_DATE_CHECK_ENABLED", "true",
            "PREDICTOR_CALENDAR_SYNC_ENABLED", "true",
            "PREDICTOR_WEATHER_ENABLED", "true",
            "PREDICTOR_WIKIMEDIA_ENABLED", "true",
            "PREDICTOR_FOOTBALL_ENABLED", "true",
            "FOOTBALL_DATA_API_KEY", "football-key",
            "PREDICTOR_OPENAGENDA_ENABLED", "true",
            "OPENAGENDA_API_KEY", "oa_pk_test",
            "PREDICTOR_QUEFAIREAPARIS_ENABLED", "true");

    @Configuration
    @EnableConfigurationProperties({CalendarSyncProperties.class, FootballDataProperties.class,
            PredictorProperties.class, DateCheckProperties.class, WikimediaProperties.class,
            OpenEventsProperties.class})
    static class GateProperties {}

    private static ApplicationContextRunner runner(Map<String, String> env) {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> useOnly(ctx, env))
                .withUserConfiguration(GateProperties.class, SourceGates.class);
    }

    private static void useOnly(ConfigurableApplicationContext ctx, Map<String, String> env) {
        MutablePropertySources sources = ctx.getEnvironment().getPropertySources();
        // The developer's own env or -D flags must not open or close a gate here.
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<String, Object>(env)));
        try {
            List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                    .load("shipped", new FileSystemResource("src/main/resources/application.yaml"));
            yaml.forEach(sources::addLast);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> without(String name, String replacement) {
        Map<String, String> env = new HashMap<>(ALL_ON);
        if (replacement == null) env.remove(name);
        else env.put(name, replacement);
        return env;
    }

    @Test
    void everyOpenDataGateOpensFromItsEnvName() {
        runner(ALL_ON).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            SourceGates gates = ctx.getBean(SourceGates.class);
            assertThat(gates.keys()).isEqualTo(ALL_GATES);
            ALL_GATES.forEach(key -> assertThat(gates.isOn(key)).as(key).isTrue());
        });
    }

    static Stream<Arguments> ownEnvAbsent() {
        Set<String> needDateCheck = Set.of("date-check", "wikimedia", "football", "openagenda", "quefaireaparis");
        return Stream.of(
                Arguments.of("PREDICTOR_DATE_CHECK_ENABLED", null, needDateCheck),
                // Calendar sync defaults to true, so only an explicit false closes it.
                Arguments.of("PREDICTOR_CALENDAR_SYNC_ENABLED", "false", Set.of("date-check", "football")),
                Arguments.of("PREDICTOR_WEATHER_ENABLED", null, Set.of("weather")),
                Arguments.of("PREDICTOR_WIKIMEDIA_ENABLED", null, Set.of("wikimedia")),
                Arguments.of("PREDICTOR_FOOTBALL_ENABLED", null, Set.of("football")),
                Arguments.of("FOOTBALL_DATA_API_KEY", null, Set.of("football")),
                Arguments.of("PREDICTOR_OPENAGENDA_ENABLED", null, Set.of("openagenda")),
                Arguments.of("PREDICTOR_QUEFAIREAPARIS_ENABLED", null, Set.of("quefaireaparis")));
    }

    @ParameterizedTest(name = "{0}={1} closes {2}")
    @MethodSource("ownEnvAbsent")
    void eachGateClosesWhenItsOwnEnvIsAbsent(String name, String replacement, Set<String> closed) {
        runner(without(name, replacement)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            SourceGates gates = ctx.getBean(SourceGates.class);
            ALL_GATES.forEach(key -> assertThat(gates.isOn(key)).as(key).isEqualTo(!closed.contains(key)));
        });
    }

    @Test
    void gateLogLineNeverCarriesAnApiKey() {
        Logger logger = (Logger) LoggerFactory.getLogger(SourceGates.class);
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            // every gate open, so both keys are set while the line is written
            runner(ALL_ON).run(ctx -> {
                assertThat(appender.list).isEmpty();
                // Published through the context, so the @EventListener wiring is what is tested.
                ctx.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], ctx, Duration.ZERO));
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
        assertThat(appender.list).singleElement().satisfies(e ->
                assertThat(e.getFormattedMessage()).doesNotContain("oa_pk_test").doesNotContain("football-key"));
    }
}
