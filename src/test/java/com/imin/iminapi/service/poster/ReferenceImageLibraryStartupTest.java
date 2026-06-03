package com.imin.iminapi.service.poster;

import com.imin.iminapi.repository.StyleReferenceAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link ReferenceImageLibrary} startup behaviour — no Spring context,
 * so no network. Verifies the analyze-on-startup flag gates the vision-LLM call and that
 * curated vibe folders are globbed and merged.
 */
class ReferenceImageLibraryStartupTest {

    private VibeLibrary emptyVibes() {
        VibeLibrary v = mock(VibeLibrary.class);
        when(v.all()).thenReturn(List.of());
        return v;
    }

    private VibeLibrary realVibes() {
        VibeLibrary v = new VibeLibrary(new DefaultResourceLoader(), "classpath:vibes.yaml");
        v.load();
        return v;
    }

    private ReferenceImageLibrary newLibrary(boolean analyzeOnStartup, ReferenceImageAnalyzer analyzer) {
        return newLibrary(analyzeOnStartup, analyzer, emptyVibes());
    }

    private ReferenceImageLibrary newLibrary(boolean analyzeOnStartup, ReferenceImageAnalyzer analyzer, VibeLibrary vibes) {
        StyleReferenceAnalysisRepository repo = mock(StyleReferenceAnalysisRepository.class);
        return new ReferenceImageLibrary(
                new DefaultResourceLoader(), repo, analyzer, vibes,
                "classpath:poster-references.yaml", analyzeOnStartup, 4);
    }

    @Test
    void load_withAnalysisDisabled_neverCallsAnalyzer() {
        ReferenceImageAnalyzer analyzer = mock(ReferenceImageAnalyzer.class);
        when(analyzer.modelId()).thenReturn("test-model");

        ReferenceImageLibrary library = newLibrary(false, analyzer);
        library.load();

        assertThat(library.referenceCount("neon_underground")).isGreaterThanOrEqualTo(1);
        verify(analyzer, never()).analyze(anyString(), anyList());
    }

    @Test
    void load_withAnalysisEnabled_callsAnalyzerForPopulatedTags() {
        ReferenceImageAnalyzer analyzer = mock(ReferenceImageAnalyzer.class);
        when(analyzer.modelId()).thenReturn("test-model");
        when(analyzer.analyze(anyString(), anyList())).thenReturn("descriptor");

        ReferenceImageLibrary library = newLibrary(true, analyzer);
        library.load();

        verify(analyzer, org.mockito.Mockito.atLeastOnce()).analyze(anyString(), anyList());
    }

    @Test
    void load_mergesCuratedVibeFolders_globbedAndCapped() {
        ReferenceImageAnalyzer analyzer = mock(ReferenceImageAnalyzer.class);
        when(analyzer.modelId()).thenReturn("test-model");

        ReferenceImageLibrary library = newLibrary(false, analyzer, realVibes());
        library.load();

        // brutalist_techno points at a curated folder → globbed flyers, capped at maxPerTag (4).
        assertThat(library.referenceCount("brutalist_techno")).isBetween(1, 4);
        assertThat(library.forTag("brutalist_techno").referenceUrls())
                .isNotEmpty()
                .allMatch(u -> u.startsWith("data:"));
        // A text-only vibe contributes no references.
        assertThat(library.referenceCount("liquid_melodic")).isZero();
    }
}
