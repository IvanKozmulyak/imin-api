package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Momentum route wiring and statuses; MomentumServiceTest owns the payloads and effects. */
@IminIntegrationTest
class MomentumControllerTest {

    @Autowired MockMvc mvc;
    @Autowired MomentumSuggestionRepository suggestions;
    @Autowired MomentumTestSupport support;
    @Autowired JdbcTemplate jdbc;

    private UUID org;
    private UUID event;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void seed() {
        event = support.seedLiveEvent(5, 100, Instant.now(), Instant.now().plusSeconds(864000));
        org = support.orgIdOf(event);
        auth = new UsernamePasswordAuthenticationToken(
                support.principalFor(org), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    /** The seeded event is live and on sale, so the evaluator's pass would otherwise keep visiting it. */
    @AfterEach
    void deleteOwnRows() {
        jdbc.update("delete from momentum_suggestions where org_id = ?", org);
        CampaignRows.delete(jdbc, List.of(org));
        OrgRows.delete(jdbc, List.of(org));
    }

    private MomentumSuggestion suggestion(String trigger) {
        MomentumSuggestion s = new MomentumSuggestion();
        s.setId(UUID.randomUUID());
        s.setOrgId(org);
        s.setEventId(event);
        s.setTriggerType(trigger);
        s.setStatus("suggested");
        s.setMetricsSnapshot("{\"sellThroughPct\":5}");
        s.setDraftPayload("{\"subject\":\"Announcing\",\"bodyMd\":\"b\",\"segmentId\":null,\"why\":\"low sales\"}");
        s.setSuggestedAt(Instant.now());
        return suggestions.save(s);
    }

    @Test
    void momentumRoutes_answerTheirStatus() throws Exception {
        MomentumSuggestion approved = suggestion("launch_push");
        MomentumSuggestion dismissed = suggestion("slump");

        mvc.perform(get("/api/v1/marketing/suggestions?status=suggested").with(authentication(auth)))
                .andExpect(status().isOk());
        // 200 here proves "state" is not captured as the {id} path variable.
        mvc.perform(get("/api/v1/marketing/suggestions/state").with(authentication(auth)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/marketing/suggestions/" + approved.getId() + "/approve").with(authentication(auth)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/marketing/suggestions/" + dismissed.getId() + "/dismiss").with(authentication(auth)))
                .andExpect(status().isNoContent());
    }
}
