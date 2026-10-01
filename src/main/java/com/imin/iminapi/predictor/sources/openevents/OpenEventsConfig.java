package com.imin.iminapi.predictor.sources.openevents;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Open event listings. Each client gets its own HTTP client (not a bean) with explicit timeouts: the static
 * {@code RestClient.builder()} ignores Boot's client settings. A source whose id is outside the V165 CHECK fails boot.
 */
@Configuration
@EnableConfigurationProperties(OpenEventsProperties.class)
public class OpenEventsConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    static final String USER_AGENT = "imin-api open events sync (+https://imin.wtf)";

    private static RestClient.Builder builder() {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)))
                .defaultHeader("User-Agent", USER_AGENT);
    }

    @Bean
    public OpenAgendaClient openAgendaClient(OpenEventsProperties props) {
        return checked(new OpenAgendaClient(builder(), props));
    }

    @Bean
    public QueFaireAParisClient queFaireAParisClient() {
        return checked(new QueFaireAParisClient(builder()));
    }

    /** Loaded whatever the flags, so a bad file fails every boot. */
    @Bean
    public OpenEventCities openEventCities(ResourceLoader resources) {
        return OpenEventCities.load(resources);
    }

    /** Loaded whatever the flags, so a bad file fails every boot. */
    @Bean
    public GenreMatcher genreMatcher(ResourceLoader resources) {
        return GenreMatcher.load(resources);
    }

    /** One thread and a queue of one: the boot seed never competes with the other seeds or runs twice. */
    @Bean(name = "openEventsSyncExecutor")
    public Executor openEventsSyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(1);
        exec.setThreadNamePrefix("open-events-sync-");
        exec.initialize();
        return exec;
    }

    static <T extends OpenEventSource> T checked(T source) {
        if (!OpenEventSource.SOURCE_IDS.contains(source.id())) {
            throw new IllegalStateException("open event source id '" + source.id()
                    + "' is not allowed by ck_open_event_occurrence_source " + OpenEventSource.SOURCE_IDS);
        }
        if (!OpenEventSource.LICENCES.contains(source.licence())) {
            throw new IllegalStateException("open event source " + source.id() + " licence '" + source.licence()
                    + "' is not allowed by ck_open_event_occurrence_licence");
        }
        return source;
    }
}
