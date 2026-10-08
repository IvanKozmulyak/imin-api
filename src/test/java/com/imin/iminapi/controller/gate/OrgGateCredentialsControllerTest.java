package com.imin.iminapi.controller.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The organizer-side gate credential: user-JWT only, and the door password is 12 to 256 chars. */
@IminIntegrationTest
class OrgGateCredentialsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void an_owner_rotates_the_door_password_and_the_status_reports_it() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        var auth = authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(owner), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        mvc.perform(get("/api/v1/orgs/{orgId}/gate/credentials", org.getId()).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCredential").value(false));

        mvc.perform(post("/api/v1/orgs/{orgId}/gate/credentials/rotate", org.getId()).with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("password", "12-char-min-pw!"))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/orgs/{orgId}/gate/credentials", org.getId()).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCredential").value(true))
                .andExpect(jsonPath("$.lastRotatedAt").isNotEmpty())
                .andExpect(jsonPath("$.username").value(org.getSlug()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "elevenchars"})
    void a_rotate_password_outside_the_bounds_is_FIELD_INVALID(String password) throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);

        mvc.perform(post("/api/v1/orgs/{orgId}/gate/credentials/rotate", org.getId())
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                fx.principal(owner), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("password", password))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.password").exists());
    }
}
