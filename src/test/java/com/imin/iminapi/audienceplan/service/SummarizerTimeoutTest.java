package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.SummaryChatClient;
import com.imin.iminapi.audienceplan.config.SummaryExecutor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The summary client against a local server that stalls or fails; nothing leaves the machine. */
class SummarizerTimeoutTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile int status;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                if (status == 0) release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"error\":\"upstream\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status == 0 ? 200 : status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
    }

    private final AudiencePlanProperties props = new AudiencePlanProperties();

    /** The production bean, pointed at the local server. */
    private ChatClient client(Duration timeout) {
        props.setSummaryTimeout(timeout);
        return new SummaryChatClient().audiencePlanSummaryChatClient("test-key-not-real",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "platform/default-model", 0.2, props);
    }

    /** Whether generate() produced model text (false = the template). */
    private boolean aiGenerated(ChatClient client) {
        Summarizer s = new Summarizer(client, new LlmPayloadGuard(), props, "platform/default-model",
                mock(JdbcTemplate.class), null, Runnable::run,
                Clock.fixed(Instant.parse("2026-09-26T09:00:00Z"), ZoneOffset.UTC));
        return s.generate(UUID.randomUUID(), SummaryFixtures.warm(), "en").summary().aiGenerated();
    }

    @Test
    void stalledProvider_timesOut_withoutRetry_theTemplateIsUsed_andTheExecutorIsFreed() throws Exception {
        ThreadPoolTaskExecutor exec = (ThreadPoolTaskExecutor) new SummaryExecutor().audiencePlanSummaryExecutor();
        try {
            ChatClient slow = client(Duration.ofMillis(300));
            CompletableFuture<Boolean> aiGenerated = new CompletableFuture<>();
            CountDownLatch next = new CountDownLatch(1);
            long start = System.nanoTime();

            exec.execute(() -> aiGenerated.complete(aiGenerated(slow)));
            exec.execute(next::countDown);

            assertThat(next.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            assertThat(aiGenerated.get(1, TimeUnit.SECONDS)).isFalse();
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            exec.shutdown();
        }
    }

    @Test
    void provider5xx_isRetriedOnce_thenTheTemplateIsUsed() {
        status = 503;

        boolean aiGenerated = aiGenerated(client(Duration.ofSeconds(5)));

        assertThat(aiGenerated).isFalse();
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void provider4xx_isNotRetried() {
        status = 400;

        boolean aiGenerated = aiGenerated(client(Duration.ofSeconds(5)));

        assertThat(aiGenerated).isFalse();
        assertThat(requests.get()).isEqualTo(1);
    }
}
