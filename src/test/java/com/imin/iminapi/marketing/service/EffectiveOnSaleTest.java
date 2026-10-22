package com.imin.iminapi.marketing.service;

import com.imin.iminapi.model.TicketTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveOnSaleTest {

    static final Instant CREATED = Instant.parse("2026-10-01T09:00:00Z");
    static final Instant PUBLISHED = Instant.parse("2026-10-03T18:00:00Z");
    static final Instant EVENT_ON_SALE = Instant.parse("2026-10-05T10:00:00Z");

    static Stream<Arguments> cases() {
        Instant twoDaysLater = PUBLISHED.plus(Duration.ofDays(2));
        Instant threeDaysLater = PUBLISHED.plus(Duration.ofDays(3));
        return Stream.of(
                Arguments.of("the event's own date wins", EVENT_ON_SALE, PUBLISHED,
                        List.of(tier(true, threeDaysLater)), EVENT_ON_SALE),
                Arguments.of("an untimed enabled tier is buyable from publish", null, PUBLISHED,
                        List.of(tier(true, null)), PUBLISHED),
                Arguments.of("every enabled tier opens after publish", null, PUBLISHED,
                        List.of(tier(true, threeDaysLater), tier(true, twoDaysLater)), twoDaysLater),
                Arguments.of("a disabled tier's earlier start is ignored", null, PUBLISHED,
                        List.of(tier(false, PUBLISHED.minus(Duration.ofDays(1))), tier(true, twoDaysLater)), twoDaysLater),
                Arguments.of("a tier open before publish makes it publish", null, PUBLISHED,
                        List.of(tier(true, PUBLISHED.minus(Duration.ofHours(5))), tier(true, threeDaysLater)), PUBLISHED),
                Arguments.of("never published falls back to creation", null, null,
                        List.of(tier(true, null)), CREATED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void picksWhenTicketsBecameBuyable(String label, Instant eventOnSale, Instant published, List<TicketTier> tiers,
                                       Instant expected) {
        assertThat(EffectiveOnSale.of(eventOnSale, published, CREATED, tiers)).isEqualTo(expected);
    }

    private static TicketTier tier(boolean enabled, Instant saleStartsAt) {
        TicketTier t = new TicketTier();
        t.setEnabled(enabled);
        t.setSaleStartsAt(saleStartsAt);
        return t;
    }
}
