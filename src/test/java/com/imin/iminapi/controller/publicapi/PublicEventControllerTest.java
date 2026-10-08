package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the public event endpoints over the real {@code PublicEventService}.
 * Eligibility and tier rules are owned by {@code PublicEventServiceTest}/{@code PublicEventServiceListTest}.
 */
@IminIntegrationTest
// Rollback keeps these listable events out of every other class's feed and facets.
@Transactional
class PublicEventControllerTest {

    static final String CACHE_CONTROL_VALUE = "public, s-maxage=60, stale-while-revalidate=30";

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;
    @Autowired EventRepository events;

    final ObjectMapper objectMapper = new ObjectMapper();

    Organization org;
    User owner;
    /** Letters only, so city and genre key normalisation keeps it. */
    String tag;

    @BeforeEach
    void setUp() {
        org = fx.org();
        owner = fx.owner(org);
        tag = randomLetters();
    }

    private Event publishedEvent() {
        Event e = fx.event(org, owner, EventStatus.LIVE, clock.instant().plusSeconds(86_400));
        e.setPublishedAt(clock.instant().minusSeconds(3600));
        e.setGenre("genre" + tag);
        e.setType("concert");
        e.setVenueName("Venue X");
        e.setVenueStreet("123 Main St");
        e.setVenueCity("City" + tag);
        e.setVenuePostalCode("10115");
        e.setVenueCountry("DE");
        return events.save(e);
    }

