package com.imin.iminapi.marketing.render;

import org.junit.jupiter.api.Test;

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

    @Test
    void blankOrEmailDisplayNameFallsBackToNeutralGreeting() {
        assertThat(MergeTags.apply("Hi {{firstName}}", null, null)).isEqualTo("Hi there");
        assertThat(MergeTags.apply("Hi {{firstName}}", "  ", null)).isEqualTo("Hi there");
        // never greet someone with their email address
        assertThat(MergeTags.apply("Hi {{firstName}}", "buyer@example.com", null)).isEqualTo("Hi there");
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
