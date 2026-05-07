package com.imin.iminapi.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SluggerTest {

    @Test
    void plain_ascii_is_lowercased_and_space_becomes_hyphen() {
        assertThat(Slugger.slugify("Funkhaus Productions")).isEqualTo("funkhaus-productions");
    }

    @Test
    void diacritics_are_stripped() {
        assertThat(Slugger.slugify("Café Müller")).isEqualTo("cafe-muller");
    }

    @Test
    void punctuation_and_symbols_collapse_to_hyphen() {
        assertThat(Slugger.slugify("Acme & Co., Ltd.")).isEqualTo("acme-co-ltd");
    }

    @Test
    void multiple_spaces_and_hyphens_collapse() {
        assertThat(Slugger.slugify("  hello   --  world  ")).isEqualTo("hello-world");
    }

    @Test
    void empty_string_returns_org() {
        assertThat(Slugger.slugify("")).isEqualTo("org");
    }

    @Test
    void whitespace_only_returns_org() {
        assertThat(Slugger.slugify("   ")).isEqualTo("org");
    }

    @Test
    void symbols_only_returns_org() {
        assertThat(Slugger.slugify("!!!")).isEqualTo("org");
    }

    @Test
    void null_returns_org() {
        assertThat(Slugger.slugify(null)).isEqualTo("org");
    }

    @Test
    void truncation_at_200_chars_with_no_trailing_hyphen() {
        // Build a string that will produce exactly 210 chars of slug-ready content
        // "a" * 210 → slug = "a" * 200 (truncated, no trailing hyphen since it's all 'a')
        String longInput = "a".repeat(210);
        String result = Slugger.slugify(longInput);
        assertThat(result).hasSize(200);
        assertThat(result).doesNotEndWith("-");
    }

    @Test
    void truncation_trims_trailing_hyphen() {
        // 199 'a' chars + separator + more chars: after truncation at 200, last char could be '-'
        // "a" * 199 + "  " + "b" * 20 → normalized: "a"*199 + "-" + "b"*20
        // That's 220 chars, truncated at 200 → "a"*199 + "-" → trailing hyphen must be trimmed
        String input = "a".repeat(199) + "  " + "b".repeat(20);
        String result = Slugger.slugify(input);
        assertThat(result.length()).isLessThanOrEqualTo(200);
        assertThat(result).doesNotEndWith("-");
    }
}
