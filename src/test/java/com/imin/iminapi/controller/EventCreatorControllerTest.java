package com.imin.iminapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.imin.iminapi.config.SecurityConfig;
import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.EventCreatorResponse;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.exception.EventCreationException;
import com.imin.iminapi.exception.EventCreationExceptionHandler;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.TokenService;
import com.imin.iminapi.service.EventCreatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    @MockitoBean private AuthSessionRepository authSessionRepository;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private TokenService tokenService;

    @Test
    void create_validRequest_returns201WithBody() throws Exception {
        EventCreatorRequest request = new EventCreatorRequest(
                "underground techno night", "edgy", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, "Void Sessions IV", null,
                "Kreuzberg 12, Berlin", "https://imin.wtf/e/abc", null, null);

        GeneratedPoster poster = new GeneratedPoster(
                UUID.randomUUID(), "atmospheric",
                "/images/raw.png", "/images/final.png",
                42L, "prompt", List.of(), Map.of(), "COMPLETE", null);

        EventCreatorResponse response = new EventCreatorResponse(
                UUID.randomUUID(), "COMPLETE",
                UUID.randomUUID(), "neon_underground",
                List.of(poster),
                LocalDateTime.now()
        );

        when(eventCreatorService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.subStyleTag").value("neon_underground"))
                .andExpect(jsonPath("$.posters").isArray())
                .andExpect(jsonPath("$.posters[0].finalUrl").value("/images/final.png"));
    }

    @Test
    void create_missingVibe_returns400() throws Exception {
        String invalidBody = """
                {"tone":"edgy","genre":"techno","city":"Berlin","date":"2026-06-14","platforms":["INSTAGRAM"]}
                """;

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.vibe").exists());
    }

    @Test
    void create_serviceThrows_returns500() throws Exception {
        EventCreatorRequest request = new EventCreatorRequest(
                "jazz night", "smooth", "jazz", "NYC",
                LocalDate.of(2026, 8, 1), List.of("TWITTER"),
                null, null, null, null, null, null, null, null);

        when(eventCreatorService.create(any()))
                .thenThrow(new EventCreationException("Image generation failed", new RuntimeException()));

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL"));
    }

    @Test
    void create_unknownSubStyleTag_returns400() throws Exception {
        String body = """
                {"vibe":"v","tone":"t","genre":"g","city":"c","date":"2026-06-14",
                 "platforms":["INSTAGRAM"],"subStyleTag":"made_up_tag"}
                """;

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.subStyleTagValid").exists());
    }

    @Test
    void create_knownSubStyleTag_passesValidation() throws Exception {
        String body = """
                {"vibe":"v","tone":"t","genre":"g","city":"c","date":"2026-06-14",
                 "platforms":["INSTAGRAM"],"subStyleTag":"neon_underground"}
                """;

        GeneratedPoster poster = new GeneratedPoster(
                UUID.randomUUID(), "atmospheric",
                "/images/raw.png", "/images/final.png",
                42L, "prompt", List.of(), Map.of(), "COMPLETE", null);
        EventCreatorResponse response = new EventCreatorResponse(
                UUID.randomUUID(), "COMPLETE",
                UUID.randomUUID(), "neon_underground",
                List.of(poster), LocalDateTime.now());
        when(eventCreatorService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
}
