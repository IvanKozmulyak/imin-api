package com.imin.iminapi.marketing.render;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec §3 attribution: rewrites every imin.wtf link in rendered campaign HTML to
 * append utm_source=imin&utm_medium={channel}&utm_campaign={campaignId}. The V43
 * UTM columns on event_funnel_events capture these from the imin-public /track beacon,
 * so the detail page's "purchases attributed" tile can group by utm_campaign.
 */
@Component
public class UtmLinkRewriter {

    // Matches href="…imin.wtf…" (http or https, any imin.wtf host/subdomain path).
    private static final Pattern HREF =
            Pattern.compile("href=\"(https?://(?:[a-z0-9.-]*\\.)?imin\\.wtf[^\"]*)\"");

    public String rewrite(String html, String channel, String campaignId) {
        if (html == null || html.isEmpty()) return html;
        Matcher m = HREF.matcher(html);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String url = m.group(1);
            String replacement;
            if (url.contains("utm_source=")) {
                replacement = "href=\"" + url + "\""; // already tagged — leave it
            } else {
                String sep = url.contains("?") ? "&" : "?";
                String tagged = url + sep + "utm_source=imin"
                        + "&utm_medium=" + channel
                        + "&utm_campaign=" + campaignId;
                replacement = "href=\"" + tagged + "\"";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
