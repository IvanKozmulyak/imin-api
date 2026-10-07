package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.StyleReferencePart;
import com.imin.iminapi.dto.Vibe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link ReferenceImageLibrary} loading — no Spring context, no network.
 * Verifies curated vibe folders are globbed, capped, merged, and materialisable as bytes.
 * vibes.yaml is now the only source; the legacy poster-references.yaml branch is gone.
 */
class ReferenceImageLibraryTest {

    private static ReferenceImageLibrary library;

    @BeforeAll
    static void loadShippedLibrary() {
        library = newLibrary(realVibes());
    }

    private static VibeLibrary realVibes() {
        VibeLibrary v = new VibeLibrary(new DefaultResourceLoader(), "classpath:vibes.yaml");
        v.load();
        return v;
    }

    private static ReferenceImageLibrary newLibrary(VibeLibrary vibes) {
        ReferenceImageLibrary lib = new ReferenceImageLibrary(new DefaultResourceLoader(), vibes, 4);
        lib.load();
        return lib;
    }

    @Test
    void load_mergesCuratedVibeFolders_globbedAndCapped() {
        // brutalist_techno points at a curated folder → globbed flyers, capped at maxPerTag (4).
        assertThat(library.referenceCount("brutalist_techno")).isBetween(1, 4);
        // Every curated vibe (all 12) resolves its folder; capped at maxPerTag (4 here).
        assertThat(library.referenceCount("liquid_melodic")).isBetween(1, 4);
        assertThat(library.referenceCount("psytrance_goa")).isBetween(1, 4);
    }

    @Test
    void load_resolvesReferencesToRealBytes_onTheLiveIdeogramPath() {
        assertThat(library.topReferenceParts("brutalist_techno", 4, 10L * 1024 * 1024))
                .isNotEmpty()
                .allSatisfy(part -> {
                    assertThat(part.bytes()).isNotEmpty();
                    assertThat(part.filename()).isNotBlank();
                });
    }

    /**
     * poster-14: bytesFor returned the UTF-8 bytes of the locator STRING for an http/https/data
     * reference, so a single remote line in vibes.yaml would have uploaded the URL text to Recraft
     * or the style-card generator as if it were a PNG. Its siblings loadBytes and topReferenceParts
     * already throw / skip; bytesFor must not be the one that invents an image.
     */
    @Test
    void remoteReference_isSkipped_notMaterialisedAsUrlText() {
        VibeLibrary vibes = mock(VibeLibrary.class);
        when(vibes.all()).thenReturn(List.of(new Vibe(
                "remote_vibe", "Remote Vibe", List.of("techno"), "vs", List.of("#000"), "typ", "comp",
                List.of(), List.of(), null, List.of("https://cdn.example/flyer.png"), null,
                null, false, "subject", null, null)));

        ReferenceImageLibrary remote = newLibrary(vibes);

        assertThat(remote.referenceCount("remote_vibe")).isEqualTo(1);
        assertThat(remote.loadAllBytes("remote_vibe")).isEmpty();
        // The style picker lists the library's tags; a vibe without references is not one of them.
        assertThat(remote.tags()).containsExactly("remote_vibe");
    }

    @Test
    void loadBytes_validIndex_returnsImageBytes() {
        byte[] bytes = library.loadBytes("brutalist_techno", 0);
        assertThat(bytes).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}[{1}]")
    @CsvSource({"not_a_tag, 0", "brutalist_techno, 99"})
    void loadBytes_unknownTagOrIndexOutOfRange_throwsIllegalArgument(String tag, int index) {
        assertThatThrownBy(() -> library.loadBytes(tag, index))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void topReferenceParts_capsCountAndCarriesMime() {
        List<StyleReferencePart> parts = library.topReferenceParts("brutalist_techno", 3, 10L * 1024 * 1024);

        assertThat(parts).isNotEmpty();
        assertThat(parts.size()).isLessThanOrEqualTo(3);
        assertThat(parts).allSatisfy(p -> {
            assertThat(p.bytes()).isNotEmpty();
            assertThat(p.filename()).isNotBlank();
            assertThat(p.mimeType()).startsWith("image/");
        });
    }

    @Test
    void topReferenceParts_unknownTagIsEmpty() {
        assertThat(library.topReferenceParts("does_not_exist", 3, 10L * 1024 * 1024)).isEmpty();
    }
}
