package com.imin.iminapi.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterConfigTest {

    @Test
    void normalize_stripsTrailingSlash() {
        assertThat(OpenRouterConfig.normalizeOpenRouterBaseUrl("https://openrouter.ai/api/"))
                .isEqualTo("https://openrouter.ai/api");
    }

    @Test
    void normalize_stripsV1Suffix() {
        assertThat(OpenRouterConfig.normalizeOpenRouterBaseUrl("https://openrouter.ai/api/v1"))
                .isEqualTo("https://openrouter.ai/api");
    }

    @Test
    void normalize_stripsTrailingSlashThenV1() {
        // Spring AI strips /v1/ → /v1 first, then strips /v1
        assertThat(OpenRouterConfig.normalizeOpenRouterBaseUrl("https://openrouter.ai/api/v1/"))
                .isEqualTo("https://openrouter.ai/api");
    }

    @Test
    void normalize_noOpForPlainBaseUrl() {
        assertThat(OpenRouterConfig.normalizeOpenRouterBaseUrl("https://openrouter.ai/api"))
                .isEqualTo("https://openrouter.ai/api");
    }

    @Test
    void normalize_handlesNull() {
        assertThat(OpenRouterConfig.normalizeOpenRouterBaseUrl(null))
                .isEqualTo("");
    }
}
