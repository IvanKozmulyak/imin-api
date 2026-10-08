package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
