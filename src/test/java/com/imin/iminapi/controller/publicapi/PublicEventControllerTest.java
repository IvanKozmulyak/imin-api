package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.publicapi.PublicCityItem;
import com.imin.iminapi.dto.publicapi.PublicEventListItem;
import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.dto.publicapi.PublicOrganizationDto;
import com.imin.iminapi.dto.publicapi.PublicTierDto;
import com.imin.iminapi.dto.publicapi.PublicVenueDto;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.event.PublicEventListQuery;
import com.imin.iminapi.service.event.PublicEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class PublicEventControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper objectMapper = new ObjectMapper();
    @MockitoBean PublicEventService publicEventService;

    static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID TIER_ID  = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    static final String CACHE_CONTROL_VALUE = "public, s-maxage=60, stale-while-revalidate=30";

    private PublicEventResponse sampleResponse() {
        PublicVenueDto venue = new PublicVenueDto("Venue X", "123 Main St", "Berlin", "10115", "DE", 52.5200d, 13.4050d);
        PublicOrganizationDto org = new PublicOrganizationDto("Acme Events", "acme-events");
        PublicTierDto tier = new PublicTierDto(
                TIER_ID, "General Admission", 2500, "EUR",
                null, Instant.parse("2026-06-01T00:00:00Z"), 1, 100, true, false, false);

        return new PublicEventResponse(
                EVENT_ID,
                "summer-festival-2026",
                "Summer Festival 2026",
                "live",
                Instant.parse("2026-01-15T10:00:00Z"),
                "techno",
                "concert",
                "A great summer festival",
                Instant.parse("2026-07-01T16:00:00Z"),
                Instant.parse("2026-07-02T04:00:00Z"),
                "Europe/Berlin",
                venue,
                "https://cdn.example.com/poster.jpg",
                null,
                "EUR",
                Instant.parse("2026-02-01T00:00:00Z"),
                Instant.parse("2026-06-30T00:00:00Z"),
                org,
                List.of(tier),
                null
        );
    }

    @Test
    void get_returns200WithBodyAndCacheControl() throws Exception {
        when(publicEventService.get(EVENT_ID, false)).thenReturn(sampleResponse());

        mvc.perform(get("/api/v1/public/events/" + EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", CACHE_CONTROL_VALUE))
                .andExpect(jsonPath("$.id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.name").value("Summer Festival 2026"))
                .andExpect(jsonPath("$.slug").value("summer-festival-2026"))
                .andExpect(jsonPath("$.status").value("live"))
                .andExpect(jsonPath("$.organization.name").value("Acme Events"))
                .andExpect(jsonPath("$.organization.slug").value("acme-events"))
                .andExpect(jsonPath("$.tiers").isArray())
                .andExpect(jsonPath("$.tiers[0].id").value(TIER_ID.toString()));
    }

    @Test
    void get_returns404_whenServiceThrowsNotFound() throws Exception {
        when(publicEventService.get(EVENT_ID, false)).thenThrow(ApiException.notFound("Event"));

        mvc.perform(get("/api/v1/public/events/" + EVENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                // 404 errors must NOT carry the public cache header. Spring Security may add
                // its own no-store default — that's fine; we only forbid the public/s-maxage one.
                .andExpect(result -> {
                    String cc = result.getResponse().getHeader("Cache-Control");
                    assertThat(cc == null || !cc.contains("s-maxage"))
                            .as("404 must not carry the public/s-maxage cache header (was: %s)", cc)
                            .isTrue();
                });
    }

    @Test
    void get_returns400_whenIdIsMalformed() throws Exception {
        mvc.perform(get("/api/v1/public/events/not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(publicEventService);
    }

    /**
     * THE LEAK GUARDRAIL.
     *
     * Asserts that the JSON response keys at the top level, within "organization", and within
     * "tiers[0]" are EXACTLY the allow-listed sets. If this test fails, you added a field to
     * PublicEventResponse (or a nested DTO). Verify it is safe to expose publicly, then update
     * this test's allowlist.
     */
    @Test
    void get_responseHasOnlyAllowListedKeys() throws Exception {
        when(publicEventService.get(EVENT_ID, false)).thenReturn(sampleResponse());

        MvcResult result = mvc.perform(get("/api/v1/public/events/" + EVENT_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        Set<String> actualRootKeys = fieldNames(root);
        Set<String> expectedRootKeys = Set.of(
                "id", "slug", "name", "status", "publishedAt",
                "genre", "type", "description", "startsAt", "endsAt",
                "timezone", "venue", "posterUrl", "videoUrl",
                "currency", "onSaleAt", "saleClosesAt",
                "organization", "tiers", "metaPixelId"
        );
        assertThat(actualRootKeys)
                .as("Top-level keys leaked or missing. " +
                    "If this fails, you added a field to PublicEventResponse. " +
                    "Verify it is safe to expose, then update this test's allowlist.")
                .isEqualTo(expectedRootKeys);

        Set<String> actualOrgKeys = fieldNames(root.get("organization"));
        Set<String> expectedOrgKeys = Set.of("name", "slug");
        assertThat(actualOrgKeys)
                .as("organization keys leaked or missing. " +
                    "If this fails, you added a field to PublicOrganizationDto. " +
                    "Verify it is safe to expose, then update this test's allowlist.")
                .isEqualTo(expectedOrgKeys);

        Set<String> actualVenueKeys = fieldNames(root.get("venue"));
        Set<String> expectedVenueKeys = Set.of(
                "name", "street", "city", "postalCode", "country", "latitude", "longitude");
        assertThat(actualVenueKeys)
                .as("venue keys leaked or missing. " +
                    "If this fails, you added a field to PublicVenueDto. " +
                    "Verify it is safe to expose, then update this test's allowlist.")
                .isEqualTo(expectedVenueKeys);

        JsonNode tier0 = root.get("tiers").get(0);
        Set<String> actualTierKeys = fieldNames(tier0);
        Set<String> expectedTierKeys = Set.of(
                "id", "name", "priceMinor", "currency",
                "saleStartsAt", "saleClosesAt", "sortOrder", "remaining",
                "onSale", "soldOut", "closed"
        );
        assertThat(actualTierKeys)
                .as("tiers[0] keys leaked or missing. " +
                    "If this fails, you added a field to PublicTierDto. " +
                    "Verify it is safe to expose, then update this test's allowlist.")
                .isEqualTo(expectedTierKeys);
    }

    /**
     * Verifies the endpoint is reachable without an Authorization header.
     *
     * This test loads the full application context including SecurityConfig (via @SpringBootTest),
     * so it exercises the real permitAll rule for GET /api/v1/public/**.
     * A 404 from the service is acceptable; we only assert it is NOT 401 or 403.
     */
    @Test
    void get_endpointReachableWithoutAuth() throws Exception {
        when(publicEventService.get(any(), eq(false))).thenReturn(sampleResponse());

        mvc.perform(get("/api/v1/public/events/" + EVENT_ID))
                // No Authorization header — must not be blocked by auth
                .andExpect(status().isOk());
    }

    @Test
    void get_passesIncludeUnavailableThrough_whenTrue() throws Exception {
        when(publicEventService.get(EVENT_ID, true)).thenReturn(sampleResponse());

        mvc.perform(get("/api/v1/public/events/" + EVENT_ID).param("includeUnavailable", "true"))
                .andExpect(status().isOk());

        verify(publicEventService).get(EVENT_ID, true);
        verify(publicEventService, never()).get(EVENT_ID, false);
    }

    @Test
    void get_defaultsIncludeUnavailableToFalse_whenParamAbsent() throws Exception {
        when(publicEventService.get(EVENT_ID, false)).thenReturn(sampleResponse());

        mvc.perform(get("/api/v1/public/events/" + EVENT_ID))
                .andExpect(status().isOk());

        verify(publicEventService).get(EVENT_ID, false);
        verify(publicEventService, never()).get(EVENT_ID, true);
    }

    // -----------------------------------------------------------------------
    // Listing endpoint tests
    // -----------------------------------------------------------------------

    private PublicEventListItem sampleListItem() {
        return new PublicEventListItem(
                EVENT_ID,
                "summer-festival-2026",
                "Summer Festival 2026",
                "live",
                Instant.parse("2026-01-15T10:00:00Z"),
                "techno",
                "concert",
                Instant.parse("2026-07-01T16:00:00Z"),
                Instant.parse("2026-07-02T04:00:00Z"),
                "Europe/Berlin",
                "Funkhaus",
                "Berlin",
                "DE",
                "https://cdn.example.com/cover.jpg",
                "EUR",
                2500,
                false,
                true,
                new PublicOrganizationDto("Acme Events", "acme-events"));
    }

    @Test
    void list_returns200WithCacheControlHeader() throws Exception {
        when(publicEventService.list(any(PublicEventListQuery.class)))
                .thenReturn(new PageResponse<>(List.of(sampleListItem()), 1, 1, 20));

        mvc.perform(get("/api/v1/public/events"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", CACHE_CONTROL_VALUE))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20));
    }

    @Test
    void list_returns400OnBadTimestampFormat() throws Exception {
        mvc.perform(get("/api/v1/public/events").param("from", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        verifyNoInteractions(publicEventService);
    }

    @Test
    void list_endpointReachableWithoutAuth() throws Exception {
        when(publicEventService.list(any(PublicEventListQuery.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 20));

        mvc.perform(get("/api/v1/public/events"))
                .andExpect(status().isOk());
    }

    @Test
    void list_bindsFullFilterSetIntoQueryObject() throws Exception {
        when(publicEventService.list(any(PublicEventListQuery.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 2, 50));

        mvc.perform(get("/api/v1/public/events")
                        .param("from", "2026-06-01T00:00:00Z")
                        .param("to", "2026-09-01T00:00:00Z")
                        .param("genre", "techno")
                        .param("type", "festival")
                        .param("city", "Berlin")
                        .param("country", "DE")
                        .param("orgSlug", "funkhaus")
                        .param("q", "void")
                        .param("onSaleOnly", "true")
                        .param("page", "2")
                        .param("pageSize", "50"))
                .andExpect(status().isOk());

        ArgumentCaptor<PublicEventListQuery> captor = ArgumentCaptor.forClass(PublicEventListQuery.class);
        verify(publicEventService).list(captor.capture());
        PublicEventListQuery captured = captor.getValue();

        assertThat(captured.from()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(captured.to()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(captured.genre()).isEqualTo("techno");
        assertThat(captured.type()).isEqualTo("festival");
        assertThat(captured.city()).isEqualTo("Berlin");
        assertThat(captured.country()).isEqualTo("DE");
        assertThat(captured.orgSlug()).isEqualTo("funkhaus");
        assertThat(captured.q()).isEqualTo("void");
        assertThat(captured.onSaleOnly()).isTrue();
        assertThat(captured.page()).isEqualTo(2);
        assertThat(captured.pageSize()).isEqualTo(50);
    }

    /**
     * THE LIST LEAK GUARDRAIL.
     *
     * Asserts that the JSON keys for items[0] and items[0].organization are EXACTLY the
     * allow-listed sets. If this fails, you added a field to PublicEventListItem (or
     * PublicOrganizationDto). Verify it is safe to expose, then update the allowlist.
     */
    @Test
    void list_responseItemKeysAreAllowListed() throws Exception {
        when(publicEventService.list(any(PublicEventListQuery.class)))
                .thenReturn(new PageResponse<>(List.of(sampleListItem()), 1, 1, 20));

        MvcResult result = mvc.perform(get("/api/v1/public/events"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode item0 = root.get("items").get(0);

        Set<String> actualItemKeys = fieldNames(item0);
        Set<String> expectedItemKeys = Set.of(
                "id", "slug", "name", "status", "publishedAt",
                "genre", "type", "startsAt", "endsAt", "timezone",
                "venueName", "venueCity", "venueCountry", "posterUrl", "currency",
                "priceFromMinor", "soldOut", "lowStock", "organization"
        );
        assertThat(actualItemKeys)
                .as("items[0] keys leaked or missing. " +
                    "If this fails, you added a field to PublicEventListItem. " +
                    "Verify it is safe to expose, then update this test's allowlist.")
                .isEqualTo(expectedItemKeys);

        Set<String> actualOrgKeys = fieldNames(item0.get("organization"));
        Set<String> expectedOrgKeys = Set.of("name", "slug");
        assertThat(actualOrgKeys)
                .as("items[0].organization keys leaked or missing.")
                .isEqualTo(expectedOrgKeys);
    }

    // -- /cities --

    @Test
    void listCities_returns200WithBodyAndCacheControl() throws Exception {
        when(publicEventService.listCities()).thenReturn(List.of(
                new PublicCityItem("Berlin", "DE", 7L),
                new PublicCityItem("Paris", "FR", 2L)));

        mvc.perform(get("/api/v1/public/events/cities"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", CACHE_CONTROL_VALUE))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].city").value("Berlin"))
                .andExpect(jsonPath("$[0].country").value("DE"))
                .andExpect(jsonPath("$[0].eventCount").value(7))
                .andExpect(jsonPath("$[1].city").value("Paris"))
                .andExpect(jsonPath("$[1].eventCount").value(2));
    }

    @Test
    void listCities_itemHasOnlyAllowListedKeys() throws Exception {
        // Leak guardrail for the /cities facet. eventCount was added deliberately (V-less,
        // read-only aggregate); anything else appearing here needs the same scrutiny.
        when(publicEventService.listCities()).thenReturn(List.of(new PublicCityItem("Berlin", "DE", 7L)));

        MvcResult result = mvc.perform(get("/api/v1/public/events/cities"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode item0 = objectMapper.readTree(result.getResponse().getContentAsString()).get(0);
        assertThat(fieldNames(item0))
                .as("cities[0] keys leaked or missing. If this fails, you added a field to "
                    + "PublicCityItem. Verify it is safe to expose, then update this allowlist.")
                .isEqualTo(Set.of("city", "country", "eventCount"));
    }

    @Test
    void listCities_returnsEmptyArrayWhenNoCities() throws Exception {
        when(publicEventService.listCities()).thenReturn(List.of());

        mvc.perform(get("/api/v1/public/events/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // -- /genres --

    @Test
    void listGenres_returns200WithBodyAndCacheControl() throws Exception {
        when(publicEventService.listGenres()).thenReturn(List.of("ambient", "house", "techno"));

        mvc.perform(get("/api/v1/public/events/genres"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", CACHE_CONTROL_VALUE))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0]").value("ambient"))
                .andExpect(jsonPath("$[1]").value("house"))
                .andExpect(jsonPath("$[2]").value("techno"));
    }

    @Test
    void listGenres_returnsEmptyArrayWhenNoGenres() throws Exception {
        when(publicEventService.listGenres()).thenReturn(List.of());

        mvc.perform(get("/api/v1/public/events/genres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void list_bindsIncludeOngoingIntoQueryObject() throws Exception {
        when(publicEventService.list(any(PublicEventListQuery.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 20));

        mvc.perform(get("/api/v1/public/events").param("includeOngoing", "true"))
                .andExpect(status().isOk());

        ArgumentCaptor<PublicEventListQuery> captor = ArgumentCaptor.forClass(PublicEventListQuery.class);
        verify(publicEventService).list(captor.capture());
        assertThat(captor.getValue().includeOngoing()).isTrue();
    }

    @Test
    void list_includeOngoingDefaultsToFalse() throws Exception {
        when(publicEventService.list(any(PublicEventListQuery.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 20));

        mvc.perform(get("/api/v1/public/events"))
                .andExpect(status().isOk());

        ArgumentCaptor<PublicEventListQuery> captor = ArgumentCaptor.forClass(PublicEventListQuery.class);
        verify(publicEventService).list(captor.capture());
        assertThat(captor.getValue().includeOngoing()).isFalse();
    }

    @Test
    void list_bindsFreeOnlyIntoQueryObject() throws Exception {
        when(publicEventService.list(any(PublicEventListQuery.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 20));

        mvc.perform(get("/api/v1/public/events").param("freeOnly", "true"))
                .andExpect(status().isOk());

        ArgumentCaptor<PublicEventListQuery> captor = ArgumentCaptor.forClass(PublicEventListQuery.class);
        verify(publicEventService).list(captor.capture());
        assertThat(captor.getValue().freeOnly()).isTrue();
    }

    @Test
    void list_freeOnlyDefaultsToFalse() throws Exception {
        when(publicEventService.list(any(PublicEventListQuery.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 20));

        mvc.perform(get("/api/v1/public/events"))
                .andExpect(status().isOk());

        ArgumentCaptor<PublicEventListQuery> captor = ArgumentCaptor.forClass(PublicEventListQuery.class);
        verify(publicEventService).list(captor.capture());
        assertThat(captor.getValue().freeOnly()).isFalse();
    }

    // --- helpers ---

    private static Set<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(
                ((Iterable<String>) node::fieldNames).spliterator(), false
        ).collect(Collectors.toSet());
    }
}
