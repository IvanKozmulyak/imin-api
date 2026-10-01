package com.imin.iminapi.predictor.sources.openweather;

import com.imin.iminapi.predictor.config.PredictorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenWeatherConfigTest {

    @Configuration
    @EnableConfigurationProperties(PredictorProperties.class)
    static class Support {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenWeatherConfig.class, Support.class);

    @Test
    void enabledWithBlankKeyFailsStartup() {
        runner.withPropertyValues("imin.predictor.weather-enabled=true", "imin.predictor.openweather.api-key=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("PREDICTOR_WEATHER_ENABLED")
                            .hasMessageContaining("OPENWEATHER_API_KEY");
                });
    }

    @Test
    void disabledWithBlankKeyStarts() {
        runner.withPropertyValues("imin.predictor.weather-enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(OpenWeatherClient.class));
        // the field default is off too
        runner.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(OpenWeatherClient.class));
    }

    @Test
    void enabledWithKeyStarts() {
        runner.withPropertyValues("imin.predictor.weather-enabled=true", "imin.predictor.openweather.api-key=k")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(OpenWeatherClient.class);
                    assertThat(ctx.getBean(OpenWeatherProperties.class).toString()).contains("<set>")
                            .doesNotContain("=k,").doesNotContain("=k}");
                });
        // a failing boot names variables only, never the configured key or base URL
        runner.withPropertyValues("imin.predictor.weather-enabled=true", "imin.predictor.openweather.api-key=test-not-a-key",
                        "imin.predictor.openweather.base-url=http://plain.test.invalid")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageNotContaining("test-not-a-key")
                        .hasMessageNotContaining("plain.test.invalid"));
    }

    @Test
    void nonHttpsBaseUrlFailsStartup() {
        runner.withPropertyValues("imin.predictor.openweather.base-url=http://x")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("PREDICTOR_OPENWEATHER_BASE_URL");
                });
    }

    private static OpenWeatherProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("imin.predictor.openweather", OpenWeatherProperties.class);
    }

    @Test
    void keyNeverInToString() {
        OpenWeatherProperties props = bind(Map.of("imin.predictor.openweather.api-key", "test-not-a-key"));

        assertThat(props.getApiKey()).isEqualTo("test-not-a-key");
        assertThat(props.toString()).doesNotContain("test-not-a-key").contains("<set>");
        assertThat(bind(Map.of()).toString()).contains("<blank>");
    }

    @Test
    void blankKeyBindsEmpty() {
        assertThat(bind(Map.of("imin.predictor.openweather.api-key", "   ")).getApiKey()).isEmpty();
        assertThat(bind(Map.of("imin.predictor.openweather.api-key", " k ")).getApiKey()).isEqualTo("k");
        OpenWeatherProperties direct = new OpenWeatherProperties();
        direct.setApiKey(null);
        assertThat(direct.getApiKey()).isEmpty();
    }

    @Test
    void blankBaseUrlBindsTheDefault() {
        assertThat(bind(Map.of("imin.predictor.openweather.base-url", " ")).getBaseUrl())
                .isEqualTo(OpenWeatherProperties.DEFAULT_BASE_URL);
        assertThat(bind(Map.of()).getBaseUrl()).isEqualTo("https://api.openweathermap.org");
        assertThat(bind(Map.of("imin.predictor.openweather.base-url", "https://o.test.invalid/")).getBaseUrl())
                .isEqualTo("https://o.test.invalid");
    }
}
