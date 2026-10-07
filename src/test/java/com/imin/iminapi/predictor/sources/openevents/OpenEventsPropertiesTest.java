package com.imin.iminapi.predictor.sources.openevents;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenEventsPropertiesTest {

    private static OpenEventsProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("imin.predictor.open-events", OpenEventsProperties.class);
    }

    @Configuration
    @EnableConfigurationProperties(OpenEventsProperties.class)
    static class PropsOnly {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropsOnly.class);

    @Test
    void enabledWithSecretKeyFailsStartup() {
        OpenEventsProperties props = bind(Map.of("imin.predictor.open-events.openagenda-enabled", "true",
                "imin.predictor.open-events.openagenda-api-key", "oa_sk_abc"));
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
        OpenEventsProperties legacy = bind(Map.of("imin.predictor.open-events.openagenda-enabled", "true",
                "imin.predictor.open-events.openagenda-api-key", "abc123"));
        assertThatThrownBy(legacy::validate).isInstanceOf(IllegalStateException.class);
        // a key alone, flag off, is never read and never checked
        OpenEventsProperties off = bind(Map.of("imin.predictor.open-events.openagenda-api-key", "oa_sk_abc"));
        off.validate();

        runner.withPropertyValues("imin.predictor.open-events.openagenda-enabled=true",
                        "imin.predictor.open-events.openagenda-api-key=oa_sk_abc")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void messageNamesVariablesNotValue() {
        OpenEventsProperties blank = bind(Map.of("imin.predictor.open-events.openagenda-enabled", "true"));
        assertThatThrownBy(blank::validate)
                .hasMessageContaining("PREDICTOR_OPENAGENDA_ENABLED")
                .hasMessageContaining("OPENAGENDA_API_KEY");
        OpenEventsProperties secret = bind(Map.of("imin.predictor.open-events.openagenda-enabled", "true",
                "imin.predictor.open-events.openagenda-api-key", "oa_sk_secretvalue"));
        assertThatThrownBy(secret::validate)
                .hasMessageContaining("PREDICTOR_OPENAGENDA_ENABLED")
                .hasMessageContaining("OPENAGENDA_API_KEY")
                .hasMessageNotContaining("oa_sk_secretvalue")
                .hasMessageNotContaining("secretvalue");
    }

    @Test
    void toStringMasksKey() {
        OpenEventsProperties props = bind(Map.of("imin.predictor.open-events.openagenda-api-key", "oa_pk_secretvalue"));

        assertThat(props.toString()).doesNotContain("secretvalue").contains("<set>");
        assertThat(bind(Map.of()).toString()).contains("<blank>");
    }
}
