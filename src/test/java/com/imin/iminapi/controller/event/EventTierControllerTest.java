package com.imin.iminapi.controller.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tier routes; the cross-org patch and delete probes are owned by CrossOrgScopingTest. */
@IminIntegrationTest
class EventTierControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired TicketTierRepository tiers;

    private final ObjectMapper om = new ObjectMapper();

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    @Test
    void creating_a_tier_on_another_orgs_event_is_a_404_and_writes_no_tier() throws Exception {
        Organization victim = fx.org();
        Event foreign = fx.event(victim, fx.owner(victim), EventStatus.DRAFT, null);
        TicketTier existing = fx.tier(foreign, 1500, 100);
        User attacker = fx.owner(fx.org());

        mvc.perform(post("/api/v1/events/{id}/tiers", foreign.getId()).with(as(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Sneaky", "priceMinor", 1, "quantity", 50))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(tiers.findByEventIdOrderBySortOrderAsc(foreign.getId()))
                .extracting(TicketTier::getId).containsExactly(existing.getId());
    }

    /** The own-event tier routes over the real service answer their documented status. */
    @Test
    void own_event_tier_routes_answer_their_status() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.DRAFT, null);
        String base = "/api/v1/events/" + e.getId() + "/tiers";

        String created = mvc.perform(post(base).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "GA", "priceMinor", 1000, "quantity", 100))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tierId = JsonPath.read(created, "$.id");

        mvc.perform(patch(base + "/" + tierId).with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "VIP"))))
                .andExpect(status().isOk());
        mvc.perform(delete(base + "/" + tierId).with(as(owner))).andExpect(status().isNoContent());
    }
}
