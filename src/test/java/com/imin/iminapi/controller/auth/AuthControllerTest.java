package com.imin.iminapi.controller.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The organizer auth HTTP contract over the real AuthService; the service rules are owned by AuthServiceTest. */
@IminIntegrationTest
class AuthControllerTest {

    private static final String PASSWORD = "lovelace12345";

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired RecordingEmailService mail;
    @Autowired MutableClock clock;

    private final ObjectMapper om = new ObjectMapper();

    private User verifiedOwner() {
        User u = fx.owner(fx.org());
        u.setPasswordHash(hasher.hash(PASSWORD));
        u.setVerifiedAt(clock.instant());
        return users.save(u);
    }

    private ResultActions postJson(String path, Map<String, ?> body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("/api/v1/auth/signup", Map.of("email", "a@b.com", "password", "short",
                        "firstName", "Ada", "lastName", "Lovelace", "orgName", "X", "country", "GB"),
                        "passwordPolicyValid"),
                Arguments.of("/api/v1/auth/verify-email", Map.of("email", "ada@example.com", "code", "abcdef"), null),
                Arguments.of("/api/v1/auth/reset-password", Map.of("token", "abc", "newPassword", "short"), null));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void an_invalid_body_is_the_FIELD_INVALID_envelope(String path, Map<String, ?> body, String field) throws Exception {
        ResultActions r = postJson(path, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
        if (field != null) r.andExpect(jsonPath("$.error.fields." + field).exists());
    }

    @Test
    void me_without_a_token_is_AUTH_MISSING() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
    }

    /** Known and unknown addresses get the same 200; only the known one receives a reset mail. */
    @Test
    void forgot_password_is_enumeration_safe() throws Exception {
        User known = verifiedOwner();
        String unknown = fx.email("nobody");

        postJson("/api/v1/auth/forgot-password", Map.of("email", unknown)).andExpect(status().isOk());
        postJson("/api/v1/auth/forgot-password", Map.of("email", known.getEmail())).andExpect(status().isOk());

        assertThat(mail.sent()).filteredOn(m -> m.to().equals(unknown)).isEmpty();
        assertThat(mail.sent()).filteredOn(m -> m.to().equals(known.getEmail())).hasSize(1);
    }

    /** Routes whose rules AuthServiceTest owns, over the real service, answer their documented status. */
    @ParameterizedTest
    @CsvSource({"/api/v1/auth/resend-verification, 200", "/api/v1/auth/login, 401"})
    void auth_routes_answer_their_status(String path, int expected) throws Exception {
        postJson(path, Map.of("email", fx.email("nobody"), "password", "not-" + PASSWORD))
                .andExpect(status().is(expected));
    }
}
