package com.imin.iminapi.audience;

import com.imin.iminapi.audience.service.ReservedConsentSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReservedConsentSourcesTest {

    @ParameterizedTest
    @ValueSource(strings = {"checkout", "organizer_import", "organizer_import_row", "door_qr", "survey",
            "preference_centre_row", "order_confirmation", "one_click", "footer_link",
            "buyer_account_deletion"})
    void exactSystemSourceIsReserved(String source) {
        assertThat(ReservedConsentSources.isReserved(source)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dsar_object", "dsar_erase", "retention_3y", "sms_stop", "sms_stop_reply"})
    void prefixedSystemSourceIsReserved(String source) {
        assertThat(ReservedConsentSources.isReserved(source)).isTrue();
    }

    @Test
    void caseAndSurroundingWhitespaceDoNotEscapeTheList() {
        assertThat(ReservedConsentSources.isReserved("  Checkout ")).isTrue();
        assertThat(ReservedConsentSources.isReserved("DSAR_Erase")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"checkout\u200B", "checkout\u00A0", "checkout.", "CHECKOUT",
            "\uFF43\uFF48\uFF45\uFF43\uFF4B\uFF4F\uFF55\uFF54", "\u200Bcheck\u200Dout!?", "dsar_erase."})
    void invisibleWidthAndTrailingPunctuationVariantsAreReserved(String source) {
        assertThat(ReservedConsentSources.isReserved(source)).isTrue();
    }

    @Test
    void smsStopIsExactOrUnderscorePrefixOnly() {
        assertThat(ReservedConsentSources.isReserved("sms_stop")).isTrue();
        assertThat(ReservedConsentSources.isReserved("sms_stop_reply")).isTrue();
        assertThat(ReservedConsentSources.isReserved("sms_stopwatch")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sms_stopwatch", "signup-form", "newsletter", "dsar", "checkout-form", "surveys"})
    void organizerTypedSourceIsNotReserved(String source) {
        assertThat(ReservedConsentSources.isReserved(source)).isFalse();
    }

    @Test
    void nullIsNotReserved() {
        assertThat(ReservedConsentSources.isReserved(null)).isFalse();
    }
}
