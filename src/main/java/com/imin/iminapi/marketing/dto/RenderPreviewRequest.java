package com.imin.iminapi.marketing.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /campaigns/{id}/render-preview. Every content field is optional: absent renders the saved value, present
 * (even empty) renders the on-screen value, and is accepted only on a draft. Bounds mirror the campaign columns;
 * the body bound keeps an oversize paste away from the renderer.
 *
 * @param recipient {@code named} (default) greets with the caller's own first name, {@code unnamed} with none
 */
public record RenderPreviewRequest(
        @Size(max = 200, message = "must be at most 200 characters") String subject,
        @Size(max = 200, message = "must be at most 200 characters") String preheader,
        @Size(max = 50_000, message = "must be at most 50000 characters") String bodyMd,
        @Size(max = 64, message = "must be at most 64 characters") String templateKey,
        @Pattern(regexp = "en|es|fr|uk", message = "must be one of en, es, fr, uk") String locale,
        @Pattern(regexp = "named|unnamed", message = "must be named or unnamed") String recipient
) {

    /** The saved campaign, greeted by name: what an empty body means. */
    public static final RenderPreviewRequest SAVED = new RenderPreviewRequest(null, null, null, null, null, null);

    /** Whether any on-screen value stands in for a saved one. */
    public boolean hasOverrides() {
        return subject != null || preheader != null || bodyMd != null || templateKey != null || locale != null;
    }

    public boolean unnamed() {
        return "unnamed".equals(recipient);
    }
}
