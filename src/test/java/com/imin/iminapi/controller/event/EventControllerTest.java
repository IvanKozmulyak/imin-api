package com.imin.iminapi.controller.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.*;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.IdempotencyKeyRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.event.EventOverviewService;
import com.imin.iminapi.service.event.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class EventControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean EventService eventService;
    @MockitoBean EventOverviewService overviewService;
    @MockitoBean IdempotencyKeyRepository idempotencyKeyRepository;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubAuthFactory.class)
    public @interface WithStubUser {}

    public static class StubAuthFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override
        public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubUser annotation) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    private EventDto sample() {
        return EventDto.summary(eventEntity());
    }

    private com.imin.iminapi.model.Event eventEntity() {
        com.imin.iminapi.model.Event e = new com.imin.iminapi.model.Event();
        e.setId(UUID.randomUUID()); e.setOrgId(ORG); e.setCreatedBy(USER);
        e.setName("X"); e.setSlug("x"); e.setUpdatedAt(Instant.parse("2026-04-23T10:00:00Z"));
        return e;
    }

    @Test
    @WithStubUser
    void post_events_creates_201() throws Exception {
        when(eventService.createDraft(any(), any())).thenReturn(sample());
        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"));
    }

    @Test
    @WithStubUser
    void get_events_returns_paginated() throws Exception {
        when(eventService.list(any(), eq(null), eq(1), eq(20)))
                .thenReturn(new PageResponse<>(List.of(sample()), 1L, 1, 20));
        mvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @WithStubUser
    void get_events_with_status_filter() throws Exception {
        when(eventService.list(any(), eq(com.imin.iminapi.model.EventStatus.LIVE), eq(1), eq(20)))
                .thenReturn(new PageResponse<>(List.of(), 0L, 1, 20));
        mvc.perform(get("/api/v1/events?status=live"))
                .andExpect(status().isOk());
    }

    @Test
    @WithStubUser
    void patch_event_passes_ifMatch() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.patch(any(), eq(id), eq("\"2026-04-23T10:00:00Z\""), any()))
                .thenReturn(sample());
        mvc.perform(patch("/api/v1/events/" + id)
                        .header("If-Match", "\"2026-04-23T10:00:00Z\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Renamed"))))
                .andExpect(status().isOk());
    }

    @Test
    @WithStubUser
    void publish_without_idempotency_key_returns_400_INVALID_REQUEST() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/events/" + id + "/publish"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithStubUser
    void publish_with_idempotency_key_returns_event() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.publish(any(), eq(id))).thenReturn(sample());
        // Mock idempotency repo so the real IdempotencyKeySupport doesn't hit H2 FK
        when(idempotencyKeyRepository.findByOrgIdAndRouteAndKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(idempotencyKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/v1/events/" + id + "/publish")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }

    @Test
    @WithStubUser
    void overview_returns_metrics() throws Exception {
        UUID id = UUID.randomUUID();
        when(overviewService.overview(any(), eq(id)))
                .thenReturn(new EventOverviewResponse(
                        new EventOverviewResponse.Metrics(0, 100, 0, "EUR", 0, 30),
                        List.of(), null,
                        List.of(new EventOverviewResponse.QuickAction("copy_link", "🔗", "Copy buyer link"))));
        mvc.perform(get("/api/v1/events/" + id + "/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.capacity").value(100))
                .andExpect(jsonPath("$.quickActions[0].key").value("copy_link"));
    }
}
