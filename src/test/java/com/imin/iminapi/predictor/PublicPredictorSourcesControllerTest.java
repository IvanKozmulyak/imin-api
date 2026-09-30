package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The test profile has calendar sync and the date check off and weather on, so only Open-Meteo is active.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class PublicPredictorSourcesControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void unauthenticatedGetReturns200WithCacheHeader() throws Exception {
        mvc.perform(get("/api/v1/public/predictor/sources"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, s-maxage=300, stale-while-revalidate=60"));
    }

    @Test
    void bodyShape() throws Exception {
        mvc.perform(get("/api/v1/public/predictor/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewedOn").value("2026-09-30"))
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].id").value("open-meteo"))
                .andExpect(jsonPath("$.sources[0].name").value("Open-Meteo"))
                .andExpect(jsonPath("$.sources[0].usedFor", hasItem("weather")))
                .andExpect(jsonPath("$.sources[0].licence").value("CC BY 4.0"))
                .andExpect(jsonPath("$.sources[0].licenceUrl").value("https://creativecommons.org/licenses/by/4.0/"))
                .andExpect(jsonPath("$.sources[0].creditLine").value("Weather data by Open-Meteo.com (CC BY 4.0)"))
                .andExpect(jsonPath("$.sources[0].url").value("https://open-meteo.com/"))
                .andExpect(jsonPath("$.sources[0].status").value("active"))
                .andExpect(jsonPath("$.sources[0].lastUpdated").value(nullValue()))
                .andExpect(jsonPath("$.sources[0].gate").doesNotExist());
    }
}
