package com.imin.iminapi.audienceplan.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryNumbersTest {

    private static final SummaryNumbers.Allowed ALLOWED =
            SummaryNumbers.allowed("{\"expected\":{\"low\":26,\"high\":93},\"cov\":0.20,\"pct\":20,\"tpo\":1.6,"
                    + "\"size\":1320,\"date\":\"2026-10-24\"}");

    @Test
    void allowed_readsFieldsAndPairs_withTrailingZerosFolded_andDatesWhole() {
        assertThat(ALLOWED.scalars()).containsExactlyInAnyOrder(new BigDecimal("0.2"), new BigDecimal("20"),
                new BigDecimal("1.6"), new BigDecimal("1320"));
        assertThat(ALLOWED.rangeEnds()).containsExactlyInAnyOrder(new BigDecimal("26"), new BigDecimal("93"));
        assertThat(ALLOWED.pairs()).containsExactly(List.of(new BigDecimal("26"), new BigDecimal("93")));
        assertThat(ALLOWED.dates()).containsExactly(LocalDate.of(2026, 10, 24));
    }

    @Test
    void wholeRange_passes() {
        assertThat(SummaryNumbers.invented("Your list could bring 26–93 tickets.", ALLOWED)).isEmpty();
    }

    @Test
    void numberNotInTheInput_isReported() {
        assertThat(SummaryNumbers.invented("About 60 tickets, 26–93 in total.", ALLOWED)).containsExactly("60");
    }

    @Test
    void decimalComma_readsAsDecimal() {
        assertThat(SummaryNumbers.invented("1,6 billets par commande, couverture 0,20", ALLOWED)).isEmpty();
    }

    @Test
    void thousandsSeparators_inEveryLocale_pass() {
        assertThat(SummaryNumbers.invented("1,320 / 1.320 / 1 320 / 1 320", ALLOWED)).isEmpty();
    }

    @Test
    void twoGroupsNotThreeDigits_isADecimalOnly() {
        // 1.5 is not allowed, and two parts are never read as the list "1" and "5".
        SummaryNumbers.Allowed allowed = SummaryNumbers.allowed("[1, 5]");
        assertThat(SummaryNumbers.invented("1.5", allowed)).containsExactly("1.5");
    }

    @Test
    void percentOfAGivenPercent_passes_butADerivedPercentDoesNot() {
        assertThat(SummaryNumbers.invented("20%", ALLOWED)).isEmpty();
        assertThat(SummaryNumbers.invented("36%", ALLOWED)).containsExactly("36");
    }

    @Test
    void readings_ofAmbiguousToken_includeDecimalAndThousands() {
        assertThat(SummaryNumbers.readings("1,320")).containsExactly(new BigDecimal("1.32"), new BigDecimal("1320"));
        assertThat(SummaryNumbers.readings("0,20")).containsExactly(new BigDecimal("0.2"));
    }

    // ── dates ───────────────────────────────────────────────────────────────

    @Test
    void wholeDate_inDigits_passesOnlyWhenItIsAnInputDate() {
        assertThat(SummaryNumbers.invented("le 24.10.2026, on 2026-10-24, 24/10/2026", ALLOWED)).isEmpty();
        assertThat(SummaryNumbers.invented("le 25.10.2026", ALLOWED)).containsExactly("25.10.2026");
    }

    @Test
    void wholeDate_inWords_passesInEveryLocale() {
        assertThat(SummaryNumbers.invented("on 24 October 2026, October 24, 2026, on 24 October", ALLOWED)).isEmpty();
        assertThat(SummaryNumbers.invented("el 24 de octubre de 2026", ALLOWED)).isEmpty();
        assertThat(SummaryNumbers.invented("le 24 octobre 2026", ALLOWED)).isEmpty();
        assertThat(SummaryNumbers.invented("24 жовтня 2026", ALLOWED)).isEmpty();
        assertThat(SummaryNumbers.invented("on 25 October 2026", ALLOWED)).containsExactly("25 October 2026");
    }

    @Test
    void partsOfADate_areNotNumbersOnTheirOwn() {
        assertThat(SummaryNumbers.invented("24 tickets in 2026", ALLOWED)).containsExactly("24", "2026");
    }

    // ── midpoints and range ends ────────────────────────────────────────────

    @Test
    void inventedMid_thatIsADayOfMonth_isRejected() {
        // Pair 20–32 has mid 26, and the 26th is an input date: 26 still traces to no field.
        SummaryNumbers.Allowed a = SummaryNumbers.allowed(
                "{\"gap\":{\"low\":20,\"high\":32},\"today\":\"2026-09-26\"}");
        assertThat(SummaryNumbers.invented("About 26 tickets to find.", a)).containsExactly("26");
    }

    @Test
    void mid_thatIsItselfAField_passes() {
        SummaryNumbers.Allowed a = SummaryNumbers.allowed("{\"gap\":{\"low\":20,\"high\":32},\"daysToEvent\":26}");
        assertThat(SummaryNumbers.invented("The event is in 26 days.", a)).isEmpty();
    }

    @Test
    void bareRangeEnd_isRejected() {
        SummaryNumbers.Allowed a = SummaryNumbers.allowed("{\"gap\":{\"low\":12,\"high\":30}}");
        assertThat(SummaryNumbers.invented("Find 30 tickets.", a)).containsExactly("30");
        assertThat(SummaryNumbers.invented("At least 12 tickets.", a)).containsExactly("12");
    }

    @Test
    void wholeRange_passes_withEveryDashVariant() {
        SummaryNumbers.Allowed a = SummaryNumbers.allowed("{\"gap\":{\"low\":12,\"high\":30}}");
        for (String dash : List.of("-", "‐", "‑", "‒", "–", "—", "−", " – ", " - ")) {
            assertThat(SummaryNumbers.invented("Find 12" + dash + "30 tickets.", a)).as(dash).isEmpty();
        }
    }

    @Test
    void rangeOfTwoEndsFromDifferentPairs_isRejected() {
        SummaryNumbers.Allowed a = SummaryNumbers.allowed(
                "{\"a\":{\"low\":12,\"high\":30},\"b\":{\"low\":40,\"high\":55}}");
        assertThat(SummaryNumbers.invented("12–55 tickets", a)).containsExactly("12", "55");
    }

    @Test
    void rangeEnd_thatIsAlsoAField_passesAlone() {
        SummaryNumbers.Allowed a = SummaryNumbers.allowed("{\"gap\":{\"low\":12,\"high\":300},\"capacity\":300}");
        assertThat(SummaryNumbers.invented("A room of 300.", a)).isEmpty();
    }

    @Test
    void percentPair_passesAsARange_withThePercentSign() {
        SummaryNumbers.Allowed a = SummaryNumbers.allowed("{\"cov\":{\"lowPct\":10,\"highPct\":36}}");
        assertThat(SummaryNumbers.invented("10–36% of the target, 10 %–36 %", a)).isEmpty();
        assertThat(SummaryNumbers.invented("36% of the target", a)).containsExactly("36");
    }
}
