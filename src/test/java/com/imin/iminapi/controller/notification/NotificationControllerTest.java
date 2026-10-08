package com.imin.iminapi.controller.notification;

import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The notification bell is per user: a teammate never sees, counts or clears another user's notifications. */
@IminIntegrationTest
class NotificationControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired UserRepository users;
    @Autowired NotificationRepository notifications;

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    @Test
    void a_user_sees_none_of_a_teammates_notifications() throws Exception {
        Organization org = fx.org();
        User a = fx.owner(org);
        User b = new User();
        b.setOrgId(org.getId());
        b.setEmail(fx.email("teammate"));
        b.setRole(UserRole.MEMBER);
        b = users.save(b);
        Notification forA = new Notification();
        forA.setUserId(a.getId());
        forA.setKind("momentum_suggestion");
        forA.setTitle("for A only");
        forA = notifications.save(forA);

        mvc.perform(get("/api/v1/notifications/unread-count").with(as(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/api/v1/notifications").with(as(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/notifications/read-all").with(as(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        assertThat(notifications.findById(forA.getId()).orElseThrow().getReadAt()).isNull();
        mvc.perform(get("/api/v1/notifications/unread-count").with(as(a)))
                .andExpect(jsonPath("$.count").value(1));
        mvc.perform(get("/api/v1/notifications").with(as(a)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("for A only"));

        mvc.perform(post("/api/v1/notifications/read-all").with(as(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
        assertThat(notifications.findById(forA.getId()).orElseThrow().getReadAt()).isNotNull();
        mvc.perform(get("/api/v1/notifications/unread-count").with(as(a)))
                .andExpect(jsonPath("$.count").value(0));
    }
}
