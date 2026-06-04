package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PosterTextSpecFactoryTest {
    PosterTextSpecFactory factory = new PosterTextSpecFactory();

    @Test
    void fromRequest_buildsRequiredAndAllowedText() {
        EventCreatorRequest r = new EventCreatorRequest(
                "warehouse rave", "energetic", "techno", "Berlin",
                LocalDate.of(2026, 6, 7), List.of("instagram"),
                "DJ A, DJ B", "RSO", "BIG NIGHT - BERLIN", null,
                "Schnellerstrasse 137", "https://imin.wtf/e/big-night",
                "berlin_minimal", null);

        var spec = factory.from(r);

        assertThat(spec.required()).containsExactly("BIG NIGHT - BERLIN", "7 JUN 2026", "RSO");
        assertThat(spec.allowed()).contains("DJ A", "DJ B", "BERLIN");
        assertThat(spec.forPrompt()).contains("\"BIG NIGHT - BERLIN\"");
        assertThat(spec.forPrompt()).contains("No other words, filler text, lorem ipsum, fake letters, pseudo-text, logos, watermarks, or invented copy.");
    }
}
