package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.*;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.PredictorJson;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PredictorRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The frozen re-forecast endpoint contract: GET status flow (none, then a served result with a ledger stamp), the
 * Stage 0 interim when no curve exists, and org scoping (cross-org 404). Stage 1 pacing math and the
 * insufficient-data fallback are covered by {@link ReforecastServiceTest}.
 */
@IminIntegrationTest
class ReforecastControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired PredictionLedgerRepository ledger;

    final ObjectMapper om = new ObjectMapper();

    private final List<UUID> createdOrgs = new ArrayList<>();

    private Organization org;
    private User owner;
    private Event event;

    @BeforeEach
    void seed() {
        org = fx.org();
        createdOrgs.add(org.getId());
        org.setCountry("NL");
        org = orgs.save(org);
        owner = fx.owner(org);

        event = new Event();
        event.setOrgId(org.getId());
        event.setCreatedBy(owner.getId());
        event.setName("RF Night");
        event.setSlug("ev-" + UUID.randomUUID().toString().substring(0, 8));
        event.setGenre("techno");
        event.setVenueCity("Amsterdam" + DateCheckControllerTest.letters());
        event.setVenueCountry("NL");
        event.setStatus(EventStatus.LIVE);
        event.setTimezone("Europe/Amsterdam");
        event.setStartsAt(Instant.now().plusSeconds(20L * 86400));
        event = events.save(event);
        TicketTier ga = new TicketTier();
        ga.setEventId(event.getId());
        ga.setName("GA");
        ga.setPriceMinor(2000);
        ga.setQuantity(200);
        ga.setSold(60);
        tiers.save(ga);
    }

    @AfterEach
    void after() {
        PredictorRows.delete(jdbc, createdOrgs);
    }

    private long ownReforecastRows() {
        return ledger.findByEventIdOrderByCreatedAtDesc(event.getId()).stream()
                .filter(r -> r.getSurface() == PredictionSurface.REFORECAST).count();
    }

    private Authentication auth(User u, Organization o) {
        AuthPrincipal p = new AuthPrincipal(u.getId(), o.getId(), UserRole.OWNER, UUID.randomUUID());
        return new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    @Test
    void getIsNoneBeforeAnyForecast() throws Exception {
        mvc.perform(get("/api/v1/events/" + event.getId() + "/reforecast").with(authentication(auth(owner, org))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("none"))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty()); // timestamp present even for none
    }

    @Test
    void manualPostRunsStage0InterimFromPrePublishAndGetServesItWithLedgerStamp() throws Exception {
        seedPrePublish(120, 170); // no pacing curve exists → interim leans on this
        Authentication a = auth(owner, org);
        long before = ownReforecastRows();

        MvcResult posted = mvc.perform(post("/api/v1/events/" + event.getId() + "/reforecast").with(authentication(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.stage").value(0))          // EXPLICITLY labelled interim
                .andExpect(jsonPath("$.band").isNotEmpty())
                .andExpect(jsonPath("$.generatedAt").isNotEmpty())
                .andReturn();
        JsonNode body = om.readTree(posted.getResponse().getContentAsString());
        assertThat(body.get("projectedFinalRange").get("low").asInt()).isEqualTo(120);
        assertThat(body.get("ledger").get("promptVersion").asText()).isNotEmpty();

        // GET now serves the ledgered interim with a full ledger stamp.
        mvc.perform(get("/api/v1/events/" + event.getId() + "/reforecast").with(authentication(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.stage").value(0))
                .andExpect(jsonPath("$.ledger.id").isNotEmpty())
                .andExpect(jsonPath("$.ledger.inputHash").isNotEmpty());
        // one reforecast row written by the manual recompute
        assertThat(ownReforecastRows() - before).isEqualTo(1);
    }

    @Test
    void crossOrgIs404() throws Exception {
        Organization other = fx.org();
        createdOrgs.add(other.getId());
        User outsider = fx.owner(other);

        mvc.perform(get("/api/v1/events/" + event.getId() + "/reforecast").with(authentication(auth(outsider, other))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/events/" + event.getId() + "/reforecast").with(authentication(auth(outsider, other))))
                .andExpect(status().isNotFound());
        assertThat(ownReforecastRows()).isZero();
    }

    private void seedPrePublish(int attLow, int attHigh) {
        PredictionResult pre = new PredictionResult(
                "pre_publish", 0, "B",
                new PredictionResult.Band(40, 70),
                new PredictionResult.Range(attLow, attHigh),
                null, List.of(), List.of(), null, false, "m", "1.0.0", Instant.now());
        PredictionLedger row = new PredictionLedger();
        row.setEventId(event.getId());
        row.setOrgId(org.getId());
        row.setSurface(PredictionSurface.PRE_PUBLISH);
        row.setStage((short) 0);
        row.setModelId("m");
        row.setPromptVersion("1.0.0");
        row.setInputSnapshotHash("h");
        row.setComparablesJson("{}");
        try {
            row.setOutputJson(PredictorJson.MAPPER.writeValueAsString(pre));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        ledger.save(row);
    }
}
