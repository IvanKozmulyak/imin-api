package com.imin.iminapi.predictor.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audienceplan.config.SummaryChatClient;
import com.imin.iminapi.config.OpenRouterPrivacy;
import com.imin.iminapi.config.OpenRouterRestClients;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The date-check research call: one OpenRouter request with the web plugin on the Exa engine, temperature 0, the
 * data-collection opt-out and a JSON answer. Exa returns each result's excerpt in its {@code url_citation}, which the
 * validator checks quotes against. No retry: a timeout, 4xx or 5xx reaches the caller as an exception.
 */
@Component
public class ResearchLlmClient {

    static final String ENGINE = "exa";
    static final int MAX_RESULTS = 5;
    static final int MAX_TOKENS = 2000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A search result the answer cited, with the page excerpt the search returned. */
    public record Citation(String url, String title, String content) {}

    /**
     * Tokens, searches and USD cost as OpenRouter reported them; {@code costUsd} is null when it reported none.
     * {@code searches} is the reported web search count, else 1, the one Exa search the plugin runs per request.
     */
    public record Usage(int tokensIn, int tokensOut, int searches, BigDecimal costUsd) {}

    /** {@code usable} is false on a refusal, a cut-off or paused turn, an error body or empty text. */
    public record Reply(String text, boolean usable, String finishReason, List<Citation> citations, Usage usage) {}

    private final RestClient http;

    public ResearchLlmClient(@Value("${openrouter.api-key}") String apiKey,
                             @Value("${openrouter.base-url}") String baseUrl, DateCheckProperties props) {
        this.http = SummaryChatClient.http(props.getResearchTimeout())
                .baseUrl(OpenRouterRestClients.v1BaseUrl(baseUrl))
                .requestInterceptor((request, body, execution) -> {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException("OPENROUTER_API_KEY is not configured. Set it before "
                                + "running date-check web research.");
                    }
                    request.getHeaders().setBearerAuth(apiKey);
                    return execution.execute(request, body);
                })
                .build();
    }

    public Reply research(String model, String system, String user) {
        String raw = http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .body(body(model, system, user)).retrieve().body(String.class);
        return parse(raw);
    }

    static Map<String, Object> body(String model, String system, String user) {
        Map<String, Object> web = new LinkedHashMap<>();
        web.put("id", "web");
        web.put("engine", ENGINE);
        web.put("max_results", MAX_RESULTS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put(OpenRouterPrivacy.PROVIDER_FIELD, OpenRouterPrivacy.providerPolicy());
        body.put("temperature", 0);
        body.put("max_tokens", MAX_TOKENS);
        body.put("plugins", List.of(web));
        body.put("usage", Map.of("include", true));
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        return body;
    }

    static Reply parse(String raw) {
        JsonNode root;
        try {
            root = raw == null ? null : JSON.readTree(raw);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !root.path("choices").isArray() || root.path("choices").isEmpty()) {
            return new Reply(null, false, null, List.of(), null);
        }
        JsonNode choice = root.path("choices").get(0);
        JsonNode message = choice.path("message");
        String text = message.path("content").isTextual() ? message.path("content").asText() : null;
        String finish = text(choice, "finish_reason");
        boolean refused = message.hasNonNull("refusal") && !message.path("refusal").asText().isBlank();
        boolean usable = !refused && "stop".equals(lower(finish)) && !stopped(text(choice, "native_finish_reason"))
                && text != null && !text.isBlank();

        List<Citation> citations = new ArrayList<>();
        for (JsonNode a : message.path("annotations")) {
            if (!"url_citation".equals(a.path("type").asText())) continue;
            JsonNode c = a.path("url_citation");
            String url = text(c, "url");
            if (url != null && !url.isBlank()) citations.add(new Citation(url, text(c, "title"), text(c, "content")));
        }
        JsonNode u = root.path("usage");
        Usage usage = null;
        if (u.isObject()) {
            JsonNode reported = u.path("server_tool_use").path("web_search_requests");
            usage = new Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0),
                    reported.isInt() ? reported.asInt() : 1,
                    u.path("cost").isNumber() ? u.path("cost").decimalValue() : null);
        }
        return new Reply(text, usable, finish, List.copyOf(citations), usage);
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
