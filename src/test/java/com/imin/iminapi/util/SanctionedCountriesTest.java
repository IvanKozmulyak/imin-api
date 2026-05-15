package com.imin.iminapi.util;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SanctionedCountriesTest {

    @Test
    void isSanctioned_recognises_known_codes_case_insensitively() {
        assertThat(SanctionedCountries.isSanctioned("IR")).isTrue();
        assertThat(SanctionedCountries.isSanctioned("ir")).isTrue();
        assertThat(SanctionedCountries.isSanctioned("Ru")).isTrue();
        assertThat(SanctionedCountries.isSanctioned("KP")).isTrue();
    }

    @Test
    void isSanctioned_false_for_allowed_codes_and_null() {
        assertThat(SanctionedCountries.isSanctioned("FR")).isFalse();
        assertThat(SanctionedCountries.isSanctioned("US")).isFalse();
        assertThat(SanctionedCountries.isSanctioned("DE")).isFalse();
        assertThat(SanctionedCountries.isSanctioned(null)).isFalse();
        assertThat(SanctionedCountries.isSanctioned("")).isFalse();
    }

    @Test
    void requireAllowed_throws_COUNTRY_NOT_ALLOWED_on_sanctioned() {
        assertThatThrownBy(() -> SanctionedCountries.requireAllowed("IR"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.COUNTRY_NOT_ALLOWED);
    }

    @Test
    void requireAllowed_no_throw_for_allowed_country() {
        SanctionedCountries.requireAllowed("DE");
        SanctionedCountries.requireAllowed("FR");
        SanctionedCountries.requireAllowed(null); // null is handled elsewhere (e.g., @NotBlank)
    }
}
