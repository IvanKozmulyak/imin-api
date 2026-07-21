package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.template.TemplateTokens;

/**
 * Raw model output for the template-generation prompt — a short {@code name} plus a
 * {@link TemplateTokens} set. Top-level record so Spring AI's {@code BeanOutputConverter}
 * can derive the JSON schema and bind it via {@code .entity(...)} (mirrors
 * {@code SubjectVariantsLlm} / {@code MomentumDraftPayload}).
 *
 * <p>Untrusted: the service runs {@code tokens} through {@code TemplateTokenValidator}
 * (hex-only colours, whitelisted header style) before anything is persisted or rendered.
 */
public record GeneratedTemplateLlm(String name, TemplateTokens tokens) {}
