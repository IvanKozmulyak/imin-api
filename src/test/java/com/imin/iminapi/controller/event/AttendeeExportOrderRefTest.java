package com.imin.iminapi.controller.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order token opens the buyer's order page and tickets with no further check, so the file an
 * organizer downloads carries the short code the Orders tab shows instead.
 */
@IminIntegrationTest
class AttendeeExportOrderRefTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired MutableClock clock;

    @Test
    void order_ref_is_the_order_short_code_and_the_order_token_never_leaves() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.LIVE, clock.instant().plusSeconds(86_400));
        Order first = fx.order(e, fx.email("first"));
        fx.ticket(first, Ticket.STATE_ISSUED);
        fx.ticket(first, Ticket.STATE_REDEEMED);
        clock.advance(java.time.Duration.ofSeconds(1));
        Order second = fx.order(e, fx.email("second"));
        fx.ticket(second, Ticket.STATE_ISSUED);

        String csv = mvc.perform(get("/api/v1/events/{id}/attendees/export", e.getId())
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                fx.principal(owner), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String[] lines = csv.strip().split("\r\n");
        assertThat(lines[0]).isEqualTo("order_ref,buyer_email,tier,status,checked_in_at,price,purchased_at");
        assertThat(Arrays.stream(lines).skip(1).map(l -> l.split(",", -1)[0]).toList())
                .containsExactly(shortCode(first), shortCode(first), shortCode(second));
        assertThat(csv).doesNotContain(first.getToken()).doesNotContain(second.getToken());
    }

    private static String shortCode(Order o) {
        return o.getId().toString().substring(0, 8);
    }
}
