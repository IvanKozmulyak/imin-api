package com.imin.iminapi.util;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeSupportedCountriesTest {

    @Test
    void isSupported_true_for_bloc_codes_case_insensitively() {
        assertThat(StripeSupportedCountries.isSupported("FR")).isTrue();
        assertThat(StripeSupportedCountries.isSupported("de")).isTrue();
        assertThat(StripeSupportedCountries.isSupported("GB")).isTrue();
        assertThat(StripeSupportedCountries.isSupported("CH")).isTrue();
        assertThat(StripeSupportedCountries.isSupported("US")).isTrue();
        assertThat(StripeSupportedCountries.isSupported("CA")).isTrue();
        assertThat(StripeSupportedCountries.isSupported("NO")).isTrue();
        assertThat(StripeSupportedCountries.isSupported("LI")).isTrue();
        assertThat(StripeSupportedCountries.isSupported("PL")).isTrue();
    }

    @Test
    void isSupported_false_for_outside_bloc_null_and_blank() {
        assertThat(StripeSupportedCountries.isSupported("UA")).isFalse();
        assertThat(StripeSupportedCountries.isSupported("RU")).isFalse();
        assertThat(StripeSupportedCountries.isSupported("JP")).isFalse();
        assertThat(StripeSupportedCountries.isSupported("AU")).isFalse();
        assertThat(StripeSupportedCountries.isSupported("SG")).isFalse();
        assertThat(StripeSupportedCountries.isSupported("AE")).isFalse();
        assertThat(StripeSupportedCountries.isSupported(null)).isFalse();
        assertThat(StripeSupportedCountries.isSupported("")).isFalse();
    }

    @Test
    void requireSupported_throws_COUNTRY_NOT_SUPPORTED_on_unsupported() {
        assertThatThrownBy(() -> StripeSupportedCountries.requireSupported("UA"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COUNTRY_NOT_SUPPORTED);
    }

    @Test
    void requireSupported_no_throw_for_supported_country_and_null() {
        StripeSupportedCountries.requireSupported("FR");
        StripeSupportedCountries.requireSupported(null); // presence validated elsewhere (e.g., @NotBlank)
    }
}
