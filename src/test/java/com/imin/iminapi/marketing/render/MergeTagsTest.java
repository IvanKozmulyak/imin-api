package com.imin.iminapi.marketing.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MergeTagsTest {

    @Test
    void substitutesFirstNameAndEventUrl() {
        String out = MergeTags.apply("Hey {{firstName}}, see {{eventUrl}}", "Ivan Kozmulyak", "https://app.imin.wtf/e/1");
        assertThat(out).isEqualTo("Hey Ivan, see https://app.imin.wtf/e/1");
    }

    @Test
    void firstNameIsFirstTokenOnly() {
        assertThat(MergeTags.firstName("Ann Marie Lee")).isEqualTo("Ann");
        assertThat(MergeTags.firstName("Bohdan")).isEqualTo("Bohdan");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Hi {{firstName}},|Hi,",
            "Bonjour {{firstName}} !|Bonjour !",
            "Привіт, {{firstName}}!|Привіт!",
            "{{firstName}}, your tickets|Your tickets",
            "{{firstName}}, ваші квитки|Ваші квитки",
            "{{firstName}}!|''",
            "**{{firstName}}**,|''",
            "Hi **{{firstName}}**, tickets are live|Hi, tickets are live",
            "{{firstName}}! Doors at 8|Doors at 8"})
    void missingNameIsDroppedWithItsPunctuation(String template, String expected) {
        assertThat(MergeTags.apply(template, null, null)).isEqualTo(expected);
        assertThat(MergeTags.apply(template, "  ", null)).isEqualTo(expected);
        // never greet someone with their email address
        assertThat(MergeTags.apply(template, "buyer@example.com", null)).isEqualTo(expected);
    }

    @Test
    void toleratesWhitespaceAndCaseInsideBraces() {
        assertThat(MergeTags.apply("Hi {{ FirstName }}", "Sam Doe", null)).isEqualTo("Hi Sam");
    }

    @Test
    void missingEventUrlRemovesTheTag() {
        assertThat(MergeTags.apply("Link: {{eventUrl}}", "X", null)).isEqualTo("Link: ");
        assertThat(MergeTags.apply("Link: {{eventUrl}}", "X", "   ")).isEqualTo("Link: ");
    }

    @Test
    void nameWithRegexSpecialCharsIsInsertedLiterally() {
        assertThat(MergeTags.apply("Hi {{firstName}}", "$1\\x", null)).isEqualTo("Hi $1\\x");
    }

    @Test
    void nullBodyIsNullSafe() {
        assertThat(MergeTags.apply(null, "X", "y")).isNull();
    }
}
