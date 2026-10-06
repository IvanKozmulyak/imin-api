package com.imin.iminapi.predictor.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.research.ResearchLlmClient.Citation;
import com.imin.iminapi.predictor.research.ResearchLlmClient.Reply;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The research client against a local server replaying a recorded Exa-plugin answer; nothing leaves the machine. */
class ResearchLlmClientTest {

    /** Shape recorded in the provider spike: url_citation annotations carrying the page excerpt, usage with cost. */
    static final String EXA_OK = """
            {"id":"gen-1","model":"anthropic/claude-haiku-4.5","choices":[{"finish_reason":"stop",
             "native_finish_reason":"end_turn","message":{"role":"assistant",
             "content":"{\\"findings\\":[]}",
             "annotations":[
               {"type":"url_citation","url_citation":{"url":"https://www.infoconcert.com/a.html","title":"Rex Club",
                "content":"Amelie Lens au Rex Club le samedi 17 octobre 2026","start_index":0,"end_index":0}},
               {"type":"url_citation","url_citation":{"url":"https://www.zenith.fr/b","title":"Zenith"}},
               {"type":"file","file":{"name":"x"}}]}}],
             "usage":{"prompt_tokens":4300,"completion_tokens":250,"total_tokens":4550,"cost":0.0125}}""";

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean stall;
    private volatile String response = EXA_OK;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            synchronized (requests) {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                auth.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + exchange.getRequestURI());
            }
            if (stall) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
    }

    private ResearchLlmClient client(Duration timeout) {
        DateCheckProperties props = new DateCheckProperties();
        props.setResearchTimeout(timeout);
        return new ResearchLlmClient("sk-test", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                props);
    }

    @Test
    void requestUsesTheExaPluginTemperatureZeroAndTheDataCollectionOptOut() throws Exception {
        client(Duration.ofSeconds(5)).research("anthropic/claude-haiku-4.5", "sys", "usr");

        JsonNode body = new ObjectMapper().readTree(requests.get(0));
        assertThat(auth.get(0)).isEqualTo("Bearer sk-test /api/v1/chat/completions");
        assertThat(body.path("model").asText()).isEqualTo("anthropic/claude-haiku-4.5");
        assertThat(body.path("temperature").asInt()).isZero();
        assertThat(body.path("provider").path("data_collection").asText()).isEqualTo("deny");
        assertThat(body.path("plugins")).hasSize(1);
        assertThat(body.path("plugins").get(0).path("id").asText()).isEqualTo("web");
        assertThat(body.path("plugins").get(0).path("engine").asText()).isEqualTo("exa");
        assertThat(body.path("plugins").get(0).path("max_results").asInt()).isEqualTo(5);
        assertThat(body.path("usage").path("include").asBoolean()).isTrue();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("sys");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("usr");
    }

    @Test
    void parsesCitationsWithExcerptsUsageAndCost() {
        Reply r = client(Duration.ofSeconds(5)).research("m", "s", "u");

        assertThat(r.usable()).isTrue();
        assertThat(r.text()).isEqualTo("{\"findings\":[]}");
        assertThat(r.citations()).containsExactly(
                new Citation("https://www.infoconcert.com/a.html", "Rex Club",
                        "Amelie Lens au Rex Club le samedi 17 octobre 2026"),
                new Citation("https://www.zenith.fr/b", "Zenith", null));
        assertThat(r.usage().tokensIn()).isEqualTo(4300);
        assertThat(r.usage().tokensOut()).isEqualTo(250);
        assertThat(r.usage().searches()).isEqualTo(1);
        assertThat(r.usage().costUsd()).isEqualByComparingTo(new BigDecimal("0.0125"));

        response = EXA_OK.replace("\"cost\":0.0125", "\"server_tool_use\":{\"web_search_requests\":3}")
                .replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\"");
        Reply cut = client(Duration.ofSeconds(5)).research("m", "s", "u");
        assertThat(cut.usable()).isFalse();
        assertThat(cut.usage().searches()).isEqualTo(3);
        assertThat(cut.usage().costUsd()).isNull();
        assertThat(ResearchLlmClient.parse("not json").usable()).isFalse();
    }

    @Test
    void recordedAnswerIsUsable() {
        // The baseline each branch below changes one field of.
        assertThat(ResearchLlmClient.parse(EXA_OK).usable()).isTrue();
    }

    @Test
    void refusalMessageIsUnusable() {
        Reply r = ResearchLlmClient.parse(EXA_OK.replace("\"role\":\"assistant\",",
                "\"role\":\"assistant\",\"refusal\":\"I can't help with that.\","));
        assertThat(r.usable()).isFalse();
        assertThat(r.finishReason()).isEqualTo("stop");
        // A blank refusal field is not a refusal.
        assertThat(ResearchLlmClient.parse(EXA_OK.replace("\"role\":\"assistant\",",
                "\"role\":\"assistant\",\"refusal\":\" \",")).usable()).isTrue();
    }

    @Test
    void nativePauseTurnIsUnusable() {
        assertThat(ResearchLlmClient.parse(nativeFinish("pause_turn")).usable()).isFalse();
    }

    @Test
    void nativeRefusalIsUnusable() {
        assertThat(ResearchLlmClient.parse(nativeFinish("refusal")).usable()).isFalse();
    }

    @Test
    void nativeMaxTokensIsUnusable() {
        assertThat(ResearchLlmClient.parse(nativeFinish("MAX_TOKENS")).usable()).isFalse();
    }

    @Test
    void searchesFallBackToOneWithoutServerToolUse() {
        assertThat(EXA_OK).doesNotContain("server_tool_use");
        assertThat(ResearchLlmClient.parse(EXA_OK).usage().searches()).isEqualTo(1);
        assertThat(ResearchLlmClient.parse(EXA_OK.replace("\"cost\":0.0125",
                "\"cost\":0.0125,\"server_tool_use\":{\"web_search_requests\":2}")).usage().searches()).isEqualTo(2);
    }

    @Test
    void missingCostIsNull() {
        Reply r = ResearchLlmClient.parse(EXA_OK.replace(",\"cost\":0.0125", ""));
        assertThat(r.usage()).isNotNull();
        assertThat(r.usage().tokensIn()).isEqualTo(4300);
        assertThat(r.usage().costUsd()).isNull();
        assertThat(ResearchLlmClient.parse(EXA_OK.replace("\"cost\":0.0125", "\"cost\":\"0.0125\"")).usage()
                .costUsd()).isNull();
    }

    private static String nativeFinish(String reason) {
        return EXA_OK.replace("\"native_finish_reason\":\"end_turn\"", "\"native_finish_reason\":\"" + reason + "\"");
    }

    @Test
    void timeoutThrowsAndIsNotRetried() {
        stall = true;
        assertThatThrownBy(() -> client(Duration.ofMillis(300)).research("m", "s", "u"))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(requests).hasSize(1);
    }
}
