package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.service.poster.ReferenceImageLibrary;
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

    private AiEventDescriptionService service;

    @BeforeEach
    void setUp() {
        service = new AiEventDescriptionService(chatClient, library);
        lenient().when(library.tags()).thenReturn(List.of("neon_underground", "chrome_tropical"));
        lenient().when(library.descriptor("neon_underground")).thenReturn("Magenta neon and black void.");
        lenient().when(library.descriptor("chrome_tropical")).thenReturn("Chrome 3D type, sunset gradient.");
    }

    private EventCreatorRequest req(String pinnedTag) {
        return new EventCreatorRequest(
                "vibe", "tone", "genre", "city",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, null, null, null, null,
                pinnedTag);
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
}
