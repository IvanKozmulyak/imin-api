package com.imin.iminapi.controller.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.gate.GateLoginResponse;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.gate.GateAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class GateLoginControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean GateAuthService gateAuthService;
    final ObjectMapper om = new ObjectMapper();

    @Test
    void login_success_returns_token_and_org_info() throws Exception {
        UUID orgId = UUID.randomUUID();
        Instant expires = Instant.parse("2027-01-01T00:00:00Z");
        when(gateAuthService.login(any())).thenReturn(
                new GateLoginResponse("opaque-bearer-token", orgId, "Acme Co", expires));

        mvc.perform(post("/api/v1/gate/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                Map.of("orgSlug", "acme", "password", "correct-password-123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("opaque-bearer-token"))
                .andExpect(jsonPath("$.orgId").value(orgId.toString()))
                .andExpect(jsonPath("$.orgName").value("Acme Co"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void login_invalid_credentials_returns_401_with_generic_envelope() throws Exception {
        when(gateAuthService.login(any())).thenThrow(new ApiException(
                HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials"));

        mvc.perform(post("/api/v1/gate/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(
                                Map.of("orgSlug", "anything", "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"))
                // Generic message — never reveals whether the slug existed.
                .andExpect(jsonPath("$.error.message").value("Invalid credentials"));
    }

    @Test
    void login_validation_blank_fields_returns_400() throws Exception {
        mvc.perform(post("/api/v1/gate/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgSlug\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }

    @Test
    void login_endpoint_is_public_no_auth_header_needed() throws Exception {
        // Same as the success case but explicitly assert that NO Authorization
        // header is required (regression guard against the route accidentally
        // moving into the authenticated subtree).
        UUID orgId = UUID.randomUUID();
        when(gateAuthService.login(any())).thenReturn(
                new GateLoginResponse("tok", orgId, "Acme", Instant.now().plusSeconds(3600)));

        mvc.perform(post("/api/v1/gate/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgSlug\":\"acme\",\"password\":\"abcdefghijkl\"}"))
                .andExpect(status().isOk());
    }
}
