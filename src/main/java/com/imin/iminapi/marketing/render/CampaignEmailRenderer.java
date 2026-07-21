package com.imin.iminapi.marketing.render;

import com.imin.iminapi.marketing.template.BuiltinTemplates;
import com.imin.iminapi.marketing.template.ResolvedTemplate;
import com.imin.iminapi.marketing.template.TemplateHeader;
import com.imin.iminapi.marketing.template.TemplatePalette;
import com.imin.iminapi.marketing.template.TemplateTokens;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/**
 * Renders a campaign's markdown body into a branded, template-driven HTML shell + a
 * plain-text variant, with the mandatory RFC 8058 unsubscribe footer and UTM link
 * rewriting (spec §3/§4).
 *
 * <p><b>Template-driven.</b> A {@link ResolvedTemplate}'s {@link TemplateTokens} drive a
 * 600px table shell with ALL styles inlined (no external CSS; a Google Fonts link with a
 * system fallback is the only remote reference, and clients that block it degrade to the
 * fallback stack). Builtins and org/AI templates share this one path. The header is one of
 * {@code wordmark} | {@code banner} | {@code posterHero}; {@code posterHero} degrades to a
 * {@code banner} when the campaign has no poster.
 *
 * <p><b>Sanitization.</b> commonmark runs with {@code escapeHtml(true)}, so raw inline HTML
 * in the body is escaped, not emitted. Token colours are validated hex upstream
 * ({@code TemplateTokenValidator}), so nothing untrusted reaches an inline {@code style=}.
 * The renderer REFUSES to produce output without an unsubscribe URL, so the send path can
 * never omit the footer. UTM rewriting is applied to the BODY ONLY (before the shell is
 * assembled), so the unsubscribe link — itself an imin.wtf URL — is never UTM-tagged.
 */
@Component
public class CampaignEmailRenderer {

    public record Rendered(String html, String text) {}

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().escapeHtml(true).build();
    private final UtmLinkRewriter utmRewriter;

    public CampaignEmailRenderer(UtmLinkRewriter utmRewriter) {
        this.utmRewriter = utmRewriter;
    }

    /**
     * Back-compat overload: renders with the default (classic) builtin and no poster.
     * Kept so callers/tests that predate the template system keep working.
     */
    public Rendered render(String subject, String preheader, String bodyMd,
                           String campaignId, String channel, String unsubscribeUrl) {
        return render(subject, preheader, bodyMd, campaignId, channel, unsubscribeUrl,
                BuiltinTemplates.defaultTemplate(), null, null);
    }

    /**
     * @param template   the resolved template whose tokens drive the shell
     * @param brandName  the organizer's brand/display name for the header (null → header text
     *                   falls back to the template's {@code header.title}, then to no text)
     * @param posterUrl  the linked event's poster URL, used ONLY by the {@code posterHero}
     *                   header; null/blank makes {@code posterHero} degrade to {@code banner}
     */
    public Rendered render(String subject, String preheader, String bodyMd,
                           String campaignId, String channel, String unsubscribeUrl,
                           ResolvedTemplate template, String brandName, String posterUrl) {
        if (unsubscribeUrl == null || unsubscribeUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Cannot render campaign email without an unsubscribe URL (footer is mandatory)");
        }
        ResolvedTemplate tpl = template != null ? template : BuiltinTemplates.defaultTemplate();
        TemplateTokens tokens = tpl.tokens();
        TemplatePalette pal = tokens.palette();

        Node doc = parser.parse(bodyMd == null ? "" : bodyMd);
        String bodyHtml = htmlRenderer.render(doc);
        bodyHtml = utmRewriter.rewrite(bodyHtml, channel, campaignId);
        // Inline an accent colour onto body links so they read as links in every client.
        bodyHtml = styleBodyLinks(bodyHtml, pal.accent());

        String preheaderBlock = (preheader == null || preheader.isBlank()) ? ""
                : "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">"
                  + escape(preheader) + "</div>";

        String headerBlock = headerBlock(tokens, brandName, posterUrl);

        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>"
                + "<link href=\"https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;600;700&family=Space+Mono&display=swap\" rel=\"stylesheet\"/>"
                + "</head>"
                + "<body style=\"margin:0;padding:0;background:" + pal.bg() + ";"
                + "font-family:" + tokens.fontStack() + ";color:" + pal.text() + ";"
                + "-webkit-font-smoothing:antialiased;\">"
                + preheaderBlock
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"background:" + pal.bg() + ";padding:32px 16px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"max-width:600px;width:100%;background:" + pal.card() + ";"
                + "border-radius:10px;overflow:hidden;\">"
                + headerBlock
                + "<tr><td style=\"padding:28px 32px;font-size:15px;line-height:1.6;color:"
                + pal.text() + ";\">" + bodyHtml + "</td></tr>"
                + footerBlock(pal, unsubscribeUrl)
                + "</table></td></tr></table></body></html>";

        String text = (bodyMd == null ? "" : bodyMd)
                + "\n\n---\nUnsubscribe: " + unsubscribeUrl;

        return new Rendered(html, text);
    }

