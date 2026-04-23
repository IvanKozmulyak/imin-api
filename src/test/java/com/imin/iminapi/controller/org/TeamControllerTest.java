package com.imin.iminapi.controller.org;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.org.InviteResponse;
import com.imin.iminapi.dto.org.TeamMemberDto;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.org.TeamService;
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
class TeamControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean TeamService teamService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000020");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000021");

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
    @WithStubUser
    void list_returns_members() throws Exception {
        TeamMemberDto member = new TeamMemberDto(USER, "test@example.com", "Test",
                "owner", "TE", ORG, Instant.parse("2026-04-23T10:00:00Z"), null);
        when(teamService.list(any(AuthPrincipal.class))).thenReturn(List.of(member));
        mvc.perform(get("/api/v1/org/team"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("test@example.com"));
    }

    @Test
    @WithStubUser
    void invite_returns_inviteId() throws Exception {
        UUID inviteId = UUID.randomUUID();
        when(teamService.invite(any(AuthPrincipal.class), any()))
                .thenReturn(new InviteResponse(inviteId, "newmember@example.com", "admin"));
        mvc.perform(post("/api/v1/org/team/invite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "newmember@example.com", "role", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteId").value(inviteId.toString()));
    }

    @Test
    @WithStubUser
    void remove_returns_204() throws Exception {
        UUID targetId = UUID.randomUUID();
        mvc.perform(delete("/api/v1/org/team/" + targetId))
                .andExpect(status().isNoContent());
        verify(teamService).remove(any(AuthPrincipal.class), eq(targetId));
    }
}
