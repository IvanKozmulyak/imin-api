package com.imin.iminapi.util;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeSupportedCountriesTest {

    @ParameterizedTest
    @CsvSource({"FR", "de", "GB", "CH", "US", "CA", "NO", "LI", "PL"})
    void supportedCodesPassCaseInsensitively(String code) {
        assertThat(StripeSupportedCountries.isSupported(code)).isTrue();
        assertThatCode(() -> StripeSupportedCountries.requireSupported(code)).doesNotThrowAnyException();
    }

    // Outside the bloc Stripe Connect rejects the account; onboarding must say so up front.
    @ParameterizedTest
    @CsvSource(value = {"UA", "RU", "JP", "AU", "SG", "AE", "''"})
    void unsupportedCodesAreRefused(String code) {
        assertThat(StripeSupportedCountries.isSupported(code)).isFalse();
        assertThatThrownBy(() -> StripeSupportedCountries.requireSupported(code))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COUNTRY_NOT_SUPPORTED);
    }

    // Presence is validated elsewhere (@NotBlank), so null passes requireSupported.
    @Test
    void nullIsNotSupportedButNotRefused() {
        assertThat(StripeSupportedCountries.isSupported(null)).isFalse();
        assertThatCode(() -> StripeSupportedCountries.requireSupported(null)).doesNotThrowAnyException();
    }
}
