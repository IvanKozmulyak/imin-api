package com.imin.iminapi.service.poster;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.service.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class ReferenceImageLibraryTest {

    @MockitoBean AuthService authService;

    @Autowired
    private ReferenceImageLibrary library;

    static final List<String> CURATED_VIBES = List.of(
            "brutalist_techno", "berlin_minimal", "acid_rave_y2k", "psytrance_goa", "industrial_hard_groove");

    @Test
    void tags_includeAllCuratedVibes() {
        assertThat(library.tags()).containsAll(CURATED_VIBES);
    }

    @Test
    void everyResolvedTag_resolvesAtLeastOneReference() {
        // Guard against silent breakage: every tag the library exposes must have >=1 image.
        for (String tag : library.tags()) {
            assertThat(library.referenceCount(tag))
                    .as("tag '%s' must resolve at least one reference image", tag)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void curatedVibeReferences_areCappedAtMaxPerTag() {
        for (String vibe : CURATED_VIBES) {
            assertThat(library.referenceCount(vibe))
                    .as("vibe '%s' references", vibe)
                    .isBetween(1, 4); // glob-capped at poster.references.max-per-tag (default 4)
        }
    }

    @Test
    void loadBytes_validIndex_returnsImageBytes() {
        byte[] bytes = library.loadBytes("brutalist_techno", 0);
        assertThat(bytes).isNotEmpty();
    }

    @Test
    void loadBytes_unknownTag_throwsIllegalArgument() {
        assertThatThrownBy(() -> library.loadBytes("not_a_tag", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadBytes_indexOutOfRange_throwsIllegalArgument() {
        assertThatThrownBy(() -> library.loadBytes("brutalist_techno", 99))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
