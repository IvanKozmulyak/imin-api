package com.imin.iminapi.predictor.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared JSON mapper for the predictor module's TEXT/JSON columns (outcome tier
 * structure, ledger comparables/output, etc.). The codebase has no injectable
 * {@code ObjectMapper} bean — every component news up its own (see
 * {@code StringListJsonConverter}, {@code SecurityConfig}) — so the predictor
 * follows suit with one configured instance:
 * <ul>
 *   <li>{@link JavaTimeModule} + ISO dates (not epoch) so {@code Instant} sale windows
 *       serialize as readable timestamps;</li>
 *   <li>lenient on unknown properties so a schema addition never breaks a re-read of an
 *       older row.</li>
 * </ul>
 */
public final class PredictorJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private PredictorJson() {}
}
