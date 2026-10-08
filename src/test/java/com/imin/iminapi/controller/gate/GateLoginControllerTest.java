package com.imin.iminapi.controller.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.service.gate.GateAuthService;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The door scanner's public login over the real GateAuthService. */
@IminIntegrationTest
class GateLoginControllerTest {

    private static final String PASSWORD = "correct-password-123";

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired GateAuthService gateAuth;

    private final ObjectMapper om = new ObjectMapper();

    private Organization orgWithGatePassword() {
        Organization org = fx.org();
        gateAuth.rotate(fx.principal(fx.owner(org)), org.getId(), PASSWORD);
        return org;
    }

    @Test
    void the_login_is_public_and_returns_a_token_for_the_org() throws Exception {
        Organization org = orgWithGatePassword();

        mvc.perform(post("/api/v1/gate/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("orgSlug", org.getSlug(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.orgId").value(org.getId().toString()))
                .andExpect(jsonPath("$.orgName").value(org.getName()))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    /** A wrong password and an unknown org answer identically, so the login never confirms a slug exists. */
    @ParameterizedTest
    @ValueSource(strings = {"wrong-password", "unknown-org"})
    void a_bad_login_is_the_same_generic_401(String kind) throws Exception {
        String slug = kind.equals("unknown-org") ? "no-such-org-" + UUID.randomUUID() : orgWithGatePassword().getSlug();

        mvc.perform(post("/api/v1/gate/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("orgSlug", slug, "password", "wrong-password-123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.message").value("Invalid credentials"));
    }
}
