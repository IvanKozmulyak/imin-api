package com.imin.iminapi.controller.analytics;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.analytics.AttributionResponse;
import com.imin.iminapi.dto.analytics.UntaggedLinksResponse;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.analytics.AttributionService;
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Endpoint-shape test: locks the exact JSON the frontend consumes for both
 * attribution read-models, and confirms the routes are wired + reachable under
 * the {@code /api/v1/**} authenticated rule. The service is mocked so this is a
 * pure HTTP/JSON contract test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class AttributionControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AttributionService service;

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
    void attribution_json_shape() throws Exception {
        // Two-arg overload: the controller now forwards an optional client slice.
        when(service.attribution(any(), any())).thenReturn(new AttributionResponse(
                10000L, 20, 50,
                List.of(new AttributionResponse.Channel("instagram", 7500L, 3))));

        mvc.perform(get("/api/v1/analytics/attribution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributedRevenueMinor").value(10000))
                .andExpect(jsonPath("$.untaggedPct").value(20))
                .andExpect(jsonPath("$.repeatBuyerPct").value(50))
                .andExpect(jsonPath("$.channels.length()").value(1))
                .andExpect(jsonPath("$.channels[0].source").value("instagram"))
                .andExpect(jsonPath("$.channels[0].revenueMinor").value(7500))
                .andExpect(jsonPath("$.channels[0].visits").value(3));
    }

    /**
     * The client slice reaches the service. Without this the query param would
     * bind and be silently dropped, and the endpoint would answer web+app
     * totals to a caller who asked for one of them.
     */
    @Test
    @WithStubUser
    void the_client_query_param_is_forwarded() throws Exception {
        when(service.attribution(any(), any())).thenReturn(
                new AttributionResponse(0L, 0, 0, List.of()));

        mvc.perform(get("/api/v1/analytics/attribution?client=ios"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(service)
                .attribution(any(), org.mockito.ArgumentMatchers.eq("ios"));
    }

    @Test
    @WithStubUser
    void untagged_json_shape() throws Exception {
        when(service.untagged(any())).thenReturn(new UntaggedLinksResponse(
                List.of(new UntaggedLinksResponse.Link(
                        "instagram.com", null, 2,
                        new UntaggedLinksResponse.Suggested("instagram", "social", "")))));

        mvc.perform(get("/api/v1/analytics/untagged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.length()").value(1))
                .andExpect(jsonPath("$.links[0].referrerHost").value("instagram.com"))
                // sampleUrl is reserved (string|null) — serialized as null today (Jackson default inclusion)
                .andExpect(jsonPath("$.links[0].sampleUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.links[0].visits").value(2))
                .andExpect(jsonPath("$.links[0].suggested.source").value("instagram"))
                .andExpect(jsonPath("$.links[0].suggested.medium").value("social"))
                .andExpect(jsonPath("$.links[0].suggested.campaign").value(""));
    }

    @Test
    void unauthenticated_is_rejected() throws Exception {
        mvc.perform(get("/api/v1/analytics/attribution"))
                .andExpect(status().is4xxClientError());
    }
}
