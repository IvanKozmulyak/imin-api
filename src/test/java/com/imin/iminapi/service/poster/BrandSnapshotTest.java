package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrandSnapshotTest {

    @Test
    void toJson_and_back_round_trips() {
        BrandSnapshot s = new BrandSnapshot(List.of("#ec4899", "#f6c04a"), "https://cdn/l.png", true);
        String json = s.toJson();
        assertThat(json).contains("#ec4899").contains("https://cdn/l.png").contains("\"logoOn\":true");

        BrandSnapshot back = BrandSnapshot.fromJson(json);
        assertThat(back.colors()).containsExactly("#ec4899", "#f6c04a");
        assertThat(back.logoUrl()).isEqualTo("https://cdn/l.png");
        assertThat(back.logoOn()).isTrue();
    }

    @Test
    void fromJson_null_or_blank_returns_null() {
        assertThat(BrandSnapshot.fromJson(null)).isNull();
        assertThat(BrandSnapshot.fromJson("  ")).isNull();
    }

    @Test
    void fromJson_malformed_returns_null() {
        assertThat(BrandSnapshot.fromJson("{not json")).isNull();
    }

    @Test
    void packedAccentColor_leads_with_first_then_supporting() {
        BrandSnapshot s = new BrandSnapshot(List.of("#ec4899", "#f6c04a", "#a78bfa"), null, true);
        assertThat(s.packedAccentColor()).isEqualTo("#ec4899 (lead); supporting: #f6c04a, #a78bfa");
    }

    @Test
    void packedAccentColor_single_colour_has_no_supporting_clause() {
        BrandSnapshot s = new BrandSnapshot(List.of("#ec4899"), null, true);
        assertThat(s.packedAccentColor()).isEqualTo("#ec4899 (lead)");
    }

    @Test
    void packedAccentColor_empty_is_null() {
        BrandSnapshot s = new BrandSnapshot(List.of(), null, true);
        assertThat(s.packedAccentColor()).isNull();
    }
}