    /** The header row per {@code header.style}, with the posterHero→banner fallback. */
    private String headerBlock(TemplateTokens tokens, String brandName, String posterUrl) {
        TemplateHeader header = tokens.header();
        TemplatePalette pal = tokens.palette();
        String style = header == null ? TemplateHeader.WORDMARK : header.style();
        String title = headerText(header, brandName);

        if (TemplateHeader.POSTER_HERO.equals(style)) {
            if (posterUrl != null && !posterUrl.isBlank()) {
                // Full-bleed hero; alt kept generic so a blocked image degrades gracefully.
                return "<tr><td style=\"padding:0;\">"
                        + "<img src=\"" + escape(posterUrl.trim()) + "\" width=\"600\" alt=\"\" "
                        + "style=\"display:block;width:100%;max-width:600px;height:auto;border:0;\"/>"
                        + "</td></tr>";
            }
            style = TemplateHeader.BANNER; // graceful fallback: no poster → banner
        }

        if (TemplateHeader.BANNER.equals(style)) {
            String label = title != null ? escape(title) : "";
            return "<tr><td style=\"background:" + pal.accent() + ";padding:22px 32px;\">"
                    + "<div style=\"font-size:16px;font-weight:700;letter-spacing:-0.2px;color:"
                    + pal.buttonText() + ";\">" + label + "</div></td></tr>";
        }

        // wordmark (default): quiet text header in the accent colour, inside the card.
        if (title == null) {
            return ""; // nothing to show — start straight into the body
        }
        return "<tr><td style=\"padding:26px 32px 0;\">"
                + "<div style=\"font-size:15px;font-weight:700;letter-spacing:-0.3px;color:"
                + pal.accent() + ";\">" + escape(title) + "</div></td></tr>";
    }

    /** Header text precedence: token title → org brand name → none. */
    private static String headerText(TemplateHeader header, String brandName) {
        if (header != null && header.title() != null && !header.title().isBlank()) {
            return header.title().trim();
        }
        if (brandName != null && !brandName.isBlank()) {
            return brandName.trim();
        }
        return null;
    }

    /** Mandatory unsubscribe footer — wording + link preserved, coloured by the muted token. */
    private String footerBlock(TemplatePalette pal, String unsubscribeUrl) {
        return "<tr><td style=\"padding:20px 32px 28px;border-top:1px solid " + pal.muted()
                + "33;font-size:12px;line-height:1.5;color:" + pal.muted() + ";\">"
                + "You received this because you are on this organizer's list. "
                + "<a href=\"" + escape(unsubscribeUrl) + "\" style=\"color:" + pal.muted()
                + ";text-decoration:underline;\">Unsubscribe</a>."
                + "</td></tr>";
    }

    /**
     * Inline {@code color}/{@code font-weight} onto every body {@code <a}. commonmark ran with
     * {@code escapeHtml(true)}, so the only anchors present were generated from the organizer's
     * markdown links — safe to decorate.
     */
    private static String styleBodyLinks(String bodyHtml, String accent) {
        if (bodyHtml == null || bodyHtml.isEmpty()) return bodyHtml;
        return bodyHtml.replace("<a ",
                "<a style=\"color:" + accent + ";font-weight:600;text-decoration:underline;\" ");
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
