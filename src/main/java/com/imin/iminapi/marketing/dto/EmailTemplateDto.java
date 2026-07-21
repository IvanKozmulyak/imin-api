package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.model.CampaignEmailTemplate;
import com.imin.iminapi.marketing.template.ResolvedTemplate;
import com.imin.iminapi.marketing.template.TemplateTokens;

/**
 * One selectable email template for the composer's picker
 * (GET /api/v1/marketing/email-templates). Covers both builtins and org-saved templates:
 *
 * <ul>
 *   <li>{@code key} — the value that goes into {@code campaigns.template_key}: a builtin key
 *       ({@code classic}…) or a saved template's UUID string.</li>
 *   <li>{@code builtin} — true for the four code-defined templates (not deletable).</li>
 *   <li>{@code source} — {@code builtin} | {@code ai} | {@code custom}.</li>
 *   <li>{@code tokens} — the full token set, so the FE renders swatch previews and applies
 *       the palette to the live inbox preview without a second round-trip.</li>
 * </ul>
 */
public record EmailTemplateDto(
        String key,
        String name,
        String source,
        boolean builtin,
        TemplateTokens tokens
) {

    public static EmailTemplateDto fromBuiltin(ResolvedTemplate t) {
        return new EmailTemplateDto(t.key(), t.name(), t.source(), true, t.tokens());
    }

    public static EmailTemplateDto fromRow(CampaignEmailTemplate row) {
        return new EmailTemplateDto(
                row.getId().toString(), row.getName(), row.getSource(), false, row.getTokens());
    }
}
