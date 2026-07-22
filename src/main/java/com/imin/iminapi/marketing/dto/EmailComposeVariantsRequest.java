package com.imin.iminapi.marketing.dto;

/**
 * Optional body for {@code POST /api/v1/marketing/campaigns/{id}/compose-variants}.
 *
 * <p>The endpoint assembles all of its grounding (linked event, org, segment, template tone,
 * existing draft) server-side from the campaign addressed by the path id, so the body is
 * optional.
 *
 * @param count how many whole-email variants to generate — clamped to 1..3 server-side
 *              (default 3 when null/out of range).
 * @param hint  a free-text nudge for tone/angle (e.g. "lean on the early-bird deadline"),
 *              clamped to 200 chars server-side.
 */
public record EmailComposeVariantsRequest(Integer count, String hint) {}
