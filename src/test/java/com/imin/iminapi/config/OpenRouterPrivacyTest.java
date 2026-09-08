package com.imin.iminapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenRouter provider opt-out.
 *
 * <p>OpenRouter routes to whichever upstream is cheapest, and some of those
 * train on what they receive. The vision gate uploads a finished poster, which
 * in DJ mode carries a real person's likeness — so every request must say
 * {@code provider.data_collection = deny}. It is a body field with no header
 * form, which is why the Spring AI path has to inject it in the transport.
 */
class OpenRouterPrivacyTest {

    final ObjectMapper om = new ObjectMapper();

    @Test
    void policy_denies_data_collection() {
        assertThat(OpenRouterPrivacy.providerPolicy()).containsEntry("data_collection", "deny");
    }

    /** A fresh map per call — a shared constant would be mutable shared state. */
    @Test
    void policy_is_a_fresh_map_each_call() {
        var first = OpenRouterPrivacy.providerPolicy();
        first.put("order", java.util.List.of("someone"));
        assertThat(OpenRouterPrivacy.providerPolicy()).doesNotContainKey("order");
    }

    @Test
    void interceptor_adds_the_policy_to_a_chat_completion_body() throws Exception {
        String sent = intercept("{\"model\":\"openai/gpt-4o-mini\",\"messages\":[]}");

        JsonNode body = om.readTree(sent);
        assertThat(body.at("/provider/data_collection").asText()).isEqualTo("deny");
        assertThat(body.at("/model").asText()).isEqualTo("openai/gpt-4o-mini");
    }

    /** A deliberate caller-set policy is never overwritten. */
    @Test
    void interceptor_leaves_an_existing_provider_field_alone() throws Exception {
        String sent = intercept("{\"model\":\"m\",\"provider\":{\"order\":[\"anthropic\"]}}");

        JsonNode body = om.readTree(sent);
        assertThat(body.at("/provider/order/0").asText()).isEqualTo("anthropic");
        assertThat(body.at("/provider/data_collection").isMissingNode()).isTrue();
    }

    /** Fail open: a body we cannot parse goes out untouched rather than corrupted. */
    @Test
    void interceptor_passes_through_a_non_json_body() throws Exception {
        assertThat(intercept("not json at all")).isEqualTo("not json at all");
    }

    @Test
    void interceptor_passes_through_an_empty_body() throws Exception {
        assertThat(intercept("")).isEmpty();
    }

    /** Content-Length must follow the body it describes. */
    @Test
    void interceptor_updates_content_length() throws Exception {
        String json = "{\"model\":\"m\"}";
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://openrouter.ai/api/v1/chat/completions"));
        request.getHeaders().setContentLength(json.getBytes(StandardCharsets.UTF_8).length);
        AtomicReference<byte[]> captured = new AtomicReference<>();

        OpenRouterPrivacy.bodyInjectingInterceptor()
                .intercept(request, json.getBytes(StandardCharsets.UTF_8), capturing(captured));

        assertThat(request.getHeaders().getContentLength()).isEqualTo(captured.get().length);
    }

    private String intercept(String json) throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://openrouter.ai/api/v1/chat/completions"));
        AtomicReference<byte[]> captured = new AtomicReference<>();
        OpenRouterPrivacy.bodyInjectingInterceptor()
                .intercept(request, json.getBytes(StandardCharsets.UTF_8), capturing(captured));
        return new String(captured.get(), StandardCharsets.UTF_8);
    }

    private static ClientHttpRequestExecution capturing(AtomicReference<byte[]> sink) {
        return (request, body) -> {
            sink.set(body);
            return response();
        };
    }

    private static ClientHttpResponse response() {
        return new MockClientHttpResponse(new byte[0], 200);
    }
}
