package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.engine.TasteCalculator.Purchase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TasteCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final Set<String> WHITELIST = Set.of("pop", "house & techno");

    @Test
    void nullGenreKey_skipped() {
        assertThat(TasteCalculator.taste(List.of(new Purchase(null, NOW), new Purchase("pop", NOW)), WHITELIST, 180, NOW))
                .containsOnlyKeys("pop").containsEntry("pop", 1.0);
    }

    @Test
    void nullTime_skipped() {
        assertThat(TasteCalculator.taste(List.of(new Purchase("house & techno", null), new Purchase("pop", NOW)),
                WHITELIST, 180, NOW))
                .containsOnlyKeys("pop");
    }

    @Test
    void nothingQualifies_empty() {
        assertThat(TasteCalculator.taste(List.of(new Purchase(null, null)), WHITELIST, 180, NOW)).isEmpty();
    }
}
