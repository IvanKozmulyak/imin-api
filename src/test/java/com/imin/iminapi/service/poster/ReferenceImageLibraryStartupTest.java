package com.imin.iminapi.service.poster;

import com.imin.iminapi.repository.StyleReferenceAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for {@link ReferenceImageLibrary} startup behaviour — no Spring context,
 * so no network. Verifies the analyze-on-startup flag actually gates the vision-LLM call.
 */
class ReferenceImageLibraryStartupTest {

    private ReferenceImageLibrary newLibrary(boolean analyzeOnStartup, ReferenceImageAnalyzer analyzer) {
        StyleReferenceAnalysisRepository repo = mock(StyleReferenceAnalysisRepository.class);
        return new ReferenceImageLibrary(
                new DefaultResourceLoader(),
                repo,
                analyzer,
                "classpath:poster-references.yaml",
                analyzeOnStartup);
    }

    @Test
    void load_withAnalysisDisabled_neverCallsAnalyzer() {
        ReferenceImageAnalyzer analyzer = mock(ReferenceImageAnalyzer.class);
        when(analyzer.modelId()).thenReturn("test-model");

        ReferenceImageLibrary library = newLibrary(false, analyzer);
        library.load();

        // References still resolve (so they can be sent to the image model)…
        assertThat(library.referenceCount("neon_underground")).isGreaterThanOrEqualTo(1);
        // …but no vision-LLM analysis happens at startup.
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
}
