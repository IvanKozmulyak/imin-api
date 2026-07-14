package com.imin.iminapi.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class MetaConnectionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired MetaPixelConnectionRepository connRepo;
    // No autowireable ObjectMapper bean in this context — construct one locally,
    // matching CampaignControllerTest (the sibling marketing web test).
    final ObjectMapper json = new ObjectMapper();

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    // Test-local auth stub — same pattern as RefundControllerTest:52-65 (there is NO
    // shared WithStubOrganizer annotation; each web test defines its own).
    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubOrganizer {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubOrganizer> {
        @Override public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubOrganizer ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @Test
    @WithStubOrganizer
    void getReturnsNotConnectedInitially() throws Exception {
        mvc.perform(get("/api/v1/marketing/meta/connection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    @WithStubOrganizer
    void putConnectsAndNeverReturnsToken() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "pixelId", "1234567890",
                "capiAccessToken", "EAAG-super-secret",
                "testEventCode", "TEST123"));
        mvc.perform(put("/api/v1/marketing/meta/connection")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.pixelId").value("1234567890"))
                .andExpect(jsonPath("$.hasToken").value(true))
                // the plaintext token must NEVER appear in the response body
                .andExpect(jsonPath("$.capiAccessToken").doesNotExist())
                .andExpect(jsonPath("$.capiAccessTokenEnc").doesNotExist());
    }

    @Test
    @WithStubOrganizer
    void putRequiresTokenOnFirstConnect() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("pixelId", "1234567890"));
        mvc.perform(put("/api/v1/marketing/meta/connection")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithStubOrganizer
    void deleteRemovesConnection() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "pixelId", "999", "capiAccessToken", "tok"));
        mvc.perform(put("/api/v1/marketing/meta/connection")
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/marketing/meta/connection")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/marketing/meta/connection"))
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    @WithStubOrganizer
    void statsReturnsZeroesInitially() throws Exception {
        mvc.perform(get("/api/v1/marketing/meta/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent24h").value(0))
                .andExpect(jsonPath("$.dead").value(0));
    }
}
