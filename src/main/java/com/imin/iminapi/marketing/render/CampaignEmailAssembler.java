package com.imin.iminapi.marketing.render;

import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.service.CampaignTemplateService;
import com.imin.iminapi.marketing.template.ResolvedTemplate;
import com.imin.iminapi.marketing.template.TemplateHeader;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.service.ai.provenance.AiEmailDisclosure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place a campaign email's renderer inputs are put together: the batch send, the test send
 * and the dashboard preview all go through it, so what the organizer previews is what fans get.
 * {@link #contextFor} resolves what is constant for the campaign; {@link #render} fills one recipient's merge
 * tags and renders.
 */
@Component
public class CampaignEmailAssembler {

    private static final Logger log = LoggerFactory.getLogger(CampaignEmailAssembler.class);

    /** The template shows the event poster, but there is none, so the renderer draws a banner. */
    public static final String POSTER_MISSING = "POSTER_MISSING";
    /** The body places a tickets button, but no event is linked, so the renderer drops it. */
    public static final String TICKETS_BUTTON_DROPPED = "TICKETS_BUTTON_DROPPED";
    /** The campaign links an event that cannot be loaded; replaces the two notices above. */
    public static final String EVENT_UNAVAILABLE = "EVENT_UNAVAILABLE";

    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\s[^>]*?href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    /**
     * Everything constant for a campaign's email: template, header name, footer identity, From, the linked
     * event's poster and buyer URL, the AI marker and the language.
     *
     * @param eventUnavailable the campaign links an event that could not be loaded for this org
     */
    public record Context(String campaignId, ResolvedTemplate template, String brandName, OrganizerIdentity sender,
                          String fromName, String fromHeader, boolean eventUnavailable, String posterUrl,
                          String ticketsUrl, String eventUrl, AiEmailDisclosure ai, String locale) {}

    /** One recipient's email: subject and preheader with merge tags filled, and the rendered bodies. */
    public record Email(String subject, String preheader, String html, String text) {}

    /** A link in the rendered HTML; the unsubscribe link carries no href because every fan gets their own. */
    public record Link(String kind, String text, String href) {}

    private final CampaignEmailRenderer renderer;
    private final CampaignTemplateService templateService;
    private final EventRepository events;
    private final MarketingEmailProperties props;

    public CampaignEmailAssembler(CampaignEmailRenderer renderer, CampaignTemplateService templateService,
                                  EventRepository events, MarketingEmailProperties props) {
        this.renderer = renderer;
        this.templateService = templateService;
        this.events = events;
        this.props = props;
    }

    /** The campaign as saved; {@code org} may be null when its lookup failed (no identity line, configured From). */
    public Context contextFor(Campaign c, Organization org) {
        return contextFor(c, org, c.getTemplateKey(), c.getLocale());
    }

    /** As {@link #contextFor(Campaign, Organization)}, with the template and language the caller chose. */
    public Context contextFor(Campaign c, Organization org, String templateKey, String locale) {
        // resolve() coalesces null/blank/unknown keys to the classic builtin, so a stale key still renders in the shell.
        ResolvedTemplate template = templateService.resolve(c.getOrgId(), templateKey);
        String brandName = org == null ? null : org.displayName();
        Event event = linkedEvent(c);
        String posterUrl = event == null ? null : event.getPosterUrl();
        // The linked event's public buyer page; with no event the renderer drops the tickets button.
        String ticketsUrl = event == null ? null : props.getBuyerSiteBaseUrl() + "/e/" + event.getId();
        // {{eventUrl}} falls back to the buyer-site root rather than a broken or empty link.
        String eventUrl = ticketsUrl != null ? ticketsUrl : props.getBuyerSiteBaseUrl();
        return new Context(c.getId().toString(), template, brandName, OrganizerIdentity.of(org),
                props.displayName(brandName), props.fromHeader(brandName), c.getEventId() != null && event == null,
                posterUrl, ticketsUrl, eventUrl, c.aiDisclosure(), locale);
    }

    /**
     * One recipient's email. A null or unusable {@code firstName} drops the token with its punctuation, as for a fan
     * with no name on file.
     *
     * @param unsubscribeUrl the footer link (the buyer-site unsubscribe page)
     * @param addressSource  Art. 14 line for an imported member's first email, or null
     */
    public Email render(Context ctx, String subject, String preheader, String bodyMd, String firstName,
                        String unsubscribeUrl, CampaignEmailRenderer.AddressSource addressSource) {
        String mergedSubject = MergeTags.apply(subject, firstName, ctx.eventUrl());
        String mergedBody = MergeTags.apply(bodyMd, firstName, ctx.eventUrl());
        String mergedPreheader = MergeTags.apply(preheader, firstName, ctx.eventUrl());
        CampaignEmailRenderer.Rendered rendered = renderer.render(
                mergedSubject, mergedPreheader, mergedBody,
                ctx.campaignId(), "email", unsubscribeUrl,
                ctx.template(), ctx.brandName(), ctx.posterUrl(), ctx.ticketsUrl(), ctx.ai(), ctx.sender(),
                addressSource, ctx.locale());
        return new Email(mergedSubject, mergedPreheader, rendered.html(), rendered.text());
    }

    /** What the renderer silently leaves out of this email, worked out from the same inputs it renders with. */
    public static List<String> notices(Context ctx, String bodyMd) {
        if (ctx.eventUnavailable()) return List.of(EVENT_UNAVAILABLE);
        List<String> out = new ArrayList<>(2);
        TemplateHeader header = ctx.template() == null || ctx.template().tokens() == null
                ? null : ctx.template().tokens().header();
        boolean posterHero = header != null && TemplateHeader.POSTER_HERO.equals(header.style());
        if (posterHero && (ctx.posterUrl() == null || ctx.posterUrl().isBlank())) out.add(POSTER_MISSING);
        if (CampaignEmailRenderer.hasTicketsButton(bodyMd) && ctx.ticketsUrl() == null) out.add(TICKETS_BUTTON_DROPPED);
        return out;
    }

    /** Every link in the rendered HTML in order: text without tags, href unescaped; the unsubscribe href is withheld. */
    public static List<Link> links(String html, String unsubscribeUrl) {
        if (html == null) return List.of();
        List<Link> out = new ArrayList<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            String href = HtmlUtils.htmlUnescape(m.group(1));
            String text = HtmlUtils.htmlUnescape(TAG.matcher(m.group(2)).replaceAll("")).strip();
            boolean unsubscribe = href.equals(unsubscribeUrl);
            out.add(new Link(unsubscribe ? "unsubscribe" : "link", text, unsubscribe ? null : href));
        }
        return out;
    }

    /**
     * The campaign's linked event, org-scoped and not deleted, or null. Failure-isolated: a lookup hiccup must not
     * fail a live send; the email just goes without the poster and tickets button.
     */
    private Event linkedEvent(Campaign c) {
        if (c.getEventId() == null) return null;
        try {
            return events.findActive(c.getEventId())
                    .filter(e -> c.getOrgId().equals(e.getOrgId()))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[email-assembler] event lookup failed for {}: {}", c.getEventId(), e.getMessage());
            return null;
        }
    }
}
