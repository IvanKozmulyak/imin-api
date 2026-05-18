package com.imin.iminapi.controller.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.event.TicketTierDto;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.event.TicketTierService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class EventTierControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean TicketTierService tierService;

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

    private TicketTierDto sampleDto(UUID eventId, UUID tierId) {
        return new TicketTierDto(
                tierId, eventId, "GA", "standard",
                1000, 100, 0, 0, null, null, true, 0);
    }

    // ── POST ───────────────────────────────────────────────────────────────────

    @Test
    @WithStubUser
    void post_creates_tier_returns_201_with_Location_header() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        when(tierService.create(any(), eq(eventId), any())).thenReturn(sampleDto(eventId, tierId));

        mvc.perform(post("/api/v1/events/" + eventId + "/tiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", "GA",
                                "kind", "standard",
                                "priceMinor", 1000,
                                "quantity", 100))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/v1/events/" + eventId + "/tiers/" + tierId))
                .andExpect(jsonPath("$.id").value(tierId.toString()))
                .andExpect(jsonPath("$.kind").value("standard"));
    }

    @Test
    @WithStubUser
    void post_returns_400_when_service_throws_INVALID_REQUEST() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(tierService.create(any(), eq(eventId), any())).thenThrow(
                new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                        "Invalid tier data", Map.of("name", "required")));

        mvc.perform(post("/api/v1/events/" + eventId + "/tiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields.name").value("required"));
    }

    // ── PATCH ──────────────────────────────────────────────────────────────────

    @Test
    @WithStubUser
    void patch_returns_200_with_body() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        TicketTierDto updated = new TicketTierDto(
                tierId, eventId, "VIP", "standard",
                2000, 50, 0, 0, null, null, true, 0);
        when(tierService.patch(any(), eq(eventId), eq(tierId), any())).thenReturn(updated);

        mvc.perform(patch("/api/v1/events/" + eventId + "/tiers/" + tierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "VIP", "priceMinor", 2000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("VIP"))
                .andExpect(jsonPath("$.priceMinor").value(2000));
    }

    // ── DELETE ─────────────────────────────────────────────────────────────────

    @Test
    @WithStubUser
    void delete_returns_204() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();

        mvc.perform(delete("/api/v1/events/" + eventId + "/tiers/" + tierId))
                .andExpect(status().isNoContent());
    }

    // ── auth ───────────────────────────────────────────────────────────────────

    @Test
    void getEndpoints_reject_unauthenticated_requests_with_401() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();

        mvc.perform(post("/api/v1/events/" + eventId + "/tiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(patch("/api/v1/events/" + eventId + "/tiers/" + tierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/v1/events/" + eventId + "/tiers/" + tierId))
                .andExpect(status().isUnauthorized());
    }
}
