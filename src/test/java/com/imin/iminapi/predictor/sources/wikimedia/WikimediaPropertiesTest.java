package com.imin.iminapi.predictor.sources.wikimedia;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WikimediaPropertiesTest {

    private static WikimediaProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("imin.predictor.wikimedia", WikimediaProperties.class);
    }

    @Test
    void defaultsWithYamlKeyAbsent() {
        WikimediaProperties props = bind(Map.of());

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getUserAgent())
                .isEqualTo("imin-api/1.0 (+https://imin.wtf; ops@imin.wtf) predictor-trends")
                .isEqualTo(WikimediaProperties.DEFAULT_USER_AGENT);
    }

    @Test
    void blankUserAgentFallsBackToDefault() {
        WikimediaProperties props = bind(Map.of("imin.predictor.wikimedia.user-agent", "  ",
                "imin.predictor.wikimedia.enabled", "true"));

        assertThat(props.getUserAgent()).isEqualTo(WikimediaProperties.DEFAULT_USER_AGENT);
        assertThat(props.isEnabled()).isTrue();
    }
}
