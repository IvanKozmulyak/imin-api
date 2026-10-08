package com.imin.iminapi.controller.me;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Notification preferences are per user: a teammate's PATCH never changes anyone else's row. */
@IminIntegrationTest
class MeControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired UserRepository users;
    @Autowired NotificationPreferencesRepository prefs;

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    @Test
    void a_users_prefs_patch_changes_only_that_users_row() throws Exception {
        Organization org = fx.org();
        User a = fx.owner(org);
        User b = new User();
        b.setOrgId(org.getId());
        b.setEmail(fx.email("teammate"));
        b.setRole(UserRole.MEMBER);
        b = users.save(b);
        mvc.perform(get("/api/v1/me/notifications").with(as(b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketSold").value(true));

        mvc.perform(patch("/api/v1/me/notifications").with(as(a))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ticketSold\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(a.getId().toString()))
                .andExpect(jsonPath("$.ticketSold").value(false));

        assertThat(prefs.findById(a.getId()).orElseThrow().isTicketSold()).isFalse();
        assertThat(prefs.findById(b.getId()).orElseThrow().isTicketSold()).isTrue();
        mvc.perform(get("/api/v1/me/notifications").with(as(b)))
                .andExpect(jsonPath("$.userId").value(b.getId().toString()))
                .andExpect(jsonPath("$.ticketSold").value(true));
    }

    @Test
    void a_profile_patch_changes_only_the_callers_name() throws Exception {
        Organization org = fx.org();
        User a = fx.owner(org);
        User b = new User();
        b.setOrgId(org.getId());
        b.setEmail(fx.email("teammate"));
        b.setRole(UserRole.MEMBER);
        b.setFirstName("Bea");
        b = users.save(b);

        mvc.perform(patch("/api/v1/me/profile").with(as(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Grace\",\"lastName\":\"Hopper\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(a.getId().toString()))
                .andExpect(jsonPath("$.user.firstName").value("Grace"));

        assertThat(users.findById(a.getId()).orElseThrow().getFirstName()).isEqualTo("Grace");
        assertThat(users.findById(b.getId()).orElseThrow().getFirstName()).isEqualTo("Bea");
    }
}
