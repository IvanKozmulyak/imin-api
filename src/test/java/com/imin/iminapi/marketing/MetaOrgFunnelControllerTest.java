package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.hamcrest.Matchers;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP wiring + JSON shape for GET /api/v1/marketing/meta/funnel (spec §8). Org is
 * resolved from the auth context; an org with no data returns a well-formed
 * all-zeros funnel (not an error, not null imin counts).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class MetaOrgFunnelControllerTest {

    @Autowired MockMvc mvc;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

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
    void funnelReturnsThreeMappedStagesForEmptyOrg() throws Exception {
        mvc.perform(get("/api/v1/marketing/meta/funnel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(30))
                .andExpect(jsonPath("$.stages.length()").value(3))
                // 3 -> 3 mapping, in order
                .andExpect(jsonPath("$.stages[0].metaEvent").value("PageView"))
                .andExpect(jsonPath("$.stages[0].iminStage").value("PAGE_VIEW"))
                .andExpect(jsonPath("$.stages[1].metaEvent").value("InitiateCheckout"))
                .andExpect(jsonPath("$.stages[1].iminStage").value("CHECKOUT_START"))
                .andExpect(jsonPath("$.stages[2].metaEvent").value("Purchase"))
                .andExpect(jsonPath("$.stages[2].iminStage").value("PAYMENTS_COMPLETED"))
                // imin-side counts are real zeros, never null
                .andExpect(jsonPath("$.stages[0].iminCount").value(0))
                .andExpect(jsonPath("$.stages[2].iminCount").value(0))
                // Meta-side: null for the browser-only stages, real 0 for Purchase
                .andExpect(jsonPath("$.stages[0].metaReceivedCount").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.stages[1].metaReceivedCount").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.stages[2].metaReceivedCount").value(0));
    }
}
