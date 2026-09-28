package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.SummaryChatClient;
import com.imin.iminapi.config.OpenRouterPrivacy;
import com.imin.iminapi.config.OpenRouterRestClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The two OpenRouter calls behind a portrait, over a raw client so the web plugin, its {@code url_citation}
 * annotations and the reported cost are all visible. One retry on a 5xx when the caller allows it; a timeout or
 * 4xx is never retried.
 */
@Component
public class PortraitLlmClient {

    /** OpenRouter's web search: the {@code web} plugin, equal to the {@code :online} model suffix. */
    static final String ONLINE_SUFFIX = ":online";
    static final int MAX_RESULTS = 5;
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 1500;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A page the web search returned and the answer cited. */
    public record Citation(String url, String title) {}

    /**
     * One answer. {@code usable} is false on a refusal, a content filter, a paused or cut-off turn, an error body or
     * empty text.
     */
    public record Reply(String text, boolean usable, String finishReason, List<Citation> citations, int tokensIn,
                        int tokensOut, BigDecimal reportedCostUsd, String model) {}

    private final RestClient http;

    public PortraitLlmClient(@Value("${openrouter.api-key}") String apiKey,
                             @Value("${openrouter.base-url}") String baseUrl, AudiencePlanProperties props) {
        this.http = SummaryChatClient.http(props.getPortraitTimeout())
                .baseUrl(OpenRouterRestClients.v1BaseUrl(baseUrl))
                .requestInterceptor((request, body, execution) -> {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException("OPENROUTER_API_KEY is not configured. Set it before "
                                + "researching audience portraits.");
                    }
                    request.getHeaders().setBearerAuth(apiKey);
                    return execution.execute(request, body);
                })
                .build();
    }

    /** The model id without the {@code :online} suffix; search is switched on by the plugin instead. */
    static String baseModel(String model) {
        return model.endsWith(ONLINE_SUFFIX) ? model.substring(0, model.length() - ONLINE_SUFFIX.length()) : model;
    }

    /** Free text grounded in a web search; the pages it cites come back as citations. */
    public Reply research(String model, String system, String user, BooleanSupplier mayRetry) {
        Map<String, Object> web = new LinkedHashMap<>();
        web.put("id", "web");
        web.put("max_results", MAX_RESULTS);
        return call(body(model, system, user, List.of(web), false), mayRetry);
    }

    /** Structured JSON from the given text, with no plugin and no tools. */
    public Reply extract(String model, String system, String user, BooleanSupplier mayRetry) {
        return call(body(model, system, user, List.of(), true), mayRetry);
    }

    static Map<String, Object> body(String model, String system, String user, List<Map<String, Object>> plugins,
                                    boolean json) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", baseModel(model));
        body.put(OpenRouterPrivacy.PROVIDER_FIELD, OpenRouterPrivacy.providerPolicy());
        body.put("temperature", TEMPERATURE);
        body.put("max_tokens", MAX_TOKENS);
        if (!plugins.isEmpty()) body.put("plugins", plugins);
        if (json) body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        return body;
    }

    /** {@code mayRetry} is asked before the one 5xx retry, so the caller can count it against its caps. */
    private Reply call(Map<String, Object> body, BooleanSupplier mayRetry) {
        String raw;
        try {
            raw = post(body);
        } catch (HttpServerErrorException e) {
            if (!mayRetry.getAsBoolean()) throw e;
            raw = post(body);
        }
        return parse(raw);
    }

    private String post(Map<String, Object> body) {
        return http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(String.class);
    }

    static Reply parse(String raw) {
        JsonNode root;
        try {
            root = raw == null ? null : JSON.readTree(raw);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !root.path("choices").isArray() || root.path("choices").isEmpty()) {
            return new Reply(null, false, null, List.of(), 0, 0, null, null);
        }
        JsonNode choice = root.path("choices").get(0);
        JsonNode message = choice.path("message");
        String text = message.path("content").isTextual() ? message.path("content").asText() : null;
        String finish = text(choice, "finish_reason");
        String nativeFinish = text(choice, "native_finish_reason");
        boolean refused = message.hasNonNull("refusal") && !message.path("refusal").asText().isBlank();
        boolean usable = !refused && "stop".equals(lower(finish)) && !stopped(nativeFinish)
                && text != null && !text.isBlank();

        List<Citation> citations = new ArrayList<>();
        for (JsonNode a : message.path("annotations")) {
            if (!"url_citation".equals(a.path("type").asText())) continue;
            JsonNode c = a.path("url_citation");
            String url = text(c, "url");
            if (url != null && !url.isBlank()) citations.add(new Citation(url, text(c, "title")));
        }
        JsonNode usage = root.path("usage");
        BigDecimal cost = usage.path("cost").isNumber() ? usage.path("cost").decimalValue() : null;
        return new Reply(text, usable, finish, List.copyOf(citations), usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0), cost, text(root, "model"));
    }

    /** Anthropic's own stop reasons that OpenRouter can report next to a plain "stop". */
    private static boolean stopped(String nativeFinish) {
        String n = lower(nativeFinish);
        return "pause_turn".equals(n) || "refusal".equals(n) || "max_tokens".equals(n);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
