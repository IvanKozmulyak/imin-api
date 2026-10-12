package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * One hung Ideogram socket used to hold a poster variant thread, and the organizer's request,
 * forever: the static {@code RestClient.builder()} never sees Boot's HTTP client settings. Driven
 * against a local socket that accepts and never answers, with the read timeout set short through
 * its property, so the bound below also proves the property binds.
 */
class IdeogramImageConfigTest {

    @Test
    void the_ideogram_client_gives_up_on_a_socket_that_never_answers() throws Exception {
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

            new ApplicationContextRunner()
                    .withUserConfiguration(IdeogramImageConfig.class)
                    .withPropertyValues("ideogram.base-url=http://127.0.0.1:" + server.getLocalPort(),
                            "ideogram.api-key=test-key",
                            "ideogram.read-timeout-millis=300")
                    .run(ctx -> {
                        RestClient client = ctx.getBean("ideogramRestClient", RestClient.class);
                        // Without the read timeout applied this call outlives the preemptive bound.
                        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                                assertThatThrownBy(() -> client.get().uri("/v1/hang").retrieve().body(String.class))
                                        .isInstanceOf(ResourceAccessException.class));
                    });
        }
    }
}
