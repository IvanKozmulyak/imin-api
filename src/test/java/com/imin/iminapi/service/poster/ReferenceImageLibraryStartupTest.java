package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit tests for {@link ReferenceImageLibrary} loading — no Spring context, no network.
 * Verifies curated vibe folders are globbed, capped, merged, and materialisable as bytes.
 * vibes.yaml is now the only source; the legacy poster-references.yaml branch is gone.
 */
class ReferenceImageLibraryStartupTest {

    private VibeLibrary realVibes() {
        VibeLibrary v = new VibeLibrary(new DefaultResourceLoader(), "classpath:vibes.yaml");
        v.load();
        return v;
    }

    private ReferenceImageLibrary newLibrary(VibeLibrary vibes) {
        ReferenceImageLibrary lib = new ReferenceImageLibrary(new DefaultResourceLoader(), vibes, 4);
        lib.load();
        return lib;
    }

    @Test
    void load_mergesCuratedVibeFolders_globbedAndCapped() {
        ReferenceImageLibrary library = newLibrary(realVibes());

        // brutalist_techno points at a curated folder → globbed flyers, capped at maxPerTag (4).
        assertThat(library.referenceCount("brutalist_techno")).isBetween(1, 4);
        // Every curated vibe (all 12) resolves its folder; capped at maxPerTag (4 here).
        assertThat(library.referenceCount("liquid_melodic")).isBetween(1, 4);
        assertThat(library.referenceCount("psytrance_goa")).isBetween(1, 4);
    }

    @Test
    void load_resolvesReferencesToRealBytes_onTheLiveIdeogramPath() {
        ReferenceImageLibrary library = newLibrary(realVibes());

        assertThat(library.topReferenceParts("brutalist_techno", 4, 10L * 1024 * 1024))
                .isNotEmpty()
                .allSatisfy(part -> {
                    assertThat(part.bytes()).isNotEmpty();
                    assertThat(part.filename()).isNotBlank();
                });
    }
}
