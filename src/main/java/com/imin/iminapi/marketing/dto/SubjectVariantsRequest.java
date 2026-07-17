package com.imin.iminapi.marketing.dto;

/**
 * Optional body for {@code POST /api/v1/marketing/campaigns/{id}/subject-variants}.
 *
 * <p>The endpoint already loads all it needs (event + segment context) from the campaign
 * addressed by the path id, so the body is optional. The composer may pass
 * {@code currentSubject} — the subject line currently typed in the field but not yet saved —
 * so the generated alternatives are told to differ from it rather than repeat it.
 */
public record SubjectVariantsRequest(String currentSubject) {}
