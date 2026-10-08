package com.imin.iminapi.controller.org;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Organization settings over the real OrgService: the legal identity bounds and the saved, trimmed values. */
@IminIntegrationTest
class OrgControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgs;

    private final ObjectMapper om = new ObjectMapper();

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }


    static Stream<Arguments> invalidLegalIdentity() {
        return Stream.of(
                Arguments.of(Map.of("legalName", "n".repeat(201)), "legalName", null),
                Arguments.of(Map.of("legalContact", "c".repeat(321)), "legalContact", null),
                Arguments.of(Map.of("legalName", "Night SAS\r\nBcc: x@y.z"), "legalName", "must be a single line"),
                Arguments.of(Map.of("legalContact", "1 rue X\n57000 Metz"), "legalContact", "must be a single line"));
    }

    /** The legal identity is printed in campaign footers; a newline would inject a header line there. */
    @ParameterizedTest
    @MethodSource("invalidLegalIdentity")
    void an_invalid_legal_identity_is_FIELD_INVALID_and_not_saved(Map<String, String> body, String field,
                                                                  String message) throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);

        var r = mvc.perform(patch("/api/v1/org").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields." + field).exists());
        if (message != null) r.andExpect(jsonPath("$.error.fields." + field).value(message));

        Organization after = orgs.findById(org.getId()).orElseThrow();
        assertThat(after.getLegalName()).isNull();
        assertThat(after.getLegalContact()).isNull();
    }

    static Stream<Arguments> savedLegalIdentity() {
        String name = "n".repeat(200);
        return Stream.of(
                Arguments.of(name, "c".repeat(320), name, "c".repeat(320)),
                Arguments.of("  " + name + " \n", "\t c ", name, "c"));
    }

    /** Bounds are measured after trimming, and what is saved is what GET then serves. */
    @ParameterizedTest
    @MethodSource("savedLegalIdentity")
    void a_legal_identity_within_bounds_is_saved_trimmed(String legalName, String legalContact,
                                                         String savedName, String savedContact) throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);

        mvc.perform(patch("/api/v1/org").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("legalName", legalName, "legalContact", legalContact))))
                .andExpect(status().isOk());

        Organization after = orgs.findById(org.getId()).orElseThrow();
        assertThat(after.getLegalName()).isEqualTo(savedName);
        assertThat(after.getLegalContact()).isEqualTo(savedContact);
        mvc.perform(get("/api/v1/org").with(as(owner)))
                .andExpect(jsonPath("$.legalName").value(savedName))
                .andExpect(jsonPath("$.legalContact").value(savedContact));
    }
}
