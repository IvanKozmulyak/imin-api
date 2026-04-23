package com.imin.iminapi.service.poster;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.service.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
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

    @org.springframework.beans.factory.annotation.Autowired
    private com.imin.iminapi.repository.StyleReferenceAnalysisRepository repo;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private ReferenceImageAnalyzer analyzer;

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

    @org.junit.jupiter.api.BeforeEach
    void resetCache() {
        repo.deleteAll();
        org.mockito.Mockito.clearInvocations(analyzer);
    }

    @org.junit.jupiter.api.Test
    void descriptor_cacheMiss_callsAnalyzerAndPersists() {
        org.mockito.Mockito.when(analyzer.modelId()).thenReturn("test-model");
        org.mockito.Mockito.when(analyzer.analyze(org.mockito.ArgumentMatchers.eq("neon_underground"),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("test descriptor for neon");

        library.reloadDescriptors();

        org.assertj.core.api.Assertions.assertThat(library.descriptor("neon_underground"))
                .isEqualTo("test descriptor for neon");
        org.assertj.core.api.Assertions.assertThat(repo.findById("neon_underground")).isPresent();
    }

    @org.junit.jupiter.api.Test
    void descriptor_cacheHit_skipsAnalyzer() {
        org.mockito.Mockito.when(analyzer.modelId()).thenReturn("test-model");
        String currentSignature = library.computeCurrentSignatureFor("neon_underground");
        com.imin.iminapi.model.StyleReferenceAnalysis row = new com.imin.iminapi.model.StyleReferenceAnalysis();
        row.setSubStyleTag("neon_underground");
        row.setDescriptor("cached descriptor");
        row.setImageSignature(currentSignature);
        row.setModelId("test-model");
        repo.save(row);

        library.reloadDescriptors();

        org.assertj.core.api.Assertions.assertThat(library.descriptor("neon_underground"))
                .isEqualTo("cached descriptor");
        org.mockito.Mockito.verify(analyzer, org.mockito.Mockito.never())
                .analyze(org.mockito.ArgumentMatchers.eq("neon_underground"),
                         org.mockito.ArgumentMatchers.anyList());
    }

    @org.junit.jupiter.api.Test
    void descriptor_signatureStale_reanalyzesAndOverwrites() {
        org.mockito.Mockito.when(analyzer.modelId()).thenReturn("test-model");
        com.imin.iminapi.model.StyleReferenceAnalysis row = new com.imin.iminapi.model.StyleReferenceAnalysis();
        row.setSubStyleTag("neon_underground");
        row.setDescriptor("old descriptor");
        row.setImageSignature("0000000000000000000000000000000000000000000000000000000000000000");
        row.setModelId("test-model");
        repo.save(row);

        org.mockito.Mockito.when(analyzer.analyze(org.mockito.ArgumentMatchers.eq("neon_underground"),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("fresh descriptor");

        library.reloadDescriptors();

        org.assertj.core.api.Assertions.assertThat(library.descriptor("neon_underground"))
                .isEqualTo("fresh descriptor");
        org.assertj.core.api.Assertions.assertThat(repo.findById("neon_underground")).get()
                .extracting(com.imin.iminapi.model.StyleReferenceAnalysis::getDescriptor)
                .isEqualTo("fresh descriptor");
    }

    @org.junit.jupiter.api.Test
    void descriptor_unknownTag_returnsEmpty() {
        org.assertj.core.api.Assertions.assertThat(library.descriptor("not_a_real_tag")).isEmpty();
    }
}
