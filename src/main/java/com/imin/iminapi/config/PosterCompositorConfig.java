package com.imin.iminapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient for the imin-public poster text-compositor route (POST {base-url}/api/poster).
 * Used by {@link com.imin.iminapi.service.poster.PosterTextCompositorClient} to composite the
 * real-font text layer over generated background art. No auth — the render route is unauthenticated
 * (add a shared secret here if it is locked down later). Inert unless poster.compositor.enabled.
 */
@Configuration
public class PosterCompositorConfig {

    @Value("${poster.compositor.base-url:}")
    private String baseUrl;

    @Bean
    public RestClient posterCompositorRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl == null ? "" : baseUrl)
                .build();
    }
}
