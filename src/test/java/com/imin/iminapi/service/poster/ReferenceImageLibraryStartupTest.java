package com.imin.iminapi.service.poster;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit tests for {@link ReferenceImageLibrary} loading — no Spring context, no network.
 * Verifies curated vibe folders are globbed, capped, and merged; and that a missing legacy
 * poster-references.yaml is tolerated (vibe references still load).
 */
class ReferenceImageLibraryStartupTest {

    private VibeLibrary realVibes() {
        VibeLibrary v = new VibeLibrary(new DefaultResourceLoader(), "classpath:vibes.yaml");
        v.load();
        return v;
    }

    private ReferenceImageLibrary newLibrary(String configFile, VibeLibrary vibes) {
        ReferenceImageLibrary lib = new ReferenceImageLibrary(
                new DefaultResourceLoader(), vibes, configFile, 4);
        lib.load();
        return lib;
    }

    @Test
    void load_mergesCuratedVibeFolders_globbedAndCapped() {
        ReferenceImageLibrary library = newLibrary("classpath:poster-references.yaml", realVibes());

        // brutalist_techno points at a curated folder → globbed flyers, capped at maxPerTag (4).
        assertThat(library.referenceCount("brutalist_techno")).isBetween(1, 4);
        assertThat(library.forTag("brutalist_techno").referenceUrls())
                .isNotEmpty()
                .allMatch(u -> u.startsWith("data:"));
        // A text-only vibe contributes no references.
        assertThat(library.referenceCount("liquid_melodic")).isZero();
    }

    @Test
    void load_missingLegacyConfig_stillLoadsVibeReferences() {
        // The legacy poster-references.yaml has been removed; loading must not fail.
        ReferenceImageLibrary library = newLibrary("classpath:no-such-file.yaml", realVibes());
        assertThat(library.referenceCount("psytrance_goa")).isBetween(1, 4);
    }
}
