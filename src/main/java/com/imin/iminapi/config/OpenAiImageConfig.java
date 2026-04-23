package com.imin.iminapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OpenAiImageConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageConfig.class);

    @Value("${openai.image.api-key:${OPENAI_API_KEY:}}")
    private String apiKey;

    @Value("${openai.image.base-url:https://api.openai.com}")
    private String baseUrl;

    @Bean
    public RestClient openAiImageRestClient() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OPENAI_API_KEY is not set — OpenAI image generation will fail with 401. "
                    + "Required only when imageProvider=OPENAI on /api/v1/events/ai-create.");
        }
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException(
                                "OPENAI_API_KEY is not configured. "
                                + "Set the environment variable and restart the app before using imageProvider=OPENAI.");
                    }
                    request.getHeaders().setBearerAuth(apiKey);
                    return execution.execute(request, body);
                })
                .build();
    }
}
