package com.imin.iminapi.controller.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.TicketTierRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The organizer events HTTP contract over the real services; cross-org probes are owned by CrossOrgScopingTest. */
@IminIntegrationTest
class EventControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired EventRepository events;
    @Autowired MutableClock clock;
    @Autowired TicketTierRepository tierRepo;

    private final ObjectMapper om = new ObjectMapper();

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    /**
     * An unknown status was a bare valueOf and a 500; a genre over the outcome column's 64 chars rolled the
     * whole publish back later. Both are FIELD_INVALID at the edge, and the event is left as it was.
     */
    @ParameterizedTest
    @ValueSource(strings = {"status", "genre"})
    void an_invalid_parameter_is_FIELD_INVALID_on_its_field(String field) throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.DRAFT, null);
        String genreBefore = e.getGenre();
        MockHttpServletRequestBuilder req = field.equals("status")
                ? get("/api/v1/events").param("status", "archived")
                : patch("/api/v1/events/{id}", e.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("genre", "x".repeat(65))));

        mvc.perform(req.with(as(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields." + field).exists());

        if (field.equals("genre")) {
            assertThat(events.findById(e.getId()).orElseThrow().getGenre()).isEqualTo(genreBefore);
        }
    }

    private TicketTier tier(Event e, int quantity, int sold, int reserved, boolean enabled) {
        TicketTier t = fx.tier(e, 1000, quantity);
        t.setSold(sold);
        t.setReserved(reserved);
        t.setEnabled(enabled);
        return tierRepo.save(t);
    }

    private com.jayway.jsonpath.DocumentContext row(User owner, Event e) throws Exception {
        String body = mvc.perform(get("/api/v1/events").with(as(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body);
    }

    private Object field(com.jayway.jsonpath.DocumentContext json, Event e, String name) {
        List<Object> v = json.read("$.items[?(@.id=='" + e.getId() + "')]." + name);
        return v.isEmpty() ? null : v.get(0);
    }

    /** Edges of min(30, ceil(20%)): 100 seats -> 20; 1000 seats -> capped at 30; a held seat counts as gone; off/sold out never. */
    @ParameterizedTest
    @CsvSource({
            "100, 80, 0, true, 20",
            "100, 79, 0, true, ",
            "100, 70, 10, true, 20",
            "1000, 970, 0, true, 30",
            "1000, 969, 0, true, ",
            "100, 100, 0, true, ",
            "100, 90, 10, true, ",
            "100, 85, 0, false, ",
            "5, 4, 0, true, 1"})
    void almost_gone_follows_the_webapp_threshold_per_tier(int quantity, int sold, int reserved,
                                                           boolean enabled, Integer expectedLeft) throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.LIVE, Instant.now().plus(10, ChronoUnit.DAYS));
        tier(e, quantity, sold, reserved, enabled);

        var json = row(owner, e);

        assertThat(field(json, e, "ticketsLeft")).isEqualTo(expectedLeft);
        assertThat(field(json, e, "almostGone")).isEqualTo(expectedLeft != null ? Boolean.TRUE : Boolean.FALSE);
    }

    @Test
    void an_event_not_on_sale_has_no_almost_gone_even_with_a_scarce_tier() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Instant later = Instant.now().plus(10, ChronoUnit.DAYS);
        Event draft = fx.event(org, owner, EventStatus.DRAFT, later);
        Event started = fx.event(org, owner, EventStatus.LIVE, Instant.now().minus(1, ChronoUnit.HOURS));
        Event notOpen = fx.event(org, owner, EventStatus.LIVE, later);
        notOpen.setOnSaleAt(Instant.now().plus(2, ChronoUnit.DAYS));
        events.save(notOpen);
        Event closed = fx.event(org, owner, EventStatus.LIVE, later);
        closed.setSaleClosesAt(Instant.now().minus(1, ChronoUnit.HOURS));
        events.save(closed);
        Event tierNotOpen = fx.event(org, owner, EventStatus.LIVE, later);
        for (Event e : List.of(draft, started, notOpen, closed, tierNotOpen)) tier(e, 100, 95, 0, true);
        TicketTier t = tierRepo.findByEventIdOrderBySortOrderAsc(tierNotOpen.getId()).get(0);
        t.setSaleStartsAt(Instant.now().plus(1, ChronoUnit.DAYS));
        tierRepo.save(t);

        var json = row(owner, draft);

        for (Event e : List.of(draft, started, notOpen, closed, tierNotOpen)) {
            assertThat(field(json, e, "almostGone")).as(e.getStatus() + " " + e.getId()).isEqualTo(false);
            assertThat(field(json, e, "ticketsLeft")).isNull();
        }
    }

    @Test
    void with_several_tiers_only_buyable_almost_gone_ones_count_and_the_scarcest_wins() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.LIVE, Instant.now().plus(10, ChronoUnit.DAYS));
        tier(e, 100, 10, 0, true);      // plenty left
        tier(e, 100, 100, 0, true);     // sold out
        tier(e, 100, 99, 0, false);     // scarcest but off sale
        tier(e, 50, 42, 0, true);       // 8 left, the scarcest buyable
        tier(e, 100, 85, 0, true);      // 15 left
        Event quiet = fx.event(org, owner, EventStatus.LIVE, Instant.now().plus(10, ChronoUnit.DAYS));
        tier(quiet, 100, 10, 0, true);

        var json = row(owner, e);

        assertThat(field(json, e, "ticketsLeft")).isEqualTo(8);
        assertThat(field(json, e, "almostGone")).isEqualTo(true);
        assertThat(field(json, quiet, "almostGone")).isEqualTo(false);
    }

    @Test
    void a_genre_at_the_64_char_limit_is_accepted_and_saved() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.DRAFT, null);
        String genre = "g".repeat(64);

        mvc.perform(patch("/api/v1/events/{id}", e.getId()).with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("genre", genre))))
                .andExpect(status().isOk());

        assertThat(events.findById(e.getId()).orElseThrow().getGenre()).isEqualTo(genre);
    }

    @Test
    void post_creates_a_draft_in_the_callers_org() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);

        String body = mvc.perform(post("/api/v1/events").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andReturn().getResponse().getContentAsString();

        Event created = events.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow();
        assertThat(created.getOrgId()).isEqualTo(org.getId());
        assertThat(created.getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void the_status_filter_lists_only_the_matching_own_events() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event live = fx.event(org, owner, EventStatus.LIVE, clock.instant().plusSeconds(86_400));
        Event draft = fx.event(org, owner, EventStatus.DRAFT, null);

        String body = mvc.perform(get("/api/v1/events").param("status", "live").with(as(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(body, "$.items[*].id");
        assertThat(ids).containsExactly(live.getId().toString()).doesNotContain(draft.getId().toString());
    }

    @Test
    void delete_removes_an_own_draft_with_204() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event draft = fx.event(org, owner, EventStatus.DRAFT, null);

        mvc.perform(delete("/api/v1/events/{id}", draft.getId()).with(as(owner)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(events.findActive(draft.getId())).isEmpty();
    }

    @Test
    void the_overview_of_an_own_event_reports_its_tier_capacity() throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        Event e = fx.event(org, owner, EventStatus.LIVE, clock.instant().plusSeconds(86_400 * 30L));
        fx.tier(e, 1500, 120);

        mvc.perform(get("/api/v1/events/{id}/overview", e.getId()).with(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.capacity").value(120));
    }
}
