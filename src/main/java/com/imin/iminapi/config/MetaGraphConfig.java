package com.imin.iminapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** RestClient for the Meta Graph API (CAPI events endpoint). */
@Configuration
public class MetaGraphConfig {

    @Value("${imin.meta.base-url:https://graph.facebook.com}")
    private String baseUrl;

    @Bean
    public RestClient metaGraphRestClient() {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
