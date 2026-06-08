package com.imin.iminapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient for the Recraft image-generation API, retained for per-vibe style
 * training. A single bearer-auth interceptor fails fast with a clear message when
 * the key is missing, so a misconfigured deploy surfaces a config error rather than a 401.
 */
@Configuration
public class RecraftImageConfig {

    private static final Logger log = LoggerFactory.getLogger(RecraftImageConfig.class);

    @Value("${recraft.api-key:${RECRAFT_API_KEY:}}")
    private String apiKey;

    @Value("${recraft.base-url:https://external.api.recraft.ai/v1}")
    private String baseUrl;

    @Bean
    public RestClient recraftRestClient() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("RECRAFT_API_KEY is not set — Recraft image generation and style training "
                    + "will fail with 401. Required for default poster generation and when "
                    + "training a vibe style via POST /api/v1/ai/vibes/{vibeId}/train-style.");
        }
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException(
                                "RECRAFT_API_KEY is not configured. "
                                + "Set the environment variable and restart the app before generating posters with Recraft.");
                    }
                    request.getHeaders().setBearerAuth(apiKey);
                    return execution.execute(request, body);
                })
                .build();
    }
}
