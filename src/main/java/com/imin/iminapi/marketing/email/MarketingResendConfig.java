package com.imin.iminapi.marketing.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient for Resend's batch endpoint. resend-java 4.1.0 cannot send an Idempotency-Key or the batch-validation
 * header, so campaign batches POST directly. The static builder bypasses Boot's client settings, so the timeouts here
 * are the only ones: the call runs on a scheduler thread shared with every other job.
 */
@Configuration
public class MarketingResendConfig {

    public static final String BEAN = "resendBatchRestClient";

    @Value("${imin.marketing.resend-base-url:https://api.resend.com}")
    private String baseUrl;

    @Bean(BEAN)
    public RestClient resendBatchRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults()
                                .withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(30))))
                .build();
    }
}
