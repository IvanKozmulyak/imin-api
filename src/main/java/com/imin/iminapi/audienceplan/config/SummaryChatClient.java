package com.imin.iminapi.audienceplan.config;

import com.imin.iminapi.config.OpenRouterConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The OpenRouter client for plan summaries: each answer is bounded by {@code summary-timeout}, and a timeout is
 * not retried, so a stalled provider holds the single summary thread for one timeout at most.
 */
@Configuration
public class SummaryChatClient {

    public static final String NAME = "audiencePlanSummaryChatClient";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean(name = NAME)
    public ChatClient audiencePlanSummaryChatClient(@Value("${openrouter.api-key}") String apiKey,
                                                    @Value("${openrouter.base-url}") String baseUrl,
                                                    @Value("${openrouter.model}") String model,
                                                    @Value("${openrouter.temperature:0.6}") Double temperature,
                                                    AudiencePlanProperties props) {
        return OpenRouterConfig.chatClient(baseUrl, apiKey, model, temperature, http(props.getSummaryTimeout()),
                retry());
    }

    /** A RestClient whose response wait is capped at {@code readTimeout}. */
    public static RestClient.Builder http(Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(readTimeout.compareTo(CONNECT_TIMEOUT) < 0 ? readTimeout : CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }

    /** One quick retry on a provider 5xx; timeouts and 4xx go straight to the template. */
    public static RetryTemplate retry() {
        return new RetryTemplate(RetryPolicy.builder()
                .maxRetries(1)
                .includes(TransientAiException.class)
                .delay(Duration.ofSeconds(1))
                .build());
    }
}
