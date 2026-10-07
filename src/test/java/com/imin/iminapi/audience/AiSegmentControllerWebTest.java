package com.imin.iminapi.audience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.RecordingRateLimiter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/audience/segments/ai-draft} over the real service: a bad prompt is a 400 before any LLM
 * call, and the route needs a principal. The pipeline itself is owned by {@code AiSegmentServiceTest}.
 */
@IminIntegrationTest
class AiSegmentControllerWebTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired ChatClient chatClient;
    @Autowired RecordingRateLimiter limiter;
    final ObjectMapper om = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(ints = {0, 501})
    void ai_draft_rejects_blank_or_overlong_prompt_without_calling_the_llm(int length) throws Exception {
        String prompt = length == 0 ? "   " : "a".repeat(length);
        AuthPrincipal p = fx.principal(fx.owner(fx.org()));

        mvc.perform(post("/api/v1/audience/segments/ai-draft").with(auth(p))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", prompt))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));

        verifyNoInteractions(chatClient);
    }

    /** A failing LLM call is still a 200 that consumed the AI bucket; the empty draft is AiSegmentServiceTest's. */
    @Test
    void ai_draft_consumes_the_ai_bucket_even_when_the_llm_fails() throws Exception {
        AuthPrincipal p = fx.principal(fx.owner(fx.org()));
        when(chatClient.prompt()).thenThrow(new IllegalStateException("upstream LLM unavailable"));

        mvc.perform(post("/api/v1/audience/segments/ai-draft").with(auth(p))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", "fans of house music in Berlin"))))
                .andExpect(status().isOk());

        assertThat(limiter.calls()).contains(new RecordingRateLimiter.Call("ai-concept", p.userId().toString()));
    }

    @Test
    void ai_draft_unauthenticated_is_blocked() throws Exception {
        mvc.perform(post("/api/v1/audience/segments/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", "everyone"))))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(chatClient);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
