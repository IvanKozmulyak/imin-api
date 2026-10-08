package com.imin.iminapi.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterConfigTest {

    // A wrong base URL breaks every AI call. "/v1/" is cut to "/v1" first, then "/v1" dropped.
    @ParameterizedTest
    @CsvSource(value = {
            "https://openrouter.ai/api/,   https://openrouter.ai/api",
            "https://openrouter.ai/api/v1, https://openrouter.ai/api",
            "https://openrouter.ai/api/v1/, https://openrouter.ai/api",
            "https://openrouter.ai/api,    https://openrouter.ai/api",
            "NIL,                          ''",
    }, nullValues = "NIL")
    void normalizesTheBaseUrl(String raw, String expected) {
        assertThat(OpenRouterConfig.normalizeOpenRouterBaseUrl(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"0.4, 0.4", "NIL, NIL"}, nullValues = "NIL")
    void chatOptionsApplyATemperatureOnlyWhenOneIsGiven(Double given, Double expected) {
        var opts = OpenRouterConfig.chatOptions("openai/gpt-4o-mini", given);
        assertThat(opts.getModel()).isEqualTo("openai/gpt-4o-mini");
        assertThat(opts.getTemperature()).isEqualTo(expected);
    }
}
