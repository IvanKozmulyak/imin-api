package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.service.LlmPayloadGuard.Reason;
import com.imin.iminapi.audienceplan.service.LlmPayloadGuard.Rejected;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmPayloadGuardTest {

    private static final String CLEAN = """
            Target 255 tickets (85% of 300). Loyal 26-93, repeat 52, coverage 0.10-0.36.
            Genre: house & techno, adjacent club / open format. Event on 12/10/2026 in Metz, 28 days out.
            Gap +40 tickets, holdout 15%.""";

    private final LlmPayloadGuard guard = new LlmPayloadGuard();

    @Test
    void cleanAggregatePayload_passes() {
        assertThatCode(() -> guard.check(CLEAN, List.of("Marie Dupont", "Zoé")))
                .doesNotThrowAnyException();
    }

    @Test
    void email_rejected() {
        assertRejected(CLEAN + " Contact marie.dupont+vip@example.fr", List.of(), Reason.EMAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+33 6 12 34 56 78",
            "+447700900123",
            "+33 6 12 34 56 78",
            "+33 (0)6-12-34-56-78",
            "06 12 34 56 78",
            "0612345678",
            "06.12.34.56.78",
            "01-42-68-53-00",
            "06\u00A012\u00A034\u00A056\u00A078",
            "06\u202F12\u202F34\u202F56\u202F78",
            "0033 6 12 34 56 78",
            "0034612345678",
            "612 345 678",
            "912345678",
            "712-345-678",
            "067 123 45 67",
            "0671234567",
            "(067) 123-45-67",
            "(+33) 6 12 34 56 78",
            "+ 33 6 12 34 56 78",
            "+33 - 6 12 34 56 78",
            "\uFF10\uFF16\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18"})
    void phone_rejected(String phone) {
        assertRejected(CLEAN + " call " + phone + " today", List.of(), Reason.PHONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Doors 04.10.2026 20:00",
            "Event 01.11.2026 19h",
            "Event 03-10-2026 21",
            "Gap +40 (24.10.2026)",
            "Loyal 26\u201393 guests",
            "range ~25\u201395",
            "coverage 10%-36%, lift +12%, holdout 15 %",
            "12/10/2026 at 23:30, 1 240 fans, 8.5% share"})
    void datesRangesAndPercentages_pass(String text) {
        assertThatCode(() -> guard.check(text, List.of())).doesNotThrowAnyException();
    }

    @Test
    void fullWidthAt_rejectedAsEmail() {
        assertRejected("mail marie\uFF20example.fr", List.of(), Reason.EMAIL);
    }

    @Test
    void longRunWithoutAt_isLinear() {
        String run = "a".repeat(LlmPayloadGuard.MAX_PAYLOAD_CHARS);
        long start = System.nanoTime();
        guard.check(run, List.of());
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Jean Pierre Martin", "O\u2019Brien", "STRASSE", "Jean\u2011Pierre Martin"})
    void nameFolding_bridgesDashApostropheAndSharpS(String inPayload) {
        assertRejected("fan " + inPayload + " came", List.of("Jean-Pierre Martin", "O'Brien", "Straße"), Reason.NAME);
    }

    @Test
    void longRepetitiveInputs_rejectedNeverError() {
        assertRejected("+" + "1 ".repeat(4000), List.of(), Reason.PHONE);
        assertRejected("a@b" + ".c".repeat(4000), List.of(), Reason.EMAIL);
    }

    @Test
    void payloadAtLimit_checked_overLimit_tooLong() {
        String tenK = (CLEAN + " ").repeat(10_000 / CLEAN.length() + 1).substring(0, 10_000);
        assertThatCode(() -> guard.check(tenK, List.of("Marie Dupont"))).doesNotThrowAnyException();
        assertRejected("x".repeat(LlmPayloadGuard.MAX_PAYLOAD_CHARS + 1), List.of(), Reason.TOO_LONG);
    }

    @Test
    void knownGap_mixedSeparatorFrNumber_passes() {
        // Accepted gap: one consistent separator is what keeps dates out.
        assertThatCode(() -> guard.check("call 06 12-34 56 78", List.of())).doesNotThrowAnyException();
    }

    @Test
    void knownFailClosed_signedLargeSpacedNumber_rejected() {
        assertRejected("revenue +1 234 567 this season", List.of(), Reason.PHONE);
    }

    @Test
    void frNational_insideLongerDigitRun_notAPhone() {
        assertThatCode(() -> guard.check("ref 106123456789", List.of())).doesNotThrowAnyException();
    }

    @Test
    void knownMemberName_rejected() {
        assertRejected(CLEAN + " Top fan: Marie Dupont.", List.of("Marie Dupont"), Reason.NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MARIE DUPONT", "marie   dupont", "Marie\nDupont", "Zoe", "ZOÉ"})
    void knownMemberName_caseAccentWhitespaceVariants_rejected(String variant) {
        assertRejected("fan " + variant + " came 3 times", List.of("Marie Dupont", "Zoé"), Reason.NAME);
    }

    @Test
    void nameInsideLongerWord_notMatched() {
        assertThatCode(() -> guard.check("Annabelle techno night", List.of("Anna")))
                .doesNotThrowAnyException();
    }

    @Test
    void nullBlankAndOneLetterNames_skipped() {
        assertThatCode(() -> guard.check("a b 12 tickets", Arrays.asList(null, "", "   ", "A", "b")))
                .doesNotThrowAnyException();
    }

    @Test
    void twoLetterName_checked() {
        assertRejected("guest Li bought 2", List.of("Li"), Reason.NAME);
    }

    @Test
    void nullNameCollection_treatedAsEmpty() {
        assertThatCode(() -> guard.check(CLEAN, null)).doesNotThrowAnyException();
        assertRejected("x@y.io", null, Reason.EMAIL);
    }

    @Test
    void nullPayload_throwsNpe() {
        assertThatThrownBy(() -> guard.check(null, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectionMessage_neverContainsMatchedValue() {
        assertThatThrownBy(() -> guard.check("mail marie@example.fr", List.of()))
                .hasMessageNotContaining("marie").hasMessageContaining("email");
        assertThatThrownBy(() -> guard.check("tel 0612345678", List.of()))
                .hasMessageNotContaining("0612345678").hasMessageContaining("phone");
        assertThatThrownBy(() -> guard.check("fan Dupont", List.of("Dupont")))
                .hasMessageNotContaining("Dupont").hasMessageContaining("name");
    }

    private void assertRejected(String payload, List<String> names, Reason reason) {
        assertThatThrownBy(() -> guard.check(payload, names))
                .isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.reason()).isEqualTo(reason));
    }
}
