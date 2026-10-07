package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.DateCheckInput.KnownEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateCheckInputTest {

    private static DateCheckInput input(String country, List<String> lineup, List<KnownEvent> known,
                                        List<String> communities) {
        return new DateCheckInput(" Metz ", country, null, null, null, null, null, null, null, null, null, null,
                lineup, known, UUID.randomUUID(), LocalDate.of(2026, 9, 30), null, communities, null, null);
    }

    @Test
    void zoneResolvedFromTheNormalisedCountryElseUtc() {
        DateCheckInput in = input(" fr ", null, null, null);

        assertThat(in.country()).isEqualTo("FR");
        assertThat(in.zone()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(in.cityKey()).isEqualTo("metz");
        assertThat(input("ZZ", null, null, null).zone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void knownEventStrengthMustBeOneOrTwo() {
        assertThat(new KnownEvent("X", LocalDate.of(2026, 10, 1), null, 1).strength()).isEqualTo(1);
        assertThatThrownBy(() -> new KnownEvent("X", LocalDate.of(2026, 10, 1), null, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnownEvent("X", LocalDate.of(2026, 10, 1), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
