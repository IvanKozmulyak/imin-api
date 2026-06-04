package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.UniversalRules;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.service.poster.VibeLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiEventDescriptionServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private VibeLibrary vibeLibrary;

    private AiEventDescriptionService service;

    private static final Vibe BRUTALIST = new Vibe(
            "brutalist_techno", "Brutalist Techno", List.of("techno"),
            "raw exposed concrete, harsh single-source lighting", List.of("#0A0A0A", "#FF2D00"),
            "oversized condensed grotesk, all caps", "giant headline, lots of negative space",
            List.of("severe"), List.of("color gradients", "warmth"),
            "recraft", List.of("reference-images/Brutalist Techno"), null, "brutalist", false);

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new AiEventDescriptionService(chatClient, vibeLibrary);
        lenient().when(vibeLibrary.universalRules()).thenReturn(new UniversalRules(
                List.of("4:5"), "blurry, watermark", "never in the style of a real artist"));
    }

    private EventCreatorRequest req(String pinnedVibeId) {
        return new EventCreatorRequest(
                "vibe", "tone", "techno", "city",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, null, null, null, null,
                pinnedVibeId, null);
    }

    @Test
    void buildPrompt_pinnedVibe_injectsStructuredPresetAndUniversalRules() {
        when(vibeLibrary.byId("brutalist_techno")).thenReturn(Optional.of(BRUTALIST));

        String prompt = service.buildPrompt(req("brutalist_techno"), null);

        assertThat(prompt).contains("must be exactly \"brutalist_techno\"");
        assertThat(prompt).contains("Visual style: raw exposed concrete");
        assertThat(prompt).doesNotContain("Palette:");
        assertThat(prompt).doesNotContain("#0A0A0A");
        assertThat(prompt).contains("Typography: oversized condensed grotesk");
        assertThat(prompt).contains("Avoid: color gradients, warmth");
        assertThat(prompt).contains("AVOID in the artwork: blurry, watermark");
        assertThat(prompt).contains("stock flyer layout");
        assertThat(prompt).contains("template poster");
        assertThat(prompt).contains("no letters");
        assertThat(prompt).contains("no pseudo-text");
        assertThat(prompt).doesNotContain("Decorative abstract lettering is allowed");
        assertThat(prompt).contains("IP rule: never in the style of a real artist");
        assertThat(prompt).doesNotContain("(no descriptor available)");
    }

    @Test
    void buildPrompt_noVibeId_autoSuggestsFromGenre() {
        when(vibeLibrary.byId(null)).thenReturn(Optional.empty());
        when(vibeLibrary.suggestForGenre("techno")).thenReturn(BRUTALIST);

        String prompt = service.buildPrompt(req(null), null);

        assertThat(prompt).contains("must be exactly \"brutalist_techno\"");
        assertThat(prompt).contains("Visual style: raw exposed concrete");
    }

    @Test
    void validate_acceptsKnownVibe_rejectsUnknown() {
        when(vibeLibrary.hasVibe("brutalist_techno")).thenReturn(true);
        when(vibeLibrary.hasVibe("bogus")).thenReturn(false);

        String body = "word ".repeat(40).trim();
        List<PosterVariant> variants = List.of(
                new PosterVariant("atmospheric", body, "4:5", "Design"),
                new PosterVariant("graphic", body, "1:1", "Design"),
                new PosterVariant("minimal", body, "9:16", "Design"));

        assertThat(service.validate(new PosterConcept("brutalist_techno", "palette", variants))).isNull();
        assertThat(service.validate(new PosterConcept("bogus", "palette", variants)))
                .contains("known vibe id");
    }
}
