package com.imin.iminapi.marketing.config;

import com.imin.iminapi.audienceplan.config.SummaryChatClient;
import com.imin.iminapi.config.OpenRouterConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenRouter client for marketing copy (compose and subject variants, email templates, Momentum drafts):
 * each answer waits at most {@code imin.marketing.llm-timeout}, one retry on a provider 5xx, none on a timeout.
 */
@Configuration("marketingChatClientConfig")   // the class's default bean name is the client's
public class MarketingChatClient {

    public static final String NAME = "marketingChatClient";
    private static final String DEFAULT_TIMEOUT = "45s";

    @Bean(name = NAME)
    public ChatClient marketingChatClient(@Value("${openrouter.api-key}") String apiKey,
                                          @Value("${openrouter.base-url}") String baseUrl,
                                          @Value("${openrouter.model}") String model,
                                          @Value("${openrouter.temperature:0.6}") Double temperature,
                                          @Value("${imin.marketing.llm-timeout:45s}") String timeout) {
        // A blank variable binds the default rather than failing startup.
        String t = timeout == null || timeout.isBlank() ? DEFAULT_TIMEOUT : timeout.trim();
        return OpenRouterConfig.chatClient(baseUrl, apiKey, model, temperature,
                SummaryChatClient.http(DurationStyle.detectAndParse(t)), SummaryChatClient.retry());
    }
}
