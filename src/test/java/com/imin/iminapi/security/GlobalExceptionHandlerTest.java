package com.imin.iminapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The advice alone over a dummy controller: every error leaves in the single {@code $.error} envelope. */
class GlobalExceptionHandlerTest {

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DummyController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    final ObjectMapper om = new ObjectMapper();

    @RestController
    @RequestMapping("/__test")
    static class DummyController {
        @GetMapping("/notfound")
        String notFound() { throw ApiException.notFound("Event"); }

        @GetMapping("/forbidden")
        String forbidden() { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed"); }

        @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
        String validate(@org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid Body b) { return "ok"; }

        @PostMapping("/upload")
        String upload(@RequestPart("file") org.springframework.web.multipart.MultipartFile file) { return "ok"; }

        @GetMapping("/param")
        String param(@RequestParam String q) { return q; }

        @PostMapping("/too-big")
        String tooBig() { throw new MaxUploadSizeExceededException(2L * 1024 * 1024); }

        record Body(@jakarta.validation.constraints.NotBlank String name) {}
    }

    @Test
    void apiException_returns_envelope() throws Exception {
        mvc.perform(get("/__test/notfound"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Event not found"));
    }

    @Test
    void response_status_403_maps_to_FORBIDDEN() throws Exception {
        mvc.perform(get("/__test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void validation_error_returns_field_invalid_with_fields() throws Exception {
        mvc.perform(post("/__test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.name").exists());
    }

    // ---- The four framework MVC exceptions the Throwable catch-all used to eat ----
    //
    // This advice is @Order(HIGHEST_PRECEDENCE) with an @ExceptionHandler(Throwable.class),
    // so ExceptionHandlerExceptionResolver matches it before Spring's own
    // DefaultHandlerExceptionResolver ever runs. None of the four extends
    // ResponseStatusException, so they all landed on handleAny: 500 INTERNAL plus a
    // spurious log.error("Unhandled exception") for what is an ordinary client mistake.

    @Test
    void wrong_verb_returns_405_in_the_envelope() throws Exception {
        mvc.perform(get("/__test/validate"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void wrong_content_type_returns_415_in_the_envelope() throws Exception {
        mvc.perform(post("/__test/validate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=ada"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void missing_multipart_part_returns_400_naming_the_part() throws Exception {
        mvc.perform(multipart("/__test/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.file").exists());
    }

    @Test
    void missing_required_query_param_returns_400_naming_the_param() throws Exception {
        mvc.perform(get("/__test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.q").exists());
    }

    @Test
    void max_upload_size_maps_to_413_with_envelope() throws Exception {
        mvc.perform(post("/__test/too-big"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.file").exists());
    }
}
