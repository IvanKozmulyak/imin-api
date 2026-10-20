package com.imin.iminapi.controller.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.auth.OrganizerTerms;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Email sign-up requires the current organizer terms, like setup after a social sign-up, and records them. */
@IminIntegrationTest
class SignupTermsTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper om = new ObjectMapper();

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("acceptedTerms", null, "acceptedTerms", "must not be null"),
                Arguments.of("acceptedTerms", false, "acceptedTerms", "must be true"),
                Arguments.of("termsVersion", null, "termsVersion", "must not be blank"),
                Arguments.of("termsVersion", "2020-01-01", "termsVersion", OrganizerTerms.STALE_MESSAGE));
    }

    @ParameterizedTest(name = "{0}={1} -> {2}")
    @MethodSource("refused")
    void signupWithoutTheCurrentTerms_isRefusedAndCreatesNothing(String key, Object value, String field,
                                                                 String message) throws Exception {
        String email = fx.email("signup");
        Map<String, Object> body = validBody(email);
        body.put(key, value);

        signup(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields." + field).value(message));

        assertThat(users.existsByEmailLower(email.toLowerCase())).isFalse();
    }

    /** 1.1 is accepted before it becomes current, so the dashboard can ship it without 400ing every sign-up. */
    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({"' 1.0 ', 1.0", "' 1.1 ', 1.1"})
    void signupWithAKnownTermsVersion_recordsTheMatchedVersion(String sent, String stored) throws Exception {
        String email = fx.email("signup");
        Instant before = Instant.now();
        Map<String, Object> body = validBody(email);
        body.put("termsVersion", sent);

        signup(body).andExpect(status().isOk());

        Map<String, Object> user = jdbc.queryForMap(
                "select terms_accepted_at, terms_version from users where email_lower = ?", email.toLowerCase());
        assertThat(user.get("terms_version")).as("the matched, trimmed id is stored").isEqualTo(stored);
        assertThat(((java.sql.Timestamp) user.get("terms_accepted_at")).toInstant())
                .isAfterOrEqualTo(before.minusSeconds(1));
    }

    private static Map<String, Object> validBody(String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", "correct-horse-42");
        body.put("firstName", "Mira");
        body.put("lastName", "Lutz");
        body.put("orgName", "Lutz Nights");
        body.put("country", "FR");
        body.put("acceptedTerms", true);
        body.put("termsVersion", OrganizerTerms.CURRENT_VERSION);
        return body;
    }

    private ResultActions signup(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }
}
