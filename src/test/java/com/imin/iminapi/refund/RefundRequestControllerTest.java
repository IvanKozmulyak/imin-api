package com.imin.iminapi.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.dto.RefundRequestDecisionResponse;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class RefundRequestControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    @MockitoBean RefundRequestService service;

    AuthPrincipal principalFor(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    Authentication auth(AuthPrincipal p) {
        return new UsernamePasswordAuthenticationToken(p, "n/a", List.of());
    }

    @Test
    void list_forbids_cross_org_access() throws Exception {
        UUID orgInPath = UUID.randomUUID();
        AuthPrincipal mine = principalFor(UUID.randomUUID());
        mvc.perform(get("/api/v1/orgs/{orgId}/refund-requests", orgInPath)
                .with(authentication(auth(mine))))
            .andExpect(status().isNotFound());
    }

    @Test
    void list_returns_rows_for_own_org() throws Exception {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal me = principalFor(orgId);
        when(service.listRequests(eq(orgId), any(), any(), any(), anyInt()))
            .thenReturn(List.of());
        mvc.perform(get("/api/v1/orgs/{orgId}/refund-requests", orgId)
                .with(authentication(auth(me))))
            .andExpect(status().isOk());
    }

    @Test
    void approve_passes_body_through() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID rid = UUID.randomUUID();
        AuthPrincipal me = principalFor(orgId);
        when(service.approveRequest(eq(rid), any(), any()))
            .thenReturn(new RefundRequestDecisionResponse(
                "approved", UUID.randomUUID(), "pending"));

        mvc.perform(post("/api/v1/orgs/{orgId}/refund-requests/{id}/approve", orgId, rid)
                .with(authentication(auth(me)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true,\"note\":\"ok\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("approved"));
    }
}
