package com.imin.iminapi.predictor;

import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public data-source credits: unauthenticated, CDN-cacheable, and no source leaks its internal gate. */
@IminIntegrationTest
class PublicPredictorSourcesControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void unauthenticatedGetIsCacheableAndLeaksNoGate() throws Exception {
        mvc.perform(get("/api/v1/public/predictor/sources"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, s-maxage=300, stale-while-revalidate=60"))
                .andExpect(jsonPath("$.sources").isNotEmpty())
                .andExpect(jsonPath("$.sources[*].gate").doesNotExist());
    }
}
