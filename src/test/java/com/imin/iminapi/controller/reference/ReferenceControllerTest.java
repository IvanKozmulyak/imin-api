package com.imin.iminapi.controller.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.util.StripeSupportedCountries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IminIntegrationTest
class ReferenceControllerTest {

    @Autowired MockMvc mvc;

    /** Onboarding must never offer a country Stripe Connect rejects (the UA incident). */
    @Test
    void countries_are_only_stripe_supported_sorted_by_name_and_publicly_cached() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/reference/countries"))
                .andExpect(status().isOk())
                .andReturn();

        String cache = result.getResponse().getHeader("Cache-Control");
        assertThat(cache).contains("public").contains("max-age=86400");

        List<Map.Entry<String, String>> countries = new ArrayList<>();
        for (JsonNode c : new ObjectMapper().readTree(result.getResponse().getContentAsString())) {
            countries.add(Map.entry(c.get("code").asText(), c.get("name").asText()));
        }
        assertThat(countries).isNotEmpty();
        assertThat(countries).allSatisfy(c -> {
            assertThat(c.getKey()).matches("^[A-Z]{2}$");
            assertThat(StripeSupportedCountries.isSupported(c.getKey())).as(c.getKey()).isTrue();
        });
        assertThat(countries).isSortedAccordingTo(Comparator.comparing(Map.Entry::getValue));

        Map<String, String> byCode = countries.stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(byCode).containsEntry("FR", "France").containsEntry("DE", "Germany")
                .containsKeys("GB", "US", "CH");
        // Real ISO codes the JDK offers, so their absence is the filter's doing.
        assertThat(Arrays.asList(java.util.Locale.getISOCountries())).contains("UA", "JP", "AU");
        assertThat(byCode).doesNotContainKeys("UA", "JP", "AU");
    }
}
