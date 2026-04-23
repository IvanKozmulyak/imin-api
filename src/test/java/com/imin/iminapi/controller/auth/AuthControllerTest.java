package com.imin.iminapi.controller.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.UserDto;
import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.dto.auth.LoginRequest;
import com.imin.iminapi.dto.auth.MeResponse;
import com.imin.iminapi.dto.auth.SignupRequest;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.*;
import com.imin.iminapi.service.auth.AuthService;
import com.imin.iminapi.web.IdempotencyKeySupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class AuthControllerTest {

    @Autowired MockMvc mvc;
    // Use local ObjectMapper to avoid requiring the Spring-managed one in test context
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean AuthService authService;

    private UserDto sampleUser(UUID orgId) {
        return new UserDto(UUID.randomUUID(), "ada@example.com", "", "owner", "AD", orgId, Instant.parse("2026-04-23T10:00:00Z"));
    }
    private OrganizationDto sampleOrg(UUID orgId) {
        return new OrganizationDto(orgId, "Ada Co", "ada@example.com", "GB", "UTC", "growth", 89, "EUR");
    }

    @Test
    void signup_returns_token_user_org() throws Exception {
        UUID orgId = UUID.randomUUID();
        when(authService.signup(any(SignupRequest.class)))
                .thenReturn(new AuthResponse("tok-abc", sampleUser(orgId), sampleOrg(orgId)));

        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "email", "ada@example.com",
                                "password", "lovelace12",
                                "orgName", "Ada Co",
                                "country", "GB"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("tok-abc"))
                .andExpect(jsonPath("$.user.email").value("ada@example.com"))
                .andExpect(jsonPath("$.org.name").value("Ada Co"));
    }

    @Test
    void signup_short_password_returns_FIELD_INVALID_with_password_field() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "email", "a@b.com",
                                "password", "short",
                                "orgName", "X",
                                "country", "GB"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.passwordPolicyValid").exists());
    }

    @Test
    void signup_duplicate_email_returns_DUPLICATE() throws Exception {
        when(authService.signup(any(SignupRequest.class)))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                        ErrorCode.DUPLICATE, "Email already registered",
                        Map.of("email", "already registered")));
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "email", "dupe@example.com",
                                "password", "valid12345",
                                "orgName", "X",
                                "country", "FR"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.error.fields.email").value("already registered"));
    }

    @Test
    void login_invalid_returns_AUTH_INVALID_CREDENTIALS() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                        ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials"));
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "x@y.com", "password", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void me_without_token_returns_AUTH_MISSING() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
    }
}
