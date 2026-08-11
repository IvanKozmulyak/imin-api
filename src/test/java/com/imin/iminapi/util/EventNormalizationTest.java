package com.imin.iminapi.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The V82 normalisation rules, pinned. These same rules are re-expressed in SQL inside
 * {@code V82__normalize_event_facets.sql} for the backfill — if you change one, change both,
 * or old rows and new rows stop agreeing on what city they are in.
 */
class EventNormalizationTest {

    @Test
    void city_collapses_whitespace_and_trims_but_never_touches_case() {
        assertThat(EventNormalization.city("  Le   Mans ")).isEqualTo("Le Mans");
        assertThat(EventNormalization.city("METZ")).isEqualTo("METZ");
        // Real names that any case-folding would wreck. This is why the merge lives in the
        // derived key and not in the display string.
        assertThat(EventNormalization.city("'s-Hertogenbosch")).isEqualTo("'s-Hertogenbosch");
        assertThat(EventNormalization.city("L'Aquila")).isEqualTo("L'Aquila");
        assertThat(EventNormalization.city(null)).isEmpty();
        assertThat(EventNormalization.city("   ")).isEmpty();
    }

    @Test
    void city_key_folds_case_and_whitespace_so_one_place_is_one_key() {
        assertThat(EventNormalization.cityKey("Metz")).isEqualTo("metz");
        assertThat(EventNormalization.cityKey("METZ")).isEqualTo("metz");
        assertThat(EventNormalization.cityKey("  metz  ")).isEqualTo("metz");
        assertThat(EventNormalization.cityKey("Le\tMans")).isEqualTo("le mans");
        assertThat(EventNormalization.cityKey(null)).isEmpty();
    }

    @Test
    void country_is_upper_case_or_null_and_never_the_empty_string() {
        assertThat(EventNormalization.country(" fr ")).isEqualTo("FR");
        assertThat(EventNormalization.country("FR")).isEqualTo("FR");
        assertThat(EventNormalization.country("")).isNull();
        assertThat(EventNormalization.country("   ")).isNull();
        assertThat(EventNormalization.country(null)).isNull();
    }

    @Test
    void country_codes_are_recognisable_so_junk_can_be_rejected_rather_than_stored() {
        assertThat(EventNormalization.isCountryCode("FR")).isTrue();
        assertThat(EventNormalization.isCountryCode("FRANCE")).isFalse();
        assertThat(EventNormalization.isCountryCode("F")).isFalse();
        assertThat(EventNormalization.isCountryCode("F1")).isFalse();
        assertThat(EventNormalization.isCountryCode(null)).isFalse();
    }

    @Test
    void genre_keeps_the_organizers_casing_because_both_frontends_print_it_verbatim() {
        // Case-folding here is what broke the organizer wizard: imin-webapp binds the stored
        // genre to a closed Title-Case GENRES list, so "house & techno" matches no option and
        // the "Music style" field renders EMPTY when the event is reopened.
        assertThat(EventNormalization.genre("Techno")).isEqualTo("Techno");
        assertThat(EventNormalization.genre("  House  &   Techno ")).isEqualTo("House & Techno");
        // null means "field absent" and must survive as null; blank means "cleared" and the
        // column is NOT NULL DEFAULT ''.
        assertThat(EventNormalization.genre(null)).isNull();
        assertThat(EventNormalization.genre("  ")).isEmpty();
    }

    @Test
    void genre_key_is_the_lower_cased_merge_key_the_facet_and_filter_share() {
        assertThat(EventNormalization.genreKey("Techno")).isEqualTo("techno");
        assertThat(EventNormalization.genreKey("TECHNO")).isEqualTo("techno");
        assertThat(EventNormalization.genreKey("  House  &   Techno ")).isEqualTo("house & techno");
        // Same null/blank contract as cityKey: no key, so the facet drops the row.
        assertThat(EventNormalization.genreKey(null)).isEmpty();
        assertThat(EventNormalization.genreKey("   ")).isEmpty();
    }

    @Test
    void genre_and_its_key_are_derived_from_the_same_collapse_so_they_cannot_disagree() {
        String raw = "  Drum   &  Bass ";
        assertThat(EventNormalization.genreKey(raw))
                .isEqualTo(EventNormalization.genre(raw).toLowerCase(java.util.Locale.ROOT));
    }
}
