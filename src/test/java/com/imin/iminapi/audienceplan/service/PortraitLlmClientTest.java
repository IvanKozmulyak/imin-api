package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.service.PortraitLlmClient.Citation;
import com.imin.iminapi.audienceplan.service.PortraitLlmClient.Reply;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The portrait client against a local server replaying recorded OpenRouter bodies; nothing leaves the machine. */
class PortraitLlmClientTest {

    /** Shape recorded from OpenRouter's web-search docs: url_citation annotations and usage with cost. */
    static final String RESEARCH_OK = """
            {"id":"gen-1","model":"anthropic/claude-haiku-4.5","choices":[{"finish_reason":"stop",
             "native_finish_reason":"end_turn","message":{"role":"assistant",
             "content":"Metz has an active techno scene around the BAM and Les Trinitaires.",
             "annotations":[
               {"type":"url_citation","url_citation":{"url":"https://www.bam-metz.fr/programme","title":"BAM Metz",
                "content":"...","start_index":1,"end_index":20}},
               {"type":"url_citation","url_citation":{"url":"https://trinitaires.fr/agenda/","title":"Les Trinitaires"}},
               {"type":"file","file":{"name":"x"}}]}}],
             "usage":{"prompt_tokens":1200,"completion_tokens":300,"total_tokens":1500,"cost":0.0321}}""";

    private HttpServer server;
    private final Deque<int[]> statuses = new ArrayDeque<>();
    private final Deque<String> bodies = new ArrayDeque<>();
    private final List<String> requests = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean stall;

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
            int status;
            String body;
            synchronized (bodies) {
                status = statuses.isEmpty() ? 200 : statuses.poll()[0];
                body = bodies.isEmpty() ? RESEARCH_OK : bodies.poll();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
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

    private PortraitLlmClient client(Duration timeout) {
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setPortraitTimeout(timeout);
        return new PortraitLlmClient("test-key-not-real", "http://127.0.0.1:" + server.getAddress().getPort() + "/api",
                props);
    }

    private void respond(int status, String body) {
        statuses.add(new int[] {status});
        bodies.add(body);
    }

    private static JsonNode json(String s) throws IOException {
        return new ObjectMapper().readTree(s);
    }

    @Test
    void research_sendsTheWebPlugin_privacyOptOut_andTheBaseModel() throws Exception {
        Reply r = client(Duration.ofSeconds(5)).research("anthropic/claude-haiku-4.5:online", "SYS", "USER", () -> true);

        JsonNode body = json(requests.get(0));
        assertThat(body.path("model").asText()).isEqualTo("anthropic/claude-haiku-4.5");
        assertThat(body.path("plugins").size()).isEqualTo(1);
        assertThat(body.path("plugins").get(0).path("id").asText()).isEqualTo("web");
        assertThat(body.path("plugins").get(0).path("max_results").asInt()).isEqualTo(5);
        assertThat(body.path("provider").path("data_collection").asText()).isEqualTo("deny");
        assertThat(body.has("response_format")).isFalse();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("SYS");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("USER");
        assertThat(auth.get(0)).isEqualTo("Bearer test-key-not-real /api/v1/chat/completions");

        assertThat(r.usable()).isTrue();
        assertThat(r.text()).startsWith("Metz has an active techno scene");
        assertThat(r.citations()).containsExactly(
                new Citation("https://www.bam-metz.fr/programme", "BAM Metz"),
                new Citation("https://trinitaires.fr/agenda/", "Les Trinitaires"));
        assertThat(r.tokensIn()).isEqualTo(1200);
        assertThat(r.tokensOut()).isEqualTo(300);
        assertThat(r.reportedCostUsd()).isEqualByComparingTo(new BigDecimal("0.0321"));
        assertThat(r.model()).isEqualTo("anthropic/claude-haiku-4.5");
    }

    @Test
    void extract_hasNoPlugin_andAsksForJson() throws Exception {
        respond(200, """
                {"model":"anthropic/claude-haiku-4.5","choices":[{"finish_reason":"stop",
                 "message":{"content":"{\\"groups\\":[]}"}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""");

        Reply r = client(Duration.ofSeconds(5)).extract("anthropic/claude-haiku-4.5", "SYS", "USER", () -> true);

        JsonNode body = json(requests.get(0));
        assertThat(body.has("plugins")).isFalse();
        assertThat(body.has("tools")).isFalse();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(r.usable()).isTrue();
        assertThat(r.citations()).isEmpty();
        assertThat(r.reportedCostUsd()).isNull();
    }

    @Test
    void refusalFilterPauseEmptyAndErrorBodies_areNotUsable() {
        String[] recorded = {
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"x\",\"refusal\":\"I can't.\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"x\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"native_finish_reason\":\"pause_turn\",\"message\":{\"content\":\"x\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"x\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"   \"}}]}",
                "{\"choices\":[]}",
                "{\"error\":{\"code\":502,\"message\":\"upstream\"}}",
                "not json",
        };
        for (String body : recorded) {
            assertThat(PortraitLlmClient.parse(body).usable()).as(body).isFalse();
        }
    }

    @Test
    void timeout_isNotRetried() {
        stall = true;

        assertThatThrownBy(() -> client(Duration.ofMillis(300)).research("m", "s", "u", () -> true))
                .isInstanceOf(RuntimeException.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void serverError_isRetriedOnce() {
        respond(502, "{\"error\":\"upstream\"}");

        Reply r = client(Duration.ofSeconds(5)).research("m", "s", "u", () -> true);

        assertThat(requests).hasSize(2);
        assertThat(r.usable()).isTrue();
    }

    @Test
    void serverError_isNotRetried_whenTheCallerRefuses() {
        respond(502, "{\"error\":\"upstream\"}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).research("m", "s", "u", () -> false))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void twoServerErrors_throw_afterOneRetry() {
        respond(502, "{}");
        respond(503, "{}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).research("m", "s", "u", () -> true))
                .isInstanceOf(RuntimeException.class);
        assertThat(requests).hasSize(2);
    }

    @Test
    void clientError_isNotRetried() {
        respond(400, "{\"error\":\"bad\"}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).research("m", "s", "u", () -> true))
                .isInstanceOf(HttpClientErrorException.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void missingKey_failsBeforeAnyRequest() {
        PortraitLlmClient noKey = new PortraitLlmClient("", "http://127.0.0.1:" + server.getAddress().getPort(),
                new AudiencePlanProperties());

        assertThatThrownBy(() -> noKey.research("m", "s", "u", () -> true)).hasMessageContaining("OPENROUTER_API_KEY");
        assertThat(requests).isEmpty();
    }
}
