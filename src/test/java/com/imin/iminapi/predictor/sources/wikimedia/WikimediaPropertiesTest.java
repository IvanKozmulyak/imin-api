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
    void blankUserAgentFallsBackToDefault() {
        WikimediaProperties props = bind(Map.of("imin.predictor.wikimedia.user-agent", "  ",
                "imin.predictor.wikimedia.enabled", "true"));

        assertThat(props.getUserAgent()).isEqualTo(WikimediaProperties.DEFAULT_USER_AGENT);
        assertThat(props.isEnabled()).isTrue();
    }
}
