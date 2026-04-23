package com.imin.iminapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ReplicateImageConfig {

    private static final Logger log = LoggerFactory.getLogger(ReplicateImageConfig.class);

    @Value("${replicate.api-token:}")
    private String apiToken;

    @Bean
    public RestClient replicateRestClient() {
        if (apiToken == null || apiToken.isBlank()) {
            log.warn("REPLICATE_API_TOKEN is not set — poster generation will fail with 401. "
                    + "Set the env var (r8_...) before starting the app.");
        } else {
            log.info("Replicate auth configured (token length={})", apiToken.length());
        }
        return RestClient.builder()
                .baseUrl("https://api.replicate.com")
                .requestInterceptor((request, body, execution) -> {
                    if (apiToken == null || apiToken.isBlank()) {
                        throw new IllegalStateException(
                                "REPLICATE_API_TOKEN is not configured. "
                                + "Set the environment variable (token starts with r8_) and restart the app.");
                    }
                    request.getHeaders().setBearerAuth(apiToken);
                    return execution.execute(request, body);
                })
                .build();
    }
}
