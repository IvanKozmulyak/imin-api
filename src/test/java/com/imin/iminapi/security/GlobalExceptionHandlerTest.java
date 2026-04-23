package com.imin.iminapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.TokenService;
import org.springframework.boot.security.saml2.autoconfigure.Saml2RelyingPartyAutoConfiguration;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.DummyController.class,
        excludeAutoConfiguration = Saml2RelyingPartyAutoConfiguration.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.DummyController.class})
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean AuthSessionRepository authSessionRepository;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TokenService tokenService;

    @RestController
    @RequestMapping("/__test")
    static class DummyController {
        @GetMapping("/notfound")
        String notFound() { throw ApiException.notFound("Event"); }

        @GetMapping("/forbidden")
        String forbidden() { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed"); }

        @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
        String validate(@org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid Body b) { return "ok"; }

        record Body(@jakarta.validation.constraints.NotBlank String name) {}
    }

    @Test
    @WithMockUser
    void apiException_returns_envelope() throws Exception {
        mvc.perform(get("/__test/notfound").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Event not found"));
    }

    @Test
    @WithMockUser
    void response_status_403_maps_to_FORBIDDEN() throws Exception {
        mvc.perform(get("/__test/forbidden").with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser
    void validation_error_returns_field_invalid_with_fields() throws Exception {
        mvc.perform(post("/__test/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.name").exists());
    }
}
