package com.imin.iminapi.controller.me;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.NotificationPreferencesDto;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.me.NotificationPrefsService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class MeControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean NotificationPrefsService notificationPrefsService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000030");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000031");

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

    private NotificationPreferencesDto samplePrefs() {
        return new NotificationPreferencesDto(USER, true, false, true, false, true, false, true);
    }

    @Test
    @WithStubUser
    void get_returns_prefs() throws Exception {
        when(notificationPrefsService.get(any(AuthPrincipal.class))).thenReturn(samplePrefs());
        mvc.perform(get("/api/v1/me/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER.toString()))
                .andExpect(jsonPath("$.ticketSold").value(true));
    }

    @Test
    @WithStubUser
    void patch_returns_updated_prefs() throws Exception {
        NotificationPreferencesDto updated = new NotificationPreferencesDto(USER, false, false, false, false, false, false, false);
        when(notificationPrefsService.patch(any(AuthPrincipal.class), any())).thenReturn(updated);
        mvc.perform(patch("/api/v1/me/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("ticketSold", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketSold").value(false));
    }
}
