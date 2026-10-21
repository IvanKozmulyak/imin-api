package com.imin.iminapi.marketing.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignLocaleTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "FR,null,fr",
            "MC,null,fr",
            "fr,null,fr",
            "UA,es,uk",
            "ES,null,es",
            "DE,uk,uk",
            "null,es,es",
            "DE,null,en",
            "DE,de,en",
            "null,null,en"})
    void defaultFromCountryThenDashboardLanguageThenEnglish(String country, String userLocale, String expected) {
        assertThat(CampaignLocale.defaultFor(country, userLocale)).isEqualTo(expected);
    }
}
