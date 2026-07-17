package com.imin.iminapi.marketing.dto;

import java.util.List;

/**
 * Raw model output for the subject-variants prompt — {@code {"variants": ["...","...","..."]}}.
 *
 * <p>Top-level record (matching {@code ConceptSet} / {@code MomentumDraftPayload}) so Spring AI's
 * {@code BeanOutputConverter} can derive its JSON schema and bind it via {@code .entity(...)}.
 * The service treats this as untrusted: it cleans, de-duplicates, and pads the list, so a short
 * or messy {@code variants} never reaches the caller.
 */
public record SubjectVariantsLlm(List<String> variants) {}
