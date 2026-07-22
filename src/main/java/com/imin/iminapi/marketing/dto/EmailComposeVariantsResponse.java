package com.imin.iminapi.marketing.dto;

import java.util.List;

/**
 * Response for {@code POST /api/v1/marketing/campaigns/{id}/compose-variants}.
 *
 * <p>{@code variants} is 1..N whole-email drafts (subject + preheader + Markdown body), each
 * already validated server-side: subject ≤80 chars and not spammy all-caps, preheader ≤120,
 * body is plain Markdown with no HTML, and the {@code {{tickets_button}}} CTA token appears
 * exactly once when the campaign has a linked event (and never when it does not). The
 * unsubscribe footer is NOT part of the body — the renderer appends it at send time.
 */
public record EmailComposeVariantsResponse(List<EmailVariant> variants) {

    /** One ready-to-apply email draft. */
    public record EmailVariant(String subject, String preheader, String bodyMarkdown) {}
}
