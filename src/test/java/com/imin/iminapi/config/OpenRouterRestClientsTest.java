package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class OpenRouterRestClientsTest {

    // Appends /v1 when absent, trims slashes and spaces first, leaves an existing /v1 alone.
    @ParameterizedTest
    @CsvSource({
            "'https://openrouter.ai/api',         https://openrouter.ai/api/v1",
            "'  https://openrouter.ai/api//  ',   https://openrouter.ai/api/v1",
            "'https://openrouter.ai/api/v1/',     https://openrouter.ai/api/v1",
    })
    void buildsTheV1BaseUrl(String raw, String expected) {
        assertThat(OpenRouterRestClients.v1BaseUrl(raw)).isEqualTo(expected);
    }

    @Test
    void blankBaseUrlIsAConfigError() {
        assertThatThrownBy(() -> OpenRouterRestClients.v1BaseUrl("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openrouter.base-url");
        assertThatThrownBy(() -> OpenRouterRestClients.v1BaseUrl(null))
                .isInstanceOf(IllegalStateException.class);
    }

    // Catches: one hung OpenRouter socket hangs a poster variant thread and the organizer's request.
    @Test
    void v1_gives_up_on_a_socket_that_never_answers() throws Exception {
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

            RestClient client = OpenRouterRestClients.v1("http://127.0.0.1:" + server.getLocalPort(),
                    "test-key", "testing", Duration.ofMillis(300));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThatThrownBy(() -> client.post().uri("/chat/completions").body("{}")
                            .retrieve().body(String.class))
                            .isInstanceOf(ResourceAccessException.class));
        }
    }
}
