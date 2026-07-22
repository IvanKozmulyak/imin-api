package com.imin.iminapi.marketing.dto;

import java.util.List;

/**
 * Raw model output for the whole-email compose-variants prompt —
 * {@code {"variants":[{"subject":"…","preheader":"…","bodyMarkdown":"…"}]}}.
 *
 * <p>Top-level record (matching {@code SubjectVariantsLlm} / {@code GeneratedTemplateLlm}) so
 * Spring AI's {@code BeanOutputConverter} can derive its JSON schema and bind it via
 * {@code .entity(...)}. The service treats this as UNTRUSTED: it strips HTML from bodies,
 * enforces length/emptiness/spam rules, drops invalid variants, and normalizes the
 * {@code {{tickets_button}}} token — so messy or unsafe output never reaches the caller.
 */
public record EmailComposeVariantsLlm(List<Variant> variants) {

    /** One generated email: a subject line, a preheader, and a Markdown body. */
    public record Variant(String subject, String preheader, String bodyMarkdown) {}
}
