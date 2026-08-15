package com.imin.iminapi.push;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Binds {@code imin.push.*} and builds the one {@link RestClient} the Expo
 * sender uses.
 *
 * <p>{@code @EnableConfigurationProperties} is mandatory here:
 * {@code IminApiApplication} is a bare {@code @SpringBootApplication} with no
 * {@code @ConfigurationPropertiesScan}, so {@link PushProperties} would never
 * bind on its own — same reason {@code OAuthConfig} carries it.
 */
@Configuration
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {

    /**
     * The Expo client. Named, because this project has several {@code RestClient}
     * beans and injection by type alone is ambiguous.
     */
    @Bean
    public RestClient expoRestClient(PushProperties props) {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(httpClientSettings(props)))
                .build();
    }

    /**
     * Connect and read timeouts, from {@code imin.push.timeout-seconds}.
     *
     * <p><b>Not decorative.</b> The Expo POST runs inline on
     * {@code NotifyReleaseSender.sweep()}, a {@code @Scheduled} method on
     * Spring's default scheduler — which is <b>pool size 1</b>, because
     * {@code spring.task.scheduling.pool.size} is set nowhere in this repo. A
     * hung connection to {@code exp.host} would stall every other
     * {@code @Scheduled} job in the application, including
     * {@code ReservationSweeper} (the thing that releases stale inventory
     * holds), and would outlive the ShedLock {@code lockAtMostFor}, letting a
     * second replica re-enter the sweep. There is no global HTTP timeout default
     * to fall back on: every other client in this project either sets its own or
     * has none.
     *
     * <p>Package-private so a test can assert the property actually reaches the
     * settings the request factory is built from — an unused
     * {@code getTimeoutSeconds()} is exactly the defect this guards.
     */
    static HttpClientSettings httpClientSettings(PushProperties props) {
        Duration timeout = Duration.ofSeconds(props.getTimeoutSeconds());
        return HttpClientSettings.defaults().withTimeouts(timeout, timeout);
    }
}
