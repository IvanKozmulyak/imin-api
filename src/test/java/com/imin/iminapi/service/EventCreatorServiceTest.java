package com.imin.iminapi.service;

import com.imin.iminapi.dto.*;
import com.imin.iminapi.exception.EventCreationException;
import com.imin.iminapi.model.Concept;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.repository.GeneratedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventCreatorServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    @Mock private ImageGenerationService imageGenerationService;
    @Mock private PricingService pricingService;
    @Mock private GeneratedEventRepository repository;

    private EventCreatorService service;

    @BeforeEach
    void setUp() {
        service = new EventCreatorService(chatClient, imageGenerationService, pricingService, repository);
    }

    private void stubChatClient(LlmGenerationResult result) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(LlmGenerationResult.class)).thenReturn(result);
    }

    private LlmGenerationResult threeConceptResult() {
        return new LlmGenerationResult(
                List.of(
                        new LlmEventConcept("Void", "Dark and deep.", "Lose yourself."),
                        new LlmEventConcept("Pulse", "Raw energy.", "Feel the beat."),
                        new LlmEventConcept("Flux", "Industrial vibes.", "Pure noise.")
                ),
                List.of(
                        new LlmSocialCopy("INSTAGRAM", "Join us for Void #techno"),
                        new LlmSocialCopy("TWITTER", "Void is happening. #rave")
                ),
                List.of("#1A1A2E", "#E94560", "#0F3460", "#533483", "#2B2D42")
        );
    }

    @Test
    void create_successfulRun_persistsDraftThenComplete() {
        EventCreatorRequest request = new EventCreatorRequest(
                "underground techno night", "edgy", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM", "TWITTER"));

        stubChatClient(threeConceptResult());

        when(imageGenerationService.generatePoster(any(Concept.class), anyString()))
                .thenReturn("https://dalle.example.com/img1.png")
                .thenReturn("https://dalle.example.com/img2.png")
                .thenReturn("https://dalle.example.com/img3.png");

        PricingRecommendation pricing = new PricingRecommendation(
                new BigDecimal("15"), new BigDecimal("25"), "Genre default.");
        when(pricingService.recommend(eq("techno"), eq("Berlin"), any())).thenReturn(pricing);

        List<GeneratedEventStatus> statusesAtSave = new ArrayList<>();
        when(repository.save(any())).thenAnswer(inv -> {
            statusesAtSave.add(((GeneratedEvent) inv.getArgument(0)).getStatus());
            return inv.getArgument(0);
        });

        EventCreatorResponse response = service.create(request);

        assertThat(response.status()).isEqualTo("COMPLETE");
        assertThat(response.concepts()).hasSize(3);
        assertThat(response.accentColors()).hasSize(5);
        assertThat(response.posterUrls()).hasSize(3);
        assertThat(response.socialCopy()).hasSize(2);
        assertThat(response.pricing().suggestedMinPrice()).isEqualByComparingTo("15");

        // DRAFT saved immediately, DRAFT again after LLM, COMPLETE at the end
        assertThat(statusesAtSave).containsExactly(
                GeneratedEventStatus.DRAFT, GeneratedEventStatus.DRAFT, GeneratedEventStatus.COMPLETE);
    }

    @Test
    void create_llmFailure_setsStatusFailedAndThrows() {
        EventCreatorRequest request = new EventCreatorRequest(
                "summer pop concert", "bright", "pop", "London",
                LocalDate.of(2026, 7, 4), List.of("INSTAGRAM"));

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(LlmGenerationResult.class))
                .thenThrow(new RuntimeException("OpenRouter timeout"));

        List<GeneratedEventStatus> statusesAtSave = new ArrayList<>();
        when(repository.save(any())).thenAnswer(inv -> {
            statusesAtSave.add(((GeneratedEvent) inv.getArgument(0)).getStatus());
            return inv.getArgument(0);
        });

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(EventCreationException.class)
                .hasMessageContaining("OpenRouter timeout");

        // DRAFT saved first, then FAILED on error
        assertThat(statusesAtSave).containsExactly(GeneratedEventStatus.DRAFT, GeneratedEventStatus.FAILED);
    }

    @Test
    void create_imageGenerationFailure_setsStatusFailedAndThrows() {
        EventCreatorRequest request = new EventCreatorRequest(
                "underground techno night", "edgy", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"));

        stubChatClient(threeConceptResult());

        when(imageGenerationService.generatePoster(any(Concept.class), anyString()))
                .thenThrow(new RuntimeException("DALL-E rate limited"));

        List<GeneratedEventStatus> statusesAtSave = new ArrayList<>();
        when(repository.save(any())).thenAnswer(inv -> {
            statusesAtSave.add(((GeneratedEvent) inv.getArgument(0)).getStatus());
            return inv.getArgument(0);
        });

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(EventCreationException.class)
                .hasMessageContaining("DALL-E rate limited");

        assertThat(statusesAtSave).contains(GeneratedEventStatus.FAILED);
    }
}
