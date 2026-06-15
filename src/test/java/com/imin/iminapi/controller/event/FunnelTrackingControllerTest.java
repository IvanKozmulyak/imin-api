package com.imin.iminapi.controller.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class FunnelTrackingControllerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void track_is_public_and_returns_204_even_for_unknown_event() throws Exception {
        mockMvc.perform(post("/api/v1/public/events/{id}/track", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"PAGE_VIEW\",\"anonId\":\"sess-1\"}"))
                .andExpect(status().isNoContent());
    }
}