    /**
     * THE LEAK GUARDRAIL. The JSON keys at the top level and in every nested object are EXACTLY
     * the allow-listed sets. If this fails, you added a field to PublicEventResponse (or a nested
     * DTO): verify it is safe to expose publicly, then update the allowlist.
     */
    @Test
    void get_published_event_has_only_allow_listed_keys_and_the_public_cache_header() throws Exception {
        Event e = publishedEvent();
        TicketTier tier = fx.tier(e, 2500, 100);

        MvcResult result = mvc.perform(get("/api/v1/public/events/" + e.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", CACHE_CONTROL_VALUE))
                .andExpect(jsonPath("$.id").value(e.getId().toString()))
                .andExpect(jsonPath("$.name").value(e.getName()))
                .andExpect(jsonPath("$.slug").value(e.getSlug()))
                .andExpect(jsonPath("$.status").value("live"))
                .andExpect(jsonPath("$.organization.name").value(org.getName()))
                .andExpect(jsonPath("$.organization.slug").value(org.getSlug()))
                .andExpect(jsonPath("$.tiers[0].id").value(tier.getId().toString()))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(root))
                .as("Top-level keys leaked or missing on PublicEventResponse.")
                .isEqualTo(Set.of(
                        "id", "slug", "name", "status", "publishedAt",
                        "genre", "type", "description", "startsAt", "endsAt",
                        "timezone", "venue", "posterUrl", "posterAiGenerated", "videoUrl",
                        "currency", "onSaleAt", "saleClosesAt",
                        "organization", "tiers", "metaPixelId"));
        assertThat(fieldNames(root.get("organization")))
                .as("organization keys leaked or missing on PublicOrganizationDto.")
                .isEqualTo(Set.of("name", "slug"));
        assertThat(fieldNames(root.get("venue")))
                .as("venue keys leaked or missing on PublicVenueDto.")
                .isEqualTo(Set.of("name", "street", "city", "postalCode", "country", "latitude", "longitude"));
        assertThat(fieldNames(root.get("tiers").get(0)))
                .as("tiers[0] keys leaked or missing on PublicTierDto.")
                .isEqualTo(Set.of(
                        "id", "name", "priceMinor", "priceAllInMinor", "currency",
                        "saleStartsAt", "saleClosesAt", "sortOrder", "remaining",
                        "onSale", "soldOut", "closed"));
    }

    /** No leak about which condition failed: the same 404 body as an id that never existed. */
    @Test
    void get_private_event_is_the_same_404_as_an_unknown_id_without_the_public_cache_header() throws Exception {
        Event e = publishedEvent();
        e.setVisibility(EventVisibility.PRIVATE);
        events.save(e);

        String hiddenBody = notFoundBody("/api/v1/public/events/" + e.getId());
        String unknownBody = notFoundBody("/api/v1/public/events/" + UUID.randomUUID());

        assertThat(hiddenBody).isEqualTo(unknownBody);
    }

    private String notFoundBody(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(result -> {
                    String cc = result.getResponse().getHeader("Cache-Control");
                    assertThat(cc == null || !cc.contains("s-maxage"))
                            .as("404 must not carry the public/s-maxage cache header (was: %s)", cc)
                            .isTrue();
                })
                .andReturn().getResponse().getContentAsString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/public/events/not-a-uuid", "/api/v1/public/events?from=not-a-date"})
    void malformed_path_or_query_parameter_is_400_invalid_request(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    /**
     * THE LIST LEAK GUARDRAIL. items[0] and items[0].organization carry EXACTLY the allow-listed
     * keys. If this fails, you added a field to PublicEventListItem: verify it is safe, then update.
     */
    @Test
    void list_item_has_only_allow_listed_keys_and_the_public_cache_header() throws Exception {
        Event e = publishedEvent();
        fx.tier(e, 2500, 100);

        MvcResult result = mvc.perform(get("/api/v1/public/events").param("orgSlug", org.getSlug()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", CACHE_CONTROL_VALUE))
                .andExpect(jsonPath("$.items[0].id").value(e.getId().toString()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andReturn();

        JsonNode item0 = objectMapper.readTree(result.getResponse().getContentAsString()).get("items").get(0);
        assertThat(fieldNames(item0))
                .as("items[0] keys leaked or missing on PublicEventListItem.")
                .isEqualTo(Set.of(
                        "id", "slug", "name", "status", "publishedAt",
                        "genre", "type", "startsAt", "endsAt", "timezone",
                        "venueName", "venueCity", "venueCountry", "posterUrl", "posterAiGenerated", "currency",
                        "priceFromMinor", "soldOut", "lowStock", "organization"));
        assertThat(fieldNames(item0.get("organization")))
                .as("items[0].organization keys leaked or missing.")
                .isEqualTo(Set.of("name", "slug"));
    }

    /** Leak guardrail for the /cities facet: anything beyond these three keys needs the same scrutiny. */
    @Test
    void cities_facet_has_only_allow_listed_keys_and_the_public_cache_header() throws Exception {
        publishedEvent();

        MvcResult result = mvc.perform(get("/api/v1/public/events/cities"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", CACHE_CONTROL_VALUE))
                .andReturn();

        JsonNode own = null;
        for (JsonNode item : objectMapper.readTree(result.getResponse().getContentAsString())) {
            assertThat(fieldNames(item)).isEqualTo(Set.of("city", "country", "eventCount"));
            if (("City" + tag).equals(item.get("city").asText())) own = item;
        }
        assertThat(own).as("this test's city chip").isNotNull();
        assertThat(own.get("country").asText()).isEqualTo("DE");
        assertThat(own.get("eventCount").asLong()).isEqualTo(1);
    }

    @Test
    void genres_facet_lists_a_published_genre_with_the_public_cache_header() throws Exception {
        publishedEvent();

        MvcResult result = mvc.perform(get("/api/v1/public/events/genres"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", CACHE_CONTROL_VALUE))
                .andReturn();

        Set<String> genres = StreamSupport.stream(
                        objectMapper.readTree(result.getResponse().getContentAsString()).spliterator(), false)
                .map(JsonNode::asText).collect(Collectors.toSet());
        assertThat(genres).contains("genre" + tag);
    }

    // --- helpers ---

    private static String randomLetters() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        return sb.toString();
    }

    private static Set<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(
                ((Iterable<String>) node::fieldNames).spliterator(), false
        ).collect(Collectors.toSet());
    }
}
