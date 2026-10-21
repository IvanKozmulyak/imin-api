package com.imin.iminapi.marketing.dto;

/**
 * The pre-drafted campaign content stored in momentum_suggestions.draft_payload (spec §6.3).
 * Generated once by the LLM (subject/preheader/bodyMd/why); posterUrl + segmentId are set by
 * MomentumCopyGenerator (never by the LLM). Text-only — no image composition (spec §6.3).
 *
 * @param posterUrl the event's existing 4:5 poster as email header (crop only), or null
 * @param locale    language the copy is written in (en|es|fr|uk); set by the server, never the model
 */
public record MomentumDraftPayload(
        String subject,
        String preheader,
        String bodyMd,
        String segmentId,
        String posterUrl,
        String why,
        String locale) {

    public MomentumDraftPayload(String subject, String preheader, String bodyMd, String segmentId,
                                String posterUrl, String why) {
        this(subject, preheader, bodyMd, segmentId, posterUrl, why, null);
    }
}
