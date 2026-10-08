package com.imin.iminapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient for the Meta Graph API (CAPI events endpoint).
 *
 * <p><b>The timeouts are not decoration.</b> {@code RestClient.builder()} is the
 * static builder, so it bypasses the auto-configured {@code RestClient.Builder} and
 * Boot's {@code spring.http.client} settings never reach it — this client shipped
 * with no connect and no read timeout at all. It is consumed by
 * {@code MetaGraphClient} from {@code MetaCapiPoller}'s {@code @Scheduled} sweep,
 * which shares four threads with every other job in this app, so one hung Meta
 * socket parks a quarter of the scheduler for good. Same shape, and the same
 * reason, as {@code OAuthConfig}, {@code PushConfig} and {@code GoogleWalletConfig}.
 */
@Configuration
public class MetaGraphConfig {

    @Value("${imin.meta.base-url:https://graph.facebook.com}")
    private String baseUrl;

    // Millis, not Duration: plain numbers bind without Boot's conversion service.
    @Value("${imin.meta.connect-timeout-millis:5000}")
    private long connectTimeoutMillis;

    @Value("${imin.meta.read-timeout-millis:10000}")
    private long readTimeoutMillis;

    @Bean
    public RestClient metaGraphRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults()
                                .withTimeouts(Duration.ofMillis(connectTimeoutMillis),
                                        Duration.ofMillis(readTimeoutMillis))))
                .build();
    }
}
