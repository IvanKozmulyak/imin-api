package com.imin.iminapi.controller.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.AiGenerationUsage;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.AiGenerationUsageRepository;
import com.imin.iminapi.service.ai.AiQuotaProperties;
import com.imin.iminapi.service.poster.IdeogramV3Client;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The paid image pipeline's rolling-24h quota, over HTTP: at the limit the call is a 429 and nothing is rendered. */
@IminIntegrationTest
class AiQuotaControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired AiGenerationUsageRepository usageRepo;
    @Autowired AiQuotaProperties quotaProps;
    @Autowired PropertyFlips flips;
    @Autowired MutableClock clock;
    @Autowired IdeogramV3Client ideogram;
    @Autowired ChatClient chatClient;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void concept_over_daily_quota_returns_429_envelope_and_never_renders() throws Exception {
        flips.set(quotaProps, "imagePerDay", 2);
        Organization org = fx.org();
        User owner = fx.owner(org);
        Instant oldest = clock.instant().truncatedTo(ChronoUnit.MICROS).minus(Duration.ofHours(1));
        seedImageUsage(owner, oldest);
        seedImageUsage(owner, oldest.plus(Duration.ofMinutes(10)));

        mvc.perform(post("/api/v1/ai/events/concept")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                fx.principal(owner), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "vibe", "Moody Berlin techno warehouse vibe",
                                "genre", "Techno", "city", "Berlin"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AI_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.error.fields.limit").value("2"))
                .andExpect(jsonPath("$.error.fields.used").value("2"))
                .andExpect(jsonPath("$.error.fields.resetAt").value(oldest.plus(Duration.ofHours(24)).toString()));

        verifyNoInteractions(ideogram, chatClient);
    }

    private void seedImageUsage(User user, Instant at) {
        AiGenerationUsage u = new AiGenerationUsage();
        u.setUserId(user.getId());
        u.setOrgId(user.getOrgId());
        u.setKind("image");
        u.setCreatedAt(at);
        usageRepo.save(u);
    }
}
