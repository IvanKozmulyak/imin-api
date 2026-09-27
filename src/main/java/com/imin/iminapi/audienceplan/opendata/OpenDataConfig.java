package com.imin.iminapi.audienceplan.opendata;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/**
 * Open-data fetchers. The HTTP client is private to them (not a bean), with explicit timeouts:
 * the static {@code RestClient.builder()} ignores Boot's client settings.
 */
@Configuration
public class OpenDataConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    static final String USER_AGENT = "imin-api open-data loader (+https://imin.wtf)";

    private final RestClient http = RestClient.builder()
            .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                    .build(HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)))
            .defaultHeader("User-Agent", USER_AGENT)
            .build();

    @Bean
    public OpenDataCities openDataCities() {
        return OpenDataCities.load();
    }

    @Bean
    public InseeMelodiFetcher inseeMelodiFetcher(Clock clock) {
        return new InseeMelodiFetcher(http, new MelodiThrottle(clock, Sleeper.REAL), Sleeper.REAL);
    }

    @Bean
    public MesrAtlasFetcher mesrAtlasFetcher() {
        return new MesrAtlasFetcher(http);
    }

    @Bean
    public IgssFrontaliersFetcher igssFrontaliersFetcher(Clock clock) {
        return new IgssFrontaliersFetcher(http, clock);
    }
}
