package com.imin.iminapi.controller.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.gate.GateCredentialStatusResponse;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.gate.GateAuthService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class OrgGateCredentialsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean GateAuthService gateAuthService;
    final ObjectMapper om = new ObjectMapper();

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000050");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000051");

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

    @Test
    void status_without_auth_returns_401() throws Exception {
        mvc.perform(get("/api/v1/orgs/" + ORG + "/gate/credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
    }

    @Test
    @WithStubUser
    void status_returns_credential_summary() throws Exception {
        when(gateAuthService.status(any(AuthPrincipal.class), eq(ORG)))
                .thenReturn(new GateCredentialStatusResponse(
                        true, Instant.parse("2026-05-01T10:00:00Z"), "acme"));

        mvc.perform(get("/api/v1/orgs/" + ORG + "/gate/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCredential").value(true))
                .andExpect(jsonPath("$.lastRotatedAt").value("2026-05-01T10:00:00Z"))
                .andExpect(jsonPath("$.username").value("acme"));
    }

    @Test
    @WithStubUser
    void status_with_no_credential_yet_returns_hasCredential_false() throws Exception {
        when(gateAuthService.status(any(AuthPrincipal.class), eq(ORG)))
                .thenReturn(new GateCredentialStatusResponse(false, null, "acme"));

        mvc.perform(get("/api/v1/orgs/" + ORG + "/gate/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCredential").value(false))
                .andExpect(jsonPath("$.lastRotatedAt").doesNotExist())
                .andExpect(jsonPath("$.username").value("acme"));
    }

    @Test
    @WithStubUser
    void rotate_with_long_password_returns_204() throws Exception {
        mvc.perform(post("/api/v1/orgs/" + ORG + "/gate/credentials/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("password", "12-char-min-pw!"))))
                .andExpect(status().isNoContent());
        verify(gateAuthService).rotate(any(AuthPrincipal.class), eq(ORG), eq("12-char-min-pw!"));
    }

    @Test
    @WithStubUser
    void rotate_with_short_password_returns_400() throws Exception {
        mvc.perform(post("/api/v1/orgs/" + ORG + "/gate/credentials/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("password", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.password").exists());
    }

    @Test
    @WithStubUser
    void rotate_with_blank_password_returns_400() throws Exception {
        mvc.perform(post("/api/v1/orgs/" + ORG + "/gate/credentials/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }
}
