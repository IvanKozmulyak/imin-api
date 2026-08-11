package com.imin.iminapi.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.refund.dto.PublicRefundFormResponse;
import com.imin.iminapi.refund.dto.PublicRefundSubmitRequest;
import com.imin.iminapi.refund.dto.PublicRefundSubmitResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class PublicRefundRequestControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    @MockitoBean RefundRequestService service;

    @Test
    void post_link_always_returns_200_and_calls_service() throws Exception {
        mvc.perform(post("/api/v1/public/refund-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"buyer@example.com\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
        verify(service).requestLink(anyString(), anyString());
    }

    @Test
    void post_link_with_empty_body_returns_200() throws Exception {
        mvc.perform(post("/api/v1/public/refund-requests")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    @Test
    void submit_returns_201_on_success() throws Exception {
        PublicRefundSubmitResponse resp = new PublicRefundSubmitResponse(
            UUID.randomUUID(), "REQ-8K2M-26", "pending", Instant.now());
        when(service.submitByToken(anyString(), any())).thenReturn(resp);

        String body = json.writeValueAsString(new PublicRefundSubmitRequest(
            RefundRequestReason.CANT_ATTEND, "Can't make it.", null));
        mvc.perform(post("/api/v1/public/refund-requests/by-token/t1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("pending"))
            // The buyer receipt quotes this, not the UUID.
            .andExpect(jsonPath("$.reference").value("REQ-8K2M-26"));
    }

    // -- allow-listed response keys -------------------------------------------------------
    //
    // Both of these routes are PUBLIC. They are token-gated rather than anonymous, but the
    // token arrives by email and ends up in a URL, so the blast radius of an accidentally
    // widened DTO is the same as on /public/events and /public/orders — which is why those
    // have had key-set guardrails and these have not. If one of these fails, you added a
    // field to a public refund DTO: verify it is safe to expose to whoever holds the link,
    // then update the allowlist.

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void submit_responseHasOnlyAllowListedKeys() throws Exception {
        when(service.submitByToken(anyString(), any())).thenReturn(new PublicRefundSubmitResponse(
            UUID.randomUUID(), "REQ-8K2M-26", "pending", Instant.parse("2026-08-11T10:00:00Z")));

        String body = json.writeValueAsString(new PublicRefundSubmitRequest(
            RefundRequestReason.CANT_ATTEND, "Can't make it.", null));
        MvcResult result = mvc.perform(post("/api/v1/public/refund-requests/by-token/t1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();

        assertThat(fieldNames(json.readTree(result.getResponse().getContentAsString())))
            .as("PublicRefundSubmitResponse keys leaked or missing")
            .isEqualTo(Set.of("id", "reference", "status", "submittedAt"));
    }

    @Test
    void form_responseHasOnlyAllowListedKeys() throws Exception {
        when(service.lookupByToken(anyString())).thenReturn(new PublicRefundFormResponse(
            UUID.randomUUID(),
            new PublicRefundFormResponse.EventSummary(
                "Great Event", Instant.parse("2026-09-01T20:00:00Z"), "Europe/Berlin",
                "Berghain", "EUR"),
            List.of(new PublicRefundFormResponse.TicketLine(UUID.randomUUID(), "GA", 2500)),
            2500, "EUR", PublicRefundFormResponse.defaultReasons(), "REQ-8K2M-26"));

        MvcResult result = mvc.perform(get("/api/v1/public/refund-requests/by-token/t1"))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode root = json.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(root))
            .as("PublicRefundFormResponse keys leaked or missing")
            .isEqualTo(Set.of("orderId", "event", "tickets", "estimatedRefundMinor",
                "currency", "reasons", "openRequestReference"));
        assertThat(fieldNames(root.get("event")))
            .as("PublicRefundFormResponse.EventSummary keys leaked or missing")
            .isEqualTo(Set.of("name", "startsAt", "timezone", "venueName", "currency"));
        assertThat(fieldNames(root.get("tickets").get(0)))
            .as("PublicRefundFormResponse.TicketLine keys leaked or missing")
            .isEqualTo(Set.of("id", "tierName", "faceMinor"));
    }
}
