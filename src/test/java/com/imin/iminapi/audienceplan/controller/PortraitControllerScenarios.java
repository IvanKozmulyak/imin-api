package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/audience/portrait} over the committed open-data seed rows, on H2 and on Postgres 17. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
abstract class PortraitControllerScenarios {

    private static final String URL = "/api/v1/audience/portrait";

    @Autowired MockMvc mvc;
    @Autowired AudiencePlanProperties props;

    private final AuthPrincipal memberA = principal();
    private final AuthPrincipal memberB = principal();

    @AfterEach
    void tearDown() {
        props.setEnabled(true);
    }

    @Test
    void metzHouse_returnsTheOpenDataPortrait() throws Exception {
        portrait(memberA, "house & techno", "Metz").andExpect(status().isOk())
                .andExpect(jsonPath("$.genre").value("house & techno"))
                .andExpect(jsonPath("$.cityKey").value("metz"))
                .andExpect(jsonPath("$.catchment.radiusKm").value(70))
                .andExpect(jsonPath("$.catchment.scope").value("fr_catchment"))
                .andExpect(jsonPath("$.catchment.towns.length()").value(5))
                .andExpect(jsonPath("$.catchment.towns[3].cityKey").value("luxembourg"))
                .andExpect(jsonPath("$.catchment.towns[3].inScope").value(false))
                .andExpect(jsonPath("$.groups.length()").value(3))
                .andExpect(jsonPath("$.groups[0].key").value("genre_first"))
                .andExpect(jsonPath("$.groups[0].origin").value("open_data"))
                .andExpect(jsonPath("$.groups[0].kind").value("audience"))
                .andExpect(jsonPath("$.groups[0].size.low").value(3180))
                .andExpect(jsonPath("$.groups[0].size.high").value(4172))
                .andExpect(jsonPath("$.groups[0].method").value("electronic_first"))
                .andExpect(jsonPath("$.groups[0].sources[0].label").value("Source : Insee, recensement de la population"))
                .andExpect(jsonPath("$.groups[0].sources[0].period").value("2023"))
                .andExpect(jsonPath("$.groups[0].sources[0].updatedAt").value("2026-09-27T00:00:00Z"))
                .andExpect(jsonPath("$.groups[0].sources[5].label").value("Ekhoscènes"))
                .andExpect(jsonPath("$.groups[0].sources[5].licence").value(nullValue()))
                .andExpect(jsonPath("$.groups[1].size.low").value(1113))
                .andExpect(jsonPath("$.groups[1].size.high").value(1460))
                .andExpect(jsonPath("$.groups[2].key").value("students"))
                .andExpect(jsonPath("$.groups[2].kind").value("context"))
                .andExpect(jsonPath("$.groups[2].size.low").value(52096))
                .andExpect(jsonPath("$.groups[2].size.high").value(52096))
                .andExpect(jsonPath("$.versions.priors").value(1));
    }

    @Test
    void unknownCity_isNullSizes_notZero() throws Exception {
        portrait(memberA, "house & techno", "Lyon").andExpect(status().isOk())
                .andExpect(jsonPath("$.catchment").value(nullValue()))
                .andExpect(jsonPath("$.groups[0].size").value(nullValue()))
                .andExpect(jsonPath("$.groups[1].size").value(nullValue()))
                .andExpect(jsonPath("$.groups[2].size").value(nullValue()));
    }

    @Test
    void genreOutsideTheBuckets_is400() throws Exception {
        portrait(memberA, "techno", "metz").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.genre").exists());
    }

    @Test
    void missingGenre_is400() throws Exception {
        portrait(memberA, null, "metz").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.genre").exists());
    }

    @Test
    void missingCity_is400() throws Exception {
        portrait(memberA, "house & techno", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields.city").exists());
    }

    @Test
    void killSwitchOff_is404() throws Exception {
        props.setEnabled(false);

        portrait(memberA, "house & techno", "metz").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void twoOrgs_getTheSamePortrait_itHoldsNoOrgData() throws Exception {
        String a = portrait(memberA, "house & techno", "metz").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String b = portrait(memberB, "house & techno", "metz").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(b).isEqualTo(a);
        assertThat(a).doesNotContain(memberA.orgId().toString()).doesNotContain(memberB.orgId().toString());
    }

    private ResultActions portrait(AuthPrincipal p, String genre, String city) throws Exception {
        MockHttpServletRequestBuilder req = get(URL).with(auth(p));
        if (genre != null) req = req.param("genre", genre);
        if (city != null) req = req.param("city", city);
        return mvc.perform(req);
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.MEMBER, UUID.randomUUID());
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
