package com.imin.iminapi.marketing.dto;

import java.util.UUID;

/**
 * Body for {@code POST /api/v1/marketing/email-templates/generate} (spec §5).
 *
 * <p>Both fields optional. {@code eventId} grounds the palette in a specific event
 * (title/vibe/date) when given; {@code hint} is an optional free-text style nudge
 * ("neon and loud", "elegant, editorial"). Org name + stored brand colours are always
 * pulled server-side from the auth context — never trusted from the body.
 */
public record GenerateTemplateRequest(UUID eventId, String hint) {}
