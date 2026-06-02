package com.imin.iminapi.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class OpenRouterConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterConfig.class);

    @Value("${openrouter.api-key}")
    private String apiKey;

    @Value("${openrouter.base-url}")
    private String baseUrl;

    @Value("${openrouter.model}")
    private String model;

    @Value("${openrouter.temperature:0.6}")
    private Double temperature;

    @Bean
    @Primary
    public ChatClient openRouterChatClient() {
        String normalizedBaseUrl = normalizeOpenRouterBaseUrl(baseUrl);
        log.info("Configuring OpenRouter ChatClient with baseUrl={}, model={}, temperature={}",
                normalizedBaseUrl, model, temperature);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(normalizedBaseUrl)
                .apiKey(apiKey)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions(model, temperature))
                .build();
        return ChatClient.builder(chatModel).build();
    }

    /**
     * Build the default chat options. A lower-than-default temperature keeps poster-concept
     * JSON stable and the art direction coherent (the model otherwise defaults near 1.0,
     * which produces noisy, inconsistent prompts).
     */
    static OpenAiChatOptions chatOptions(String model, Double temperature) {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder().model(model);
        if (temperature != null) {
            b.temperature(temperature);
        }
        return b.build();
    }

    static String normalizeOpenRouterBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        // Spring AI OpenAI client appends /v1 internally for chat completions.
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }
}
