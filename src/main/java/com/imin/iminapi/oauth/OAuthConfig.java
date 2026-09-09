package com.imin.iminapi.oauth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Wires up the OAuth social-sign-in properties and the {@link RestClient} used
 * for provider token exchange (Google + Apple {@code /token} endpoints). The
 * client carries no base URL — the services pass absolute provider URLs.
 *
 * <p><b>The timeouts are not decoration.</b> {@code RestClient.builder()} is the
 * static builder, so it bypasses the auto-configured {@code RestClient.Builder}
 * and Boot's {@code spring.http.client} settings never reach it — the client
 * shipped with no connect and no read timeout at all, on the one call a sign-in
 * blocks on. Same shape as {@code GoogleWalletConfig}.
 */
@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class OAuthConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public RestClient oauthRestClient() {
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults()
                                .withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)))
                .build();
    }
}
