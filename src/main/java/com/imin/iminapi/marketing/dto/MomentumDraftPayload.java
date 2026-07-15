package com.imin.iminapi.marketing.dto;

/**
 * The pre-drafted campaign content stored in momentum_suggestions.draft_payload (spec §6.3).
 * Generated once by the LLM (subject/preheader/bodyMd/why); posterUrl + segmentId are set by
 * MomentumCopyGenerator (never by the LLM). Text-only — no image composition (spec §6.3).
 *
 * @param posterUrl the event's existing 4:5 poster as email header (crop only), or null
 */
public record MomentumDraftPayload(
        String subject,
        String preheader,
        String bodyMd,
        String segmentId,
        String posterUrl,
        String why) {}
