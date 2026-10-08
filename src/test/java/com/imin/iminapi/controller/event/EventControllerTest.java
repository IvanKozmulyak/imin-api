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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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
