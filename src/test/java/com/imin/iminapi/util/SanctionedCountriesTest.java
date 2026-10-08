package com.imin.iminapi.util;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SanctionedCountriesTest {

    // Sanctioned codes match case-insensitively and are refused with COUNTRY_NOT_ALLOWED.
    @ParameterizedTest
    @CsvSource({"IR", "ir", "Ru", "KP"})
    void sanctionedCodesAreRefused(String code) {
        assertThat(SanctionedCountries.isSanctioned(code)).isTrue();
        assertThatThrownBy(() -> SanctionedCountries.requireAllowed(code))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COUNTRY_NOT_ALLOWED);
    }

    // Null and empty are handled elsewhere (@NotBlank), so they pass here.
    @ParameterizedTest
    @CsvSource(value = {"FR", "US", "DE", "NIL", "''"}, nullValues = "NIL")
    void allowedCodesNullAndEmptyPass(String code) {
        assertThat(SanctionedCountries.isSanctioned(code)).isFalse();
        assertThatCode(() -> SanctionedCountries.requireAllowed(code)).doesNotThrowAnyException();
    }
}
