package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckFindingRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorJobRepository;
import com.imin.iminapi.predictor.research.ResearchLlmClient;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Research switched on with the org list left empty, as in production until an org is added: nobody researches. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = {"imin.predictor.date-check.enabled=true",
        "imin.predictor.date-check.all-orgs=true",
        "imin.predictor.date-check.research-enabled=true",
        "imin.predictor.date-check.research-org-ids="})
class DateCheckResearchListEmptyTest {

    @MockitoBean ResearchLlmClient client;

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;
    @Autowired DateCheckFindingRepository findings;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired PredictorJobRepository jobs;

    private Authentication auth;

    @BeforeEach
    void seed() {
        clean();
        Organization o = new Organization();
        o.setName("No Research Org");
        o.setSlug("nr-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("nr@example.test");
        o.setCountry("FR");
        o = orgs.save(o);
        User u = new User();
        u.setOrgId(o.getId());
        u.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        u.setRole(UserRole.OWNER);
        u = users.save(u);
        auth = new UsernamePasswordAuthenticationToken(new AuthPrincipal(u.getId(), o.getId(), UserRole.OWNER,
                UUID.randomUUID()), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    @AfterEach
    void clean() {
        findings.deleteAll();
        checkDates.deleteAll();
        ledger.deleteAll();
        jobs.deleteAll();
        checks.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    @Test
    void emptyResearchListMeansNobodyResearches() throws Exception {
        mvc.perform(get("/api/v1/predictions/date-checks/config").with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.researchAvailable").value(false));

        Map<String, Object> body = Map.of("city", "Paris", "genreFamily", "house & techno",
                "dates", List.of(LocalDate.now().plusDays(30).toString()), "research", true);
        String json = mvc.perform(post("/api/v1/predictions/date-checks").with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode r = new ObjectMapper().readTree(json);

        assertThat(r.get("research").asBoolean()).isFalse();
        assertThat(r.get("researchStatus").asText()).isEqualTo("off");
        assertThat(jobs.count()).isZero();
        verify(client, never()).research(anyString(), anyString(), anyString());
    }
}
