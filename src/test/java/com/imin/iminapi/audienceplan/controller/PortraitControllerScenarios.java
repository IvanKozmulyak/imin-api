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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    @Autowired JdbcTemplate jdbc;

    private final AuthPrincipal memberA = principal();
    private final AuthPrincipal memberB = principal();

    @AfterEach
    void tearDown() {
        props.setEnabled(true);
        jdbc.update("DELETE FROM audience_portraits WHERE city_key IN ('nancy', 'thionville')");
    }

    @Test
    void get_recordsTheRequest_andReportsResearchPending() throws Exception {
        jdbc.update("DELETE FROM audience_portraits WHERE city_key = 'nancy'");

        portrait(memberA, "pop", "Nancy").andExpect(status().isOk())
                .andExpect(jsonPath("$.research.status").value("pending"))
                .andExpect(jsonPath("$.research.generatedAt").value(nullValue()))
                .andExpect(jsonPath("$.groups.length()").value(3));

        assertThat(jdbc.queryForObject("SELECT status FROM audience_portraits WHERE genre_key = 'pop' AND city_key = 'nancy'",
                String.class)).isEqualTo("pending");
    }

    @Test
    void readyResearch_isServedAfterTheOpenDataGroups() throws Exception {
        Instant generated = Instant.now().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("""
                INSERT INTO audience_portraits (id, genre_key, city_key, status, research_groups, version, generated_at,
                  expires_at, requested_at, reviewed_by, created_at) VALUES (?, 'pop', 'thionville', 'ready', ?, 1, ?, ?, ?,
                  'ivan', ?)""", UUID.randomUUID(), """
                [{"label":"Thionville students","description":"They meet at the campus bar nights.",
                  "basis":"students","towns":["thionville"],
                  "sources":[{"url":"https://example.org/thionville","title":"Campus nights"}],"confidence":"assumed"}]""",
                Timestamp.from(generated), Timestamp.from(generated.plus(Duration.ofDays(90))), Timestamp.from(generated),
                Timestamp.from(generated));

        portrait(memberA, "pop", "Thionville").andExpect(status().isOk())
                .andExpect(jsonPath("$.research.status").value("ready"))
                .andExpect(jsonPath("$.research.stale").value(false))
                .andExpect(jsonPath("$.research.reviewedBy").value("ivan"))
                .andExpect(jsonPath("$.groups.length()").value(4))
                .andExpect(jsonPath("$.groups[3].key").value("research_1"))
                .andExpect(jsonPath("$.groups[3].origin").value("research"))
                .andExpect(jsonPath("$.groups[3].size.low").value(605))
                .andExpect(jsonPath("$.groups[3].research.label").value("Thionville students"))
                .andExpect(jsonPath("$.groups[3].research.description").value("They meet at the campus bar nights."))
                .andExpect(jsonPath("$.groups[3].research.basis").value("students"))
                .andExpect(jsonPath("$.groups[3].research.confidence").value("assumed"))
                .andExpect(jsonPath("$.groups[3].research.aiDisclosure").value("mode=ai-originated"));
    }

    @Test
    void unknownCity_isNotRecorded() throws Exception {
        portrait(memberA, "pop", "Atlantis").andExpect(status().isOk())
                .andExpect(jsonPath("$.research.status").value("none"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audience_portraits WHERE city_key = 'atlantis'",
                Integer.class)).isZero();
    }

    @Test
    void killSwitchOffOrBadGenre_recordsNothing() throws Exception {
        jdbc.update("DELETE FROM audience_portraits WHERE city_key = 'nancy'");
        props.setEnabled(false);
        portrait(memberA, "pop", "nancy").andExpect(status().isNotFound());
        props.setEnabled(true);
        portrait(memberA, "techno", "nancy").andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audience_portraits WHERE city_key = 'nancy'",
                Integer.class)).isZero();
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
