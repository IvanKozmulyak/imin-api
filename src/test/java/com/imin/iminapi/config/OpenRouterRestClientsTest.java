package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * poster-15: this normalization existed verbatim in three classes, each with its own hand-rolled
 * bearer client, and none of them had a test. One copy, one test.
 */
class OpenRouterRestClientsTest {

    @Test
    void appendsV1WhenAbsent() {
        assertThat(OpenRouterRestClients.v1BaseUrl("https://openrouter.ai/api"))
                .isEqualTo("https://openrouter.ai/api/v1");
    }

    @Test
    void trimsTrailingSlashesBeforeAppending() {
        assertThat(OpenRouterRestClients.v1BaseUrl("  https://openrouter.ai/api//  "))
                .isEqualTo("https://openrouter.ai/api/v1");
    }

    @Test
    void leavesAnExistingV1Alone() {
        assertThat(OpenRouterRestClients.v1BaseUrl("https://openrouter.ai/api/v1/"))
                .isEqualTo("https://openrouter.ai/api/v1");
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
