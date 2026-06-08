package com.imin.iminapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient for the native Ideogram V3 API (generate + remix). Authenticates with the
 * {@code Api-Key} header. Fails fast with a clear message when the key is missing so a
 * misconfigured deploy surfaces a config error rather than an opaque 401.
 */
@Configuration
public class IdeogramImageConfig {

    private static final Logger log = LoggerFactory.getLogger(IdeogramImageConfig.class);

    @Value("${ideogram.api-key:${IDEOGRAM_API_KEY:}}")
    private String apiKey;

    @Value("${ideogram.base-url:https://api.ideogram.ai}")
    private String baseUrl;

    @Bean
    public RestClient ideogramRestClient() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("IDEOGRAM_API_KEY is not set — poster rendering will fail with 401. "
                    + "Set the environment variable and restart the app.");
        }
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException(
                                "IDEOGRAM_API_KEY is not configured. Set the environment variable and "
                                + "restart the app before generating posters.");
                    }
                    request.getHeaders().set("Api-Key", apiKey);
                    return execution.execute(request, body);
                })
                .build();
    }
}
