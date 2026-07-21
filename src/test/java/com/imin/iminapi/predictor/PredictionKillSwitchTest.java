package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.Stage0Scorer;
import com.imin.iminapi.repository.AiGenerationUsageRepository;
import com.imin.iminapi.repository.EventRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Global kill switch (spec §5, task 86cav477c): with {@code imin.predictor.benchmark-only=true}
 * every scoring request returns benchmark-only — no LLM call, no quota burn — and the result
 * is STILL ledgered (write-before-render survives the floor mode).
 */
@SpringBootTest(properties = "imin.predictor.benchmark-only=true")
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class PredictionKillSwitchTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired PredictionLedgerRepository ledger;
    @Autowired AiGenerationUsageRepository usage;

    @MockitoBean Stage0Scorer scorer;

    final ObjectMapper om = new ObjectMapper();

    private Organization org;
    private User owner;
    private Event event;

    @BeforeEach
    void seed() {
        clean();
        org = new Organization();
        org.setName("Kill Switch Org");
        org.setSlug("kill-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("k@example.test");
        org.setCountry("NL");
        org = orgs.save(org);
        owner = new User();
        owner.setOrgId(org.getId());
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setCreatedBy(owner.getId());
        e.setName("Dark Mode Night");
        e.setSlug("ks-" + UUID.randomUUID().toString().substring(0, 8));
        e.setGenre("techno");
        e.setVenueCity("Amsterdam");
        e.setVenueCountry("NL");
        e.setStartsAt(Instant.now().plusSeconds(30L * 86400));
        event = events.save(e);
        when(scorer.modelId()).thenReturn("test/model");
    }

    @AfterEach
    void after() { clean(); }

    private void clean() {
        ledger.deleteAll();
        usage.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    @Test
    void killSwitchServesBenchmarkOnlyWithoutLlmOrQuota() throws Exception {
        AuthPrincipal p = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
        Authentication a = new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")));

        mvc.perform(post("/api/v1/events/" + event.getId() + "/prediction").with(authentication(a)))
                .andExpect(status().isAccepted());

        JsonNode body = null;
        for (int i = 0; i < 100; i++) {
            MvcResult res = mvc.perform(get("/api/v1/events/" + event.getId() + "/prediction")
                            .with(authentication(a)))
                    .andExpect(status().isOk()).andReturn();
            body = om.readTree(res.getResponse().getContentAsString());
            if (!"pending".equals(body.get("status").asText())) break;
            Thread.sleep(50);
        }

        assertThat(body.get("status").asText()).isEqualTo("failed_benchmark_only");
        JsonNode result = body.get("result");
        assertThat(result.get("benchmarkOnly").asBoolean()).isTrue();
        assertThat(result.has("selloutBand")).isFalse();      // NO forward numbers
        assertThat(result.has("attendanceRange")).isFalse();
        assertThat(result.has("revenueRangeMinor")).isFalse();
        assertThat(result.get("comparables")).isNotNull();    // corpus stats still served

        verify(scorer, never()).score(any(), any(), any());   // no LLM call
        assertThat(usage.count()).isZero();                   // no quota burn
        assertThat(ledger.count()).isEqualTo(1);              // still ledgered
    }
}
