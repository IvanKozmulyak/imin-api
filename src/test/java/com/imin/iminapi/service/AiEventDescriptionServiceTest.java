package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.UniversalRules;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.service.poster.ReferenceImageLibrary;
import com.imin.iminapi.service.poster.VibeLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiEventDescriptionServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ReferenceImageLibrary library;
    @Mock private VibeLibrary vibeLibrary;

    private AiEventDescriptionService service;

    @BeforeEach
    void setUp() {
        service = new AiEventDescriptionService(chatClient, library, vibeLibrary);
        lenient().when(library.tags()).thenReturn(List.of("neon_underground", "chrome_tropical"));
        lenient().when(library.descriptor("neon_underground")).thenReturn("Magenta neon and black void.");
        lenient().when(library.descriptor("chrome_tropical")).thenReturn("Chrome 3D type, sunset gradient.");
    }

    private EventCreatorRequest req(String pinnedTag) {
        return new EventCreatorRequest(
                "vibe", "tone", "genre", "city",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, null, null, null, null,
                pinnedTag, null);
    }

    @Test
    void buildPrompt_noPinnedTag_includesStyleGuideForEveryTag() {
        String prompt = service.buildPrompt(req(null), null);

        assertThat(prompt).contains("neon_underground — Magenta neon and black void.");
        assertThat(prompt).contains("chrome_tropical — Chrome 3D type, sunset gradient.");
        assertThat(prompt).contains("pick one and weave its style notes");
    }

    @Test
    void buildPrompt_pinnedTag_emitsSingleImperativeLine() {
        String prompt = service.buildPrompt(req("chrome_tropical"), null);

        assertThat(prompt).contains("sub_style_tag is pre-selected as chrome_tropical");
        assertThat(prompt).contains("Chrome 3D type, sunset gradient.");
        assertThat(prompt).doesNotContain("neon_underground —");
    }

    @Test
    void buildPrompt_descriptorMissing_emitsPlaceholder() {
        when(library.descriptor("neon_underground")).thenReturn("");

        String prompt = service.buildPrompt(req(null), null);

        assertThat(prompt).contains("neon_underground — (no descriptor available)");
    }

    @Test
    void buildPrompt_pinnedVibe_injectsStructuredPresetAndUniversalRules() {
        Vibe v = new Vibe("brutalist_techno", "Brutalist Techno", List.of("techno"),
                "raw exposed concrete, harsh single-source lighting", List.of("#0A0A0A", "#FF2D00"),
                "oversized condensed grotesk, all caps", "giant headline, lots of negative space",
                List.of("severe"), List.of("color gradients", "warmth"),
                "recraft", List.of("reference-images/Brutalist Techno"), null, "brutalist", false);
        when(vibeLibrary.byId("brutalist_techno")).thenReturn(java.util.Optional.of(v));
        when(vibeLibrary.universalRules()).thenReturn(new UniversalRules(
                List.of("4:5"), "blurry, watermark", "never in the style of a real artist"));

        String prompt = service.buildPrompt(req("brutalist_techno"), null);

        assertThat(prompt).contains("sub_style_tag is pre-selected as brutalist_techno");
        assertThat(prompt).contains("Visual style: raw exposed concrete");
        assertThat(prompt).contains("Palette: #0A0A0A, #FF2D00");
        assertThat(prompt).contains("Typography: oversized condensed grotesk");
        assertThat(prompt).contains("Avoid: color gradients, warmth");
        assertThat(prompt).contains("AVOID in the artwork: blurry, watermark");
        assertThat(prompt).contains("IP rule: never in the style of a real artist");
        assertThat(prompt).doesNotContain("(no descriptor available)");
    }
}
