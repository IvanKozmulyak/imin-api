package com.imin.iminapi.marketing.render;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/**
 * Renders a campaign's markdown body into a branded HTML shell + plain-text variant,
 * with the mandatory RFC 8058 unsubscribe footer and UTM link rewriting (spec §3).
 *
 * <p>Sanitization: commonmark is configured with escapeHtml(true), so any raw inline
 * HTML in body_md (stored XSS in the composer preview / HTML injection in sent mail —
 * orgs are multi-user) is escaped rather than emitted. The renderer REFUSES to produce
 * output without an unsubscribe URL, so the send path can never omit the footer.
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

    public Rendered render(String subject, String preheader, String bodyMd,
                           String campaignId, String channel, String unsubscribeUrl) {
        if (unsubscribeUrl == null || unsubscribeUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Cannot render campaign email without an unsubscribe URL (footer is mandatory)");
        }
        Node doc = parser.parse(bodyMd == null ? "" : bodyMd);
        String bodyHtml = htmlRenderer.render(doc);
        bodyHtml = utmRewriter.rewrite(bodyHtml, channel, campaignId);

        String preheaderBlock = (preheader == null || preheader.isBlank()) ? ""
                : "<span style=\"display:none;max-height:0;overflow:hidden;\">"
                  + escape(preheader) + "</span>";

        String html = "<!DOCTYPE html><html><body style=\"font-family:sans-serif;\">"
                + preheaderBlock
                + "<div>" + bodyHtml + "</div>"
                + "<hr/>"
                + "<p style=\"font-size:12px;color:#888;\">"
                + "You received this because you are on this organizer's list. "
                + "<a href=\"" + escape(unsubscribeUrl) + "\">Unsubscribe</a>."
                + "</p></body></html>";

        String text = (bodyMd == null ? "" : bodyMd)
                + "\n\n---\nUnsubscribe: " + unsubscribeUrl;

        return new Rendered(html, text);
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
