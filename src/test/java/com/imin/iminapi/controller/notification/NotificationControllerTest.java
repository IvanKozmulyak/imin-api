package com.imin.iminapi.controller.notification;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.imin.iminapi.model.Notification;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class NotificationControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean NotificationRepository repo;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubUser {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubUser ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @Test
    @WithStubUser
    void unread_count_returns_zero_when_empty() throws Exception {
        when(repo.countByUserIdAndReadAtIsNull(USER)).thenReturn(0L);
        mvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @WithStubUser
    void unread_count_returns_value() throws Exception {
        when(repo.countByUserIdAndReadAtIsNull(USER)).thenReturn(3L);
        mvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    private Notification make(String title) {
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setUserId(USER);
        n.setKind("momentum_suggestion");
        n.setTitle(title);
        n.setBody("body of " + title);
        n.setLink("/marketing?tab=momentum");
        n.setCreatedAt(Instant.now());
        return n;
    }

    @Test
    @WithStubUser
    void list_returns_user_notifications() throws Exception {
        when(repo.findTop50ByUserIdOrderByCreatedAtDesc(USER))
                .thenReturn(List.of(make("newer"), make("older")));
        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("newer"))
                .andExpect(jsonPath("$[0].kind").value("momentum_suggestion"))
                .andExpect(jsonPath("$[0].link").value("/marketing?tab=momentum"))
                .andExpect(jsonPath("$[0].readAt").isEmpty());
    }

    @Test
    @WithStubUser
    void read_all_marks_unread_and_returns_zero() throws Exception {
        Notification unread = make("mine");
        when(repo.findByUserIdAndReadAtIsNull(USER)).thenReturn(List.of(unread));
        mvc.perform(post("/api/v1/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        org.assertj.core.api.Assertions.assertThat(unread.getReadAt()).isNotNull();
        verify(repo).saveAll(List.of(unread));
    }
}
