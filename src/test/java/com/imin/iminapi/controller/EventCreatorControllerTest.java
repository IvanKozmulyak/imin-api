package com.imin.iminapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.imin.iminapi.config.SecurityConfig;
import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.EventCreatorResponse;
import com.imin.iminapi.exception.EventCreationException;
import com.imin.iminapi.exception.EventCreationExceptionHandler;
import com.imin.iminapi.service.EventCreatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = EventCreatorController.class,
        excludeAutoConfiguration = Saml2RelyingPartyAutoConfiguration.class
)
@Import({SecurityConfig.class, EventCreationExceptionHandler.class})
class EventCreatorControllerTest {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @MockitoBean private EventCreatorService eventCreatorService;

    @Test
    void create_validRequest_returns201WithBody() throws Exception {
        EventCreatorRequest request = new EventCreatorRequest(
                "underground techno night", "edgy", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"));

        EventCreatorResponse response = new EventCreatorResponse(
                UUID.randomUUID(), "COMPLETE",
                List.of("#1A1A2E", "#E94560", "#0F3460", "#533483", "#2B2D42"),
                List.of("https://img1.com", "https://img2.com", "https://img3.com"),
                List.of(new EventCreatorResponse.ConceptDto("Void", "Dark vibes.", "Lose yourself.", 1)),
                List.of(new EventCreatorResponse.SocialCopyDto("INSTAGRAM", "Join us #techno")),
                new EventCreatorResponse.PricingDto(
                        new BigDecimal("15"), new BigDecimal("25"), "SATURDAY", "Genre default."),
                LocalDateTime.now()
        );

        when(eventCreatorService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.concepts").isArray())
                .andExpect(jsonPath("$.accentColors").isArray())
                .andExpect(jsonPath("$.posterUrls").isArray())
                .andExpect(jsonPath("$.pricing.recommendedDow").value("SATURDAY"));
    }

    @Test
    void create_missingVibe_returns400() throws Exception {
        String invalidBody = """
                {"tone":"edgy","genre":"techno","city":"Berlin","date":"2026-06-14","platforms":["INSTAGRAM"]}
                """;

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_serviceThrows_returns500() throws Exception {
        EventCreatorRequest request = new EventCreatorRequest(
                "jazz night", "smooth", "jazz", "NYC",
                LocalDate.of(2026, 8, 1), List.of("TWITTER"));

        when(eventCreatorService.create(any()))
                .thenThrow(new EventCreationException("LLM failed", new RuntimeException()));

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());
    }
}
