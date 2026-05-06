package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.dto.publicapi.PublicOrganizationDto;
import com.imin.iminapi.dto.publicapi.PublicTierDto;
import com.imin.iminapi.dto.publicapi.PublicVenueDto;
import com.imin.iminapi.security.ApiException;
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
import static org.mockito.Mockito.*;
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
        PublicVenueDto venue = new PublicVenueDto("Venue X", "123 Main St", "Berlin", "10115", "DE");
        PublicOrganizationDto org = new PublicOrganizationDto("Acme Events", "acme-events");
        PublicTierDto tier = new PublicTierDto(
                TIER_ID, "General Admission", "standard", 2500, "EUR",
                Instant.parse("2026-06-01T00:00:00Z"), 1, 100, true, false, false);

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
                "https://cdn.example.com/cover.jpg",
                "https://cdn.example.com/poster.jpg",
                null,
                "EUR",
                Instant.parse("2026-02-01T00:00:00Z"),
                Instant.parse("2026-06-30T00:00:00Z"),
                false,
                2,
                10,
                org,
                List.of(tier)
        );
    }

    @Test
    void get_returns200WithBodyAndCacheControl() throws Exception {
        when(publicEventService.get(EVENT_ID)).thenReturn(sampleResponse());

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
        when(publicEventService.get(EVENT_ID)).thenThrow(ApiException.notFound("Event"));

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
        when(publicEventService.get(EVENT_ID)).thenReturn(sampleResponse());

        MvcResult result = mvc.perform(get("/api/v1/public/events/" + EVENT_ID))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        Set<String> actualRootKeys = fieldNames(root);
        Set<String> expectedRootKeys = Set.of(
                "id", "slug", "name", "status", "publishedAt",
                "genre", "type", "description", "startsAt", "endsAt",
                "timezone", "venue", "coverUrl", "posterUrl", "videoUrl",
                "currency", "onSaleAt", "saleClosesAt",
                "squadsEnabled", "minSquadSize", "squadDiscountPct",
                "organization", "tiers"
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

        JsonNode tier0 = root.get("tiers").get(0);
        Set<String> actualTierKeys = fieldNames(tier0);
        Set<String> expectedTierKeys = Set.of(
                "id", "name", "kind", "priceMinor", "currency",
                "saleClosesAt", "sortOrder", "remaining",
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
        when(publicEventService.get(any())).thenReturn(sampleResponse());

        mvc.perform(get("/api/v1/public/events/" + EVENT_ID))
                // No Authorization header — must not be blocked by auth
                .andExpect(status().isOk());
    }

    // --- helpers ---

    private static Set<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(
                ((Iterable<String>) node::fieldNames).spliterator(), false
        ).collect(Collectors.toSet());
    }
}
