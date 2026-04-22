package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ReferenceImageLibraryTest {

    @Autowired
    private ReferenceImageLibrary library;

    @Test
    void tags_returnsAllConfiguredTags() {
        List<String> tags = library.tags();
        assertThat(tags).contains(
                "neon_underground", "chrome_tropical", "sunset_silhouette",
                "flat_graphic", "aquatic_distressed", "industrial_minimal", "golden_editorial");
    }

    @Test
    void referenceCount_matchesYamlForKnownTag() {
        assertThat(library.referenceCount("neon_underground")).isEqualTo(3);
        assertThat(library.referenceCount("chrome_tropical")).isEqualTo(2);
        assertThat(library.referenceCount("nonexistent")).isZero();
    }

    @Test
    void loadBytes_validIndex_returnsPngBytes() {
        byte[] bytes = library.loadBytes("neon_underground", 0);
        assertThat(bytes).isNotEmpty();
        // PNG magic number: 89 50 4E 47
        assertThat(bytes[0] & 0xFF).isEqualTo(0x89);
        assertThat(bytes[1] & 0xFF).isEqualTo(0x50);
        assertThat(bytes[2] & 0xFF).isEqualTo(0x4E);
        assertThat(bytes[3] & 0xFF).isEqualTo(0x47);
    }

    @Test
    void loadBytes_unknownTag_throwsIllegalArgument() {
        assertThatThrownBy(() -> library.loadBytes("not_a_tag", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadBytes_indexOutOfRange_throwsIllegalArgument() {
        assertThatThrownBy(() -> library.loadBytes("neon_underground", 99))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
