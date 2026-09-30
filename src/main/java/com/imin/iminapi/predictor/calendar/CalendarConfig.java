package com.imin.iminapi.predictor.calendar;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Reference calendar sources. The HTTP client is private to them (not a bean), with explicit
 * timeouts: the static {@code RestClient.builder()} ignores Boot's client settings.
 */
@Configuration
@EnableConfigurationProperties(CalendarSyncProperties.class)
public class CalendarConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    static final String USER_AGENT = "imin-api reference calendar sync (+https://imin.wtf)";

    private final RestClient http = RestClient.builder()
            .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                    .build(HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)))
            .defaultHeader("User-Agent", USER_AGENT)
            .build();

    @Bean
    public FrenchHolidaySync frenchHolidaySync(CalendarSyncProperties props) {
        return new FrenchHolidaySync(http, props);
    }

    @Bean
    public FrenchSchoolCalendarSync frenchSchoolCalendarSync(CalendarSyncProperties props) {
        return new FrenchSchoolCalendarSync(http, props);
    }

    @Bean
    public OpenHolidaysSync openHolidaysSync(CalendarSyncProperties props) {
        return new OpenHolidaysSync(http, props);
    }

    @Bean
    public ComputedCalendar computedCalendar(CalendarSyncProperties props) {
        return new ComputedCalendar(props);
    }

    /** One thread: the startup sync must never hold up boot or run twice at once on this JVM. */
    @Bean(name = "referenceCalendarSyncExecutor")
    public Executor referenceCalendarSyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(1);
        exec.setThreadNamePrefix("ref-calendar-");
        exec.initialize();
        return exec;
    }
}
