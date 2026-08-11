package com.imin.iminapi.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
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

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
}
