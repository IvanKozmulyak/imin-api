package com.imin.iminapi.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SluggerTest {

    // Lowercase, diacritics stripped, punctuation and runs of spaces/hyphens collapse, empty falls back to "org".
    @ParameterizedTest
    @CsvSource(value = {
            "Funkhaus Productions,       funkhaus-productions",
            "Café Müller,                cafe-muller",
            "'Acme & Co., Ltd.',         acme-co-ltd",
            "'  hello   --  world  ',    hello-world",
            "'',                         org",
            "'   ',                      org",
            "!!!,                        org",
            "NIL,                        org",
    }, nullValues = "NIL")
    void slugifies(String input, String expected) {
        assertThat(Slugger.slugify(input)).isEqualTo(expected);
    }

    @Test
    void truncatesAt200CharsWithNoTrailingHyphen() {
        assertThat(Slugger.slugify("a".repeat(210))).hasSize(200);
        // 199 a's + separator: the cut at 200 lands on the hyphen, which must be trimmed.
        String result = Slugger.slugify("a".repeat(199) + "  " + "b".repeat(20));
        assertThat(result).isEqualTo("a".repeat(199));
    }
}
