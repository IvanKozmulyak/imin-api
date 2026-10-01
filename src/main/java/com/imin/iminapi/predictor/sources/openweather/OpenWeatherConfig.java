package com.imin.iminapi.predictor.sources.openweather;

import com.imin.iminapi.predictor.config.PredictorProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/**
 * OpenWeather source. The HTTP client is private to it (not a bean), with explicit timeouts:
 * the static {@code RestClient.builder()} ignores Boot's client settings.
 */
@Configuration
@EnableConfigurationProperties(OpenWeatherProperties.class)
public class OpenWeatherConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    // Free plan allows 60 calls a minute; one a second per JVM stays under it.
    static final long MIN_INTERVAL_MILLIS = 1000;
    static final long MAX_WAIT_MILLIS = 2000;

    /** The flag without a key, or a non-https base, fails startup; messages name variables, never values. */
    @Bean
    public OpenWeatherClient openWeatherClient(PredictorProperties predictor, OpenWeatherProperties props, Clock clock) {
        if (predictor.isWeatherEnabled() && props.getApiKey().isEmpty()) {
            throw new IllegalStateException("PREDICTOR_WEATHER_ENABLED is true but OPENWEATHER_API_KEY is blank");
        }
        if (!props.getBaseUrl().startsWith("https://")) {
            throw new IllegalStateException("PREDICTOR_OPENWEATHER_BASE_URL must be an https URL");
        }
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)));
        return new OpenWeatherClient(builder, props, clock, MIN_INTERVAL_MILLIS, MAX_WAIT_MILLIS);
    }
}
