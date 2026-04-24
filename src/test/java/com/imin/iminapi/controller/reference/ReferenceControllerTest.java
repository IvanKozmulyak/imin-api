package com.imin.iminapi.controller.reference;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class ReferenceControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void countries_is_public_sorted_and_cached() throws Exception {
        mvc.perform(get("/api/v1/reference/countries"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=86400")))
                .andExpect(header().string("Cache-Control", containsString("public")))
                // Contains expected entries with ISO alpha-2 codes
                .andExpect(jsonPath("$[?(@.code == 'FR')].name").value(contains("France")))
                .andExpect(jsonPath("$[?(@.code == 'DE')].name").value(contains("Germany")))
                .andExpect(jsonPath("$[?(@.code == 'GB')].name").exists())
                // All codes are 2 uppercase letters
                .andExpect(jsonPath("$[*].code", everyItem(matchesPattern("^[A-Z]{2}$"))))
                // Alphabetical by name — first entry should be "Afghanistan" (A…)
                .andExpect(jsonPath("$[0].name", startsWith("A")));
    }
}
