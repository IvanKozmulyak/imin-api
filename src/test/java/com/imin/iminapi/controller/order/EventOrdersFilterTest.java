package com.imin.iminapi.controller.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Orders-tab filters. The status filter runs in SQL before the row cap, so it must agree with the
 * status each row renders, and a search must never 500 or let typed wildcards match everything.
 */
@IminIntegrationTest
class EventOrdersFilterTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    @Autowired IminFixtures fx;
    @Autowired OrderRepository orders;
    @Autowired DisputeRepository disputes;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    private Organization org;
    private Event event;
    private RequestPostProcessor owner;
    private int seq;

    @BeforeEach
    void setUp() {
        org = fx.org();
        User u = fx.owner(org);
        event = fx.event(org, u, EventStatus.LIVE, clock.instant().plusSeconds(86_400L * 10));
        owner = authentication(new UsernamePasswordAuthenticationToken(fx.principal(u),
                null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    /** Disputes are swept across every org, so the open ones this class leaves must go with their org. */
    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, List.of(org.getId()));
    }

    /** Created oldest first, one second apart, so newest-first order is the reverse of creation. */
    private Order orderFor(String email, String... ticketStates) {
        Order o = fx.order(event, email);
        o.setCreatedAt(clock.instant().minusSeconds(3600 - seq++));
        o = orders.save(o);
        for (String s : ticketStates) fx.ticket(o, s);
        return o;
    }

    private Order order(String... ticketStates) {
        return orderFor(fx.email("buyer"), ticketStates);
    }

    private void dispute(Order o, DisputeStatus status) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().substring(0, 12));
        d.setOrgId(org.getId());
        d.setEventId(event.getId());
        d.setOrderId(o.getId());
        d.setAmountMinor(1500);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setOpenedAt(clock.instant());
        disputes.save(d);
    }

    /** id → rendered status, newest first. */
    private Map<UUID, String> rows(MockHttpServletRequestBuilder req) throws Exception {
        String body = mvc.perform(req.with(owner)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<UUID, String> out = new LinkedHashMap<>();
        for (JsonNode n : json.readTree(body)) out.put(UUID.fromString(n.get("id").asText()), n.get("status").asText());
        return out;
    }

    private MockHttpServletRequestBuilder list() {
        return get("/api/v1/events/{id}/orders", event.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid", "partially_refunded", "refunded", "disputed"})
    void each_status_filter_returns_exactly_the_rows_rendered_with_that_status(String wanted) throws Exception {
        Map<UUID, String> expected = new HashMap<>();
        expected.put(order(Ticket.STATE_ISSUED).getId(), "paid");
        expected.put(order(Ticket.STATE_REDEEMED, Ticket.STATE_ISSUED).getId(), "paid");
        expected.put(order().getId(), "paid");
        expected.put(order(Ticket.STATE_ISSUED, Ticket.STATE_REFUNDED).getId(), "partially_refunded");
        expected.put(order(Ticket.STATE_REDEEMED, Ticket.STATE_REVOKED).getId(), "partially_refunded");
        expected.put(order(Ticket.STATE_REFUNDED, Ticket.STATE_REFUNDED).getId(), "refunded");
        expected.put(order(Ticket.STATE_REVOKED).getId(), "refunded");
        expected.put(order(Ticket.STATE_REFUNDED, Ticket.STATE_REVOKED).getId(), "refunded");
        Order open = order(Ticket.STATE_REVOKED);
        dispute(open, DisputeStatus.OPEN);
        expected.put(open.getId(), "disputed");
        Order lost = order(Ticket.STATE_REVOKED);
        dispute(lost, DisputeStatus.LOST);
        expected.put(lost.getId(), "disputed");
        Order won = order(Ticket.STATE_ISSUED);
        dispute(won, DisputeStatus.WON);
        expected.put(won.getId(), "paid");
        Order wonThenOpen = order(Ticket.STATE_REVOKED);
        dispute(wonThenOpen, DisputeStatus.WON);
        dispute(wonThenOpen, DisputeStatus.OPEN);
        expected.put(wonThenOpen.getId(), "disputed");
        Order reinstated = order(Ticket.STATE_ISSUED, Ticket.STATE_REFUNDED);
        dispute(reinstated, DisputeStatus.WITHDRAWN_REINSTATED);
        expected.put(reinstated.getId(), "partially_refunded");

        Map<UUID, String> all = rows(list());
        assertThat(all).containsExactlyInAnyOrderEntriesOf(expected);

        List<UUID> wantedIds = expected.entrySet().stream()
                .filter(e -> e.getValue().equals(wanted)).map(Map.Entry::getKey).toList();
        Map<UUID, String> filtered = rows(list().param("status", wanted));
        assertThat(filtered.keySet()).containsExactlyInAnyOrderElementsOf(wantedIds);
        assertThat(filtered.values()).containsOnly(wanted);
    }

    @Test
    void the_limit_applies_after_the_status_filter() throws Exception {
        Order disputed = order(Ticket.STATE_REVOKED);
        dispute(disputed, DisputeStatus.OPEN);
        for (int i = 0; i < 3; i++) order(Ticket.STATE_ISSUED);

        assertThat(rows(list().param("status", "disputed").param("limit", "1")).keySet())
                .containsExactly(disputed.getId());
        assertThat(rows(list().param("status", "paid").param("limit", "2"))).hasSize(2).containsValues("paid");
    }

    @Test
    void search_matches_an_email_substring_whatever_its_case() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Order hit = orderFor("Mixed.Case-" + tag + "@Example.test", Ticket.STATE_ISSUED);
        order(Ticket.STATE_ISSUED);

        assertThat(rows(list().param("q", "  mixed.CASE-" + tag.toUpperCase(Locale.ROOT) + "@example ")).keySet())
                .containsExactly(hit.getId());
    }

    @Test
    void search_matches_a_short_code_prefix_whatever_its_case() throws Exception {
        Order hit = order(Ticket.STATE_ISSUED);
        Order other = order(Ticket.STATE_ISSUED);
        String prefix = hit.getId().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        // Skip the vanishing case where both ids share the 6-char prefix.
        assumeFalse(other.getId().toString().toUpperCase(Locale.ROOT).startsWith(prefix));

        assertThat(rows(list().param("q", prefix)).keySet()).containsExactly(hit.getId());
        // A prefix, not a substring: the code's tail does not match on its own.
        String tail = hit.getId().toString().substring(2, 8);
        assertThat(rows(list().param("q", tail)).keySet()).doesNotContain(hit.getId());
    }

    @Test
    void no_search_or_a_blank_one_lists_every_order() throws Exception {
        Order a = order(Ticket.STATE_ISSUED);
        Order b = order(Ticket.STATE_REFUNDED);

        assertThat(rows(list()).keySet()).containsExactly(b.getId(), a.getId());
        assertThat(rows(list().param("q", "   ")).keySet()).containsExactly(b.getId(), a.getId());
        assertThat(rows(list().param("q", "")).keySet()).containsExactly(b.getId(), a.getId());
    }

    /** {@code %}, {@code _} and the escape character itself match only themselves. */
    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!"})
    void like_wildcards_in_the_search_match_literally(String wildcard) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Order literal = orderFor("a" + wildcard + "b-" + tag + "@example.test", Ticket.STATE_ISSUED);
        orderFor("axb-" + tag + "@example.test", Ticket.STATE_ISSUED);
        orderFor("ab-" + tag + "@example.test", Ticket.STATE_ISSUED);

        assertThat(rows(list().param("q", "a" + wildcard + "b-" + tag)).keySet()).containsExactly(literal.getId());
        assertThat(rows(list().param("q", "a" + wildcard)).keySet()).containsExactly(literal.getId());
    }

    @Test
    void an_unknown_status_is_a_400_not_an_empty_list() throws Exception {
        mvc.perform(list().param("status", "chargeback").with(owner))
                .andExpect(status().isBadRequest());
    }
}
