package com.imin.iminapi.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API has exactly one error envelope, so it must have exactly one advice that emits it.
 *
 * <p>Every consumer — imin-webapp's {@code src/shared/api/client.ts}, imin-public — reads
 * {@code $.error.code} off the {@link ApiError} object shape. A second
 * {@code @RestControllerAdvice} is a second envelope waiting for an ordering change: the one
 * this test was written to remove ({@code EventCreationExceptionHandler}) emitted
 * {@code {"error": "validation_failed", "fields": …}} with {@code error} as a STRING, and
 * duplicated {@code handleValidation}. It lost only because it was un-{@code @Order}ed and so
 * ranked below {@link GlobalExceptionHandler}'s {@code HIGHEST_PRECEDENCE} — one {@code @Order}
 * tweak away from silently changing the validation envelope on both frontends.
 *
 * <p>Read from source rather than from the context: an advice that is present but currently
 * losing is exactly the case that has to fail here, and the context cannot tell you which one
 * would win.
 */
class SingleErrorEnvelopeAdviceTest {

    @Test
    void globalExceptionHandlerIsTheOnlyControllerAdvice() throws IOException {
        Set<String> advices = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                // Annotation use, not the word in a comment: the annotation sits at column 0.
                if (src.contains("\n@RestControllerAdvice") || src.contains("\n@ControllerAdvice")) {
                    advices.add(file.getFileName().toString());
                }
            }
        }

        assertThat(advices)
                .as("""
                    More than one @ControllerAdvice emits error bodies. Both frontends read
                    $.error.code off the ApiError shape, so a second advice is a second envelope
                    that an @Order change can promote without anyone noticing. Fold the handlers
                    into GlobalExceptionHandler and throw ApiException instead.""")
                .containsExactly("GlobalExceptionHandler.java");
    }
}
