package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@code metaGraphRestClient} is consumed by {@code MetaGraphClient} on
 * {@code MetaCapiPoller}'s {@code @Scheduled} sweep, which shares a pool of four
 * threads with every other job in this app. {@code RestClient.builder()} is the
 * static builder, so Boot's {@code spring.http.client} settings never reach it and
 * the client shipped with no connect and no read timeout at all — one hung Meta
 * socket parks a quarter of the scheduler permanently. Same defect, and the same
 * fix, as {@code OAuthConfig}, {@code PushConfig} and {@code GoogleWalletConfig}.
 *
 * <p>Driven against a local socket that accepts and never answers, because that is
 * the failure mode: not a refused connection, a conversation that never ends.
 */
class MetaGraphConfigTest {

    @Test
    void the_graph_client_gives_up_on_a_socket_that_never_answers() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread blackHole = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    Thread.sleep(60_000L);
                } catch (Exception ignored) {
                    // Test over; the socket goes with it.
                }
            });
            blackHole.setDaemon(true);
            blackHole.start();

            MetaGraphConfig config = new MetaGraphConfig();
            ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + server.getLocalPort());
            RestClient client = config.metaGraphRestClient();

            // Without a read timeout this call never returns and the preemptive
            // timeout is what fails the test.
            assertTimeoutPreemptively(Duration.ofSeconds(25), () ->
                    assertThatThrownBy(() -> client.get().uri("/v25.0/hang").retrieve().body(String.class))
                            .isInstanceOf(ResourceAccessException.class));
        }
    }
}
