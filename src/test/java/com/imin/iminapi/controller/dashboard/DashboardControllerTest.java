package com.imin.iminapi.controller.dashboard;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dashboard and pulse over the real services. */
@IminIntegrationTest
class DashboardControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired MutableClock clock;

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    /** The dashboard reads an absent key as "0", so a figure with no data must arrive as an explicit null. */
    @Test
    void an_org_with_no_events_sends_its_empty_figures_as_null_not_dropped() throws Exception {
        User owner = fx.owner(fx.org());

        mvc.perform(get("/api/v1/dashboard").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycle.deltas", hasKey("revenuePct")))
                .andExpect(jsonPath("$.cycle.deltas.revenuePct").value(nullValue()))
                .andExpect(jsonPath("$.cycle.deltas", hasKey("ticketsPct")))
                .andExpect(jsonPath("$.cycle.deltas.ticketsPct").value(nullValue()))
                .andExpect(jsonPath("$.lastEvent.metrics", hasKey("avgTicketMinor")))
                .andExpect(jsonPath("$.lastEvent.metrics.avgTicketMinor").value(nullValue()));

        mvc.perform(get("/api/v1/dashboard/pulse").with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onSale").value(false))
                .andExpect(jsonPath("$.onSaleCount").value(0))
                .andExpect(jsonPath("$", hasKey("lastSale")))
                .andExpect(jsonPath("$.lastSale").value(nullValue()));
    }

    @Test
    void pulse_for_another_orgs_event_is_a_404() throws Exception {
        Organization other = fx.org();
        Event foreign = fx.event(other, fx.owner(other), EventStatus.LIVE, clock.instant().plusSeconds(86_400));
        User owner = fx.owner(fx.org());

        mvc.perform(get("/api/v1/dashboard/pulse").param("eventId", foreign.getId().toString()).with(as(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
