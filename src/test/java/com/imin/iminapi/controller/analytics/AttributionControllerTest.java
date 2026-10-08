package com.imin.iminapi.controller.analytics;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Attribution read-models are the caller's org only: another org's visits and tagged revenue never appear. */
@IminIntegrationTest
class AttributionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired FunnelEventRepository funnel;
    @Autowired OrderRepository orders;
    @Autowired MutableClock clock;

    @Test
    void another_orgs_beacon_and_revenue_never_reach_the_callers_attribution() throws Exception {
        String sourceA = "src-a-" + UUID.randomUUID();
        String sourceB = "src-b-" + UUID.randomUUID();
        Organization a = fx.org();
        User ownerA = fx.owner(a);
        String hostA = "a-" + UUID.randomUUID() + ".example";
        String hostB = "b-" + UUID.randomUUID() + ".example";
        seed(a, ownerA, sourceA, hostA);
        Organization b = fx.org();
        seed(b, fx.owner(b), sourceB, hostB);
        var asA = authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(ownerA), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));

        mvc.perform(get("/api/v1/analytics/attribution").with(asA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(1))
                .andExpect(jsonPath("$.channels[0].source").value(sourceA))
                .andExpect(jsonPath("$.channels[0].visits").value(1))
                .andExpect(jsonPath("$.channels[0].revenueMinor").value(1500))
                .andExpect(jsonPath("$.attributedRevenueMinor").value(1500));

        // The client slice reaches the service: A's beacons are web, so an ios slice has no channel.
        mvc.perform(get("/api/v1/analytics/attribution").param("client", "ios").with(asA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(0));

        mvc.perform(get("/api/v1/analytics/untagged").with(asA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.length()").value(1))
                .andExpect(jsonPath("$.links[0].referrerHost").value(hostA))
                .andExpect(jsonPath("$.links[0].visits").value(1));
    }

    /** A tagged and an untagged web page view, and one tagged 15.00 order, on a fresh event of the org. */
    private void seed(Organization org, User owner, String source, String untaggedHost) {
        Event e = fx.event(org, owner, EventStatus.LIVE, clock.instant().plusSeconds(86_400));
        FunnelEvent fe = new FunnelEvent();
        fe.setEventId(e.getId());
        fe.setStage(FunnelEvent.STAGE_PAGE_VIEW);
        fe.setAnonId("anon-" + UUID.randomUUID());
        fe.setUtmSource(source);
        fe.setCreatedAt(clock.instant());
        funnel.save(fe);
        FunnelEvent untagged = new FunnelEvent();
        untagged.setEventId(e.getId());
        untagged.setStage(FunnelEvent.STAGE_PAGE_VIEW);
        untagged.setAnonId("anon-" + UUID.randomUUID());
        untagged.setReferrerHost(untaggedHost);
        untagged.setCreatedAt(clock.instant());
        funnel.save(untagged);
        Order o = fx.order(e, fx.email("buyer"));
        o.setUtmSource(source);
        orders.save(o);
    }
}
