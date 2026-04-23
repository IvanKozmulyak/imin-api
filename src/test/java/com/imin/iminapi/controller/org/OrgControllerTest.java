package com.imin.iminapi.controller.org;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.org.OrgService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class OrgControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean OrgService orgService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubAuthFactory.class)
    public @interface WithStubUser {}

    public static class StubAuthFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override
        public SecurityContext createSecurityContext(WithStubUser annotation) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    private OrganizationDto sampleOrg() {
        return new OrganizationDto(ORG, "Test Org", "test@example.com", "DE",
                "Europe/Berlin", "growth", 89, "EUR", Instant.parse("2026-04-23T10:00:00Z"));
    }

    @Test
    void get_without_token_returns_AUTH_MISSING() throws Exception {
        mvc.perform(get("/api/v1/org"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
    }

    @Test
    @WithStubUser
    void get_returns_org() throws Exception {
        when(orgService.get(any(AuthPrincipal.class))).thenReturn(sampleOrg());
        mvc.perform(get("/api/v1/org"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORG.toString()))
                .andExpect(jsonPath("$.name").value("Test Org"));
    }

    @Test
    @WithStubUser
    void patch_returns_updated() throws Exception {
        OrganizationDto updated = new OrganizationDto(ORG, "Updated Org", "test@example.com", "DE",
                "Europe/Berlin", "growth", 89, "EUR", Instant.parse("2026-04-23T11:00:00Z"));
        when(orgService.patch(any(AuthPrincipal.class), any(), any())).thenReturn(updated);
        mvc.perform(patch("/api/v1/org")
                        .header("If-Match", "\"2026-04-23T10:00:00Z\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Updated Org"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Org"));
    }

    @Test
    @WithStubUser
    void delete_returns_204() throws Exception {
        mvc.perform(delete("/api/v1/org"))
                .andExpect(status().isNoContent());
        verify(orgService).delete(any(AuthPrincipal.class));
    }
}
