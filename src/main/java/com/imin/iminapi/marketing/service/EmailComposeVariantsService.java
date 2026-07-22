package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsLlm;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsResponse;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsResponse.EmailVariant;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates whole-email AI variants (subject + preheader + Markdown body, together) for a
 * campaign's email step — the "✦ Generate email with AI" button. It extends the same one-shot,
 * per-user {@code ai-concept}-rate-limited {@link ChatClient} pattern as
 * {@link SubjectVariantsService}, but returns complete drafts rather than subject lines only.
 *
 * <p><b>One paid call per request.</b> Exactly one structured-output LLM call, no retry loop, so
 * the endpoint can never fan out into repeated model hits (the controller also gates it on the
 * shared {@code ai-concept} bucket).
 *
 * <p><b>Untrusted output.</b> Every returned variant is sanitized and validated server-side:
 * HTML tags are stripped from the body (it must be plain Markdown), subjects over 80 chars or
 * spammy all-caps are dropped, empty required fields are dropped, and the {@code {{tickets_button}}}
 * CTA token is enforced to appear exactly once when the campaign has a linked event (and never
 * when it does not — no fabricated buyer URL). If validation leaves nothing usable, a single
 * deterministic, event-grounded fallback variant is returned so the button never yields nothing.
 *
 * <p><b>The unsubscribe footer is NOT part of the body</b> — the renderer appends it at send
 * time, and the prompt says so, so the model never writes its own.
 */
@Service
public class EmailComposeVariantsService {

    private static final Logger log = LoggerFactory.getLogger(EmailComposeVariantsService.class);

    private static final int DEFAULT_COUNT = 3;
    private static final int MAX_COUNT = 3;
    private static final int MAX_SUBJECT = 80;
    private static final int MAX_PREHEADER = 120;
    private static final int MAX_HINT = 200;
    /** A subject is "spammy all-caps" only once it has enough letters to be intentional shouting. */
    private static final int ALL_CAPS_MIN_LETTERS = 4;

    /** Any {@code {{tickets_button}}} / {@code {{tickets_button:Label}}} token. */
    private static final Pattern TICKETS_TOKEN =
            Pattern.compile("\\{\\{\\s*tickets_button(?::[^}]*)?\\s*\\}\\}");
    /** HTML-ish tags to strip from a Markdown body. */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern MANY_BLANK_LINES = Pattern.compile("\\n{3,}");

    private final ChatClient chat;
    private final CampaignRepository campaigns;
    private final EventRepository events;
    private final OrganizationRepository organizations;
    private final SegmentRepository segments;
    private final SegmentService segmentService;

    public EmailComposeVariantsService(ChatClient chat, CampaignRepository campaigns,
                                       EventRepository events, OrganizationRepository organizations,
                                       SegmentRepository segments, SegmentService segmentService) {
        this.chat = chat;
        this.campaigns = campaigns;
        this.events = events;
        this.organizations = organizations;
        this.segments = segments;
        this.segmentService = segmentService;
    }

    @Transactional(readOnly = true)
    public EmailComposeVariantsResponse generate(AuthPrincipal p, UUID campaignId,
                                                 Integer requestedCount, String hint) {
        // Org-scoped load: another org's (or a missing) campaign is an indistinguishable 404
        // no-leak, exactly like CampaignService / SubjectVariantsService.
        Campaign campaign = campaigns.findByIdAndOrgId(campaignId, p.orgId())
                .orElseThrow(() -> ApiException.notFound("Campaign"));

        int count = clampCount(requestedCount);
        Event event = loadEvent(p.orgId(), campaign.getEventId());
        boolean hasEvent = event != null;

        String facts = facts(campaign, event, p.orgId(), hint);
        EmailComposeVariantsLlm out = callModel(prompt(facts, hasEvent, count));

        List<EmailVariant> valid = new ArrayList<>();
        if (out != null && out.variants() != null) {
            for (EmailComposeVariantsLlm.Variant v : out.variants()) {
                EmailVariant clean = sanitize(v, hasEvent);
                if (clean != null) valid.add(clean);
                if (valid.size() == count) break;
            }
        }
        if (valid.isEmpty()) {
            // Model failed or everything was rejected — never return nothing for a valid campaign.
            valid.add(fallbackVariant(campaign, event, hasEvent));
        }
        return new EmailComposeVariantsResponse(valid);
    }

    private static int clampCount(Integer requested) {
        if (requested == null) return DEFAULT_COUNT;
        if (requested < 1) return 1;
        return Math.min(requested, MAX_COUNT);
    }

    /** Single, non-looping LLM call. Any failure degrades to the deterministic fallback (never a 5xx). */
    private EmailComposeVariantsLlm callModel(String prompt) {
        try {
            return chat.prompt().user(prompt).call().entity(EmailComposeVariantsLlm.class);
        } catch (Exception e) {
            log.warn("Email compose-variants LLM call failed; using deterministic fallback: {}", e.getMessage());
            return null;
        }
    }

    // ---- validation / sanitization ----

    /**
     * Clean and validate one model variant, or return null to drop it. Subject: unquoted, trimmed,
     * dropped if empty, over {@link #MAX_SUBJECT}, or spammy all-caps. Preheader: trimmed, dropped
     * if empty, clamped to {@link #MAX_PREHEADER}. Body: HTML stripped (plain Markdown only),
     * dropped if empty, {@code {{tickets_button}}} normalized to exactly-one-or-none per event.
     */
    private EmailVariant sanitize(EmailComposeVariantsLlm.Variant v, boolean hasEvent) {
        if (v == null) return null;
        String subject = normalizeSubject(v.subject());
        if (subject == null) return null;

        String preheader = trimToNull(v.preheader());
        if (preheader == null) return null;
        if (preheader.length() > MAX_PREHEADER) preheader = preheader.substring(0, MAX_PREHEADER).trim();

        String body = stripHtml(v.bodyMarkdown());
        if (body == null) return null;
        body = enforceTicketsButton(body, hasEvent);
        if (body.isBlank()) return null;

        return new EmailVariant(subject, preheader, body);
    }

    /** Trim, strip one pair of wrapping quotes, reject blank / overlong / spammy all-caps subjects. */
    private static String normalizeSubject(String raw) {
        String v = trimToNull(raw);
        if (v == null) return null;
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        if (v.isBlank()) return null;
        if (v.length() > MAX_SUBJECT) return null;              // overlong → reject
        if (isSpamAllCaps(v)) return null;                       // shouting → reject
        return v;
    }

    /** True when the subject is all-caps with enough letters to read as spam (e.g. "SALE ENDS NOW"). */
    private static boolean isSpamAllCaps(String s) {
        int letters = 0;
        boolean hasLower = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isLowerCase(c)) hasLower = true;
            }
        }
        return letters >= ALL_CAPS_MIN_LETTERS && !hasLower;
    }

    /** Strip HTML tags so the body is plain Markdown; return null when nothing usable remains. */
    private static String stripHtml(String raw) {
        if (raw == null) return null;
        String v = HTML_TAG.matcher(raw).replaceAll("").strip();
        return v.isBlank() ? null : v;
    }

    /**
     * Enforce the tickets-button token: when the campaign has a linked event the body must carry
     * exactly one {@code {{tickets_button}}} (append if missing, keep the FIRST if duplicated);
     * when it has no event the token must not appear at all (strip any the model hallucinated).
     */
    private static String enforceTicketsButton(String body, boolean hasEvent) {
        if (!hasEvent) {
            return collapseBlankLines(TICKETS_TOKEN.matcher(body).replaceAll("")).strip();
        }
        Matcher m = TICKETS_TOKEN.matcher(body);
        StringBuilder out = new StringBuilder();
        boolean kept = false;
        while (m.find()) {
            // Keep the first token verbatim (a custom label is legitimate); drop the rest.
            m.appendReplacement(out, Matcher.quoteReplacement(kept ? "" : m.group()));
            kept = true;
        }
        m.appendTail(out);
        String result = collapseBlankLines(out.toString());
        if (!kept) {
            result = result.stripTrailing() + "\n\n{{tickets_button}}";
        }
        return result;
    }

    private static String collapseBlankLines(String s) {
        return MANY_BLANK_LINES.matcher(s).replaceAll("\n\n");
    }

    // ---- deterministic fallback (never return zero variants for a valid campaign) ----

    private static EmailVariant fallbackVariant(Campaign campaign, Event event, boolean hasEvent) {
        String name = baseName(event, campaign);
        String subject = clampSubject(name != null ? "You're invited: " + name : "You're invited");
        String preheader = name != null ? "Save your spot for " + name : "Save your spot — details inside";
        if (preheader.length() > MAX_PREHEADER) preheader = preheader.substring(0, MAX_PREHEADER).trim();
        StringBuilder body = new StringBuilder();
        body.append("Hi there,\n\n");
        if (name != null) {
            body.append("We'd love to see you at **").append(name).append("**. ");
        }
        body.append("Here are the details and everything you need to know before the day.\n\n");
        body.append("Hope to see you there!");
        if (hasEvent) {
            body.append("\n\n{{tickets_button}}");
        }
        return new EmailVariant(subject, preheader, body.toString());
    }

    private static String clampSubject(String s) {
        return s.length() > MAX_SUBJECT ? s.substring(0, MAX_SUBJECT).trim() : s;
    }

    /** Preferred grounding name: event name, else campaign name, else null. */
    private static String baseName(Event event, Campaign campaign) {
        if (event != null && notBlank(event.getName())) return event.getName().trim();
        if (campaign != null && notBlank(campaign.getName())) return campaign.getName().trim();
        return null;
    }

    // ---- prompt + grounding facts ----

    private String prompt(String facts, boolean hasEvent, int count) {
        String ticketsRule = hasEvent
                ? """
                  - This campaign HAS a linked event, so include the LITERAL token {{tickets_button}}
                    on its own line EXACTLY ONCE, placed after the main pitch. It is replaced with a
                    real "Get tickets" button at send time. Do NOT write your own link, URL, or button
                    text — just the token.
                  """
                : """
                  - This campaign has NO linked event. Do NOT include any {{tickets_button}} token or
                    any ticket link — there is no event to link to.
                  """;
        return """
                You are an expert email marketer for an events ticketing app. Write %d DISTINCT
                complete marketing emails for the campaign below — each with a subject line, a
                preheader, and a Markdown body. Ground every email in the real details provided; if a
                draft is given, RIFF on and improve it rather than discarding it.

                Each of the %d emails must take a DIFFERENT angle (e.g. urgency/scarcity, curiosity,
                value/benefit) — not three rewordings of one idea.

                Rules for every email:
                - subject: max 80 characters, plain text, no emoji, no surrounding quotes, and NEVER
                  all-caps shouting (that trips spam filters).
                - preheader: max 120 characters, plain text — the one-line teaser shown after the
                  subject in the inbox.
                - bodyMarkdown: PLAIN MARKDOWN ONLY (paragraphs, **bold**, and [links](https://...) are
                  fine) — absolutely NO HTML tags. A few short, warm, specific paragraphs.
                - Do NOT write an unsubscribe line or footer — the system appends the legal footer
                  automatically.
                %s
                Return JSON ONLY, matching this schema exactly:

                {
                  "variants": [
                    { "subject": "<subject>", "preheader": "<preheader>", "bodyMarkdown": "<markdown body>" }
                  ]
                }

                Campaign details:
                %s
                """.formatted(count, count, ticketsRule, facts);
    }

    /**
     * Grounding block from REAL data: the linked event (title/date/venue/genre), the org name, the
     * selected segment (name + size), the template's tone, and the existing draft to riff on.
     * Failure-isolated per section: any lookup hiccup still yields a usable (thinner) block.
     */
    private String facts(Campaign campaign, Event event, UUID orgId, String hint) {
        StringBuilder sb = new StringBuilder();

        String orgName = orgName(orgId);
        if (orgName != null) sb.append("Organizer: ").append(orgName).append('\n');

        if (event != null && notBlank(event.getName())) {
            sb.append("Event: ").append(event.getName().trim()).append('\n');
            if (event.getStartsAt() != null) sb.append("Date: ").append(event.getStartsAt()).append('\n');
            String venue = venueLabel(event);
            if (venue != null) sb.append("Venue: ").append(venue).append('\n');
            if (notBlank(event.getGenre())) sb.append("Genre: ").append(event.getGenre().trim()).append('\n');
            if (notBlank(event.getType())) sb.append("Type: ").append(event.getType().trim()).append('\n');
        } else {
            sb.append("Campaign: ").append(campaign.getName()).append('\n');
            sb.append("(No specific event is linked — keep it general, no ticket link.)\n");
        }

        segmentLine(orgId, campaign.getSegmentId()).ifPresent(line -> sb.append(line).append('\n'));

        String tone = templateTone(campaign.getTemplateKey());
        if (tone != null) sb.append("Design tone: ").append(tone).append('\n');

        // Existing draft to riff on — only when non-empty.
        if (notBlank(campaign.getSubject())) {
            sb.append("Current subject draft (improve on it): ").append(campaign.getSubject().trim()).append('\n');
        }
        if (notBlank(campaign.getPreheader())) {
            sb.append("Current preheader draft: ").append(campaign.getPreheader().trim()).append('\n');
        }
        if (notBlank(campaign.getBodyMd())) {
            sb.append("Current body draft (improve on it, keep what works):\n")
                    .append(campaign.getBodyMd().trim()).append('\n');
        }

        if (notBlank(hint)) {
            String h = hint.trim();
            if (h.length() > MAX_HINT) h = h.substring(0, MAX_HINT);
            sb.append("Extra instruction from the organizer: ").append(h).append('\n');
        }
        return sb.toString();
    }

    /** "Venue, City" / "Venue" / "City" — null when neither is set. */
    private static String venueLabel(Event e) {
        boolean hasName = notBlank(e.getVenueName());
        boolean hasCity = notBlank(e.getVenueCity());
        if (hasName && hasCity) return e.getVenueName().trim() + ", " + e.getVenueCity().trim();
        if (hasName) return e.getVenueName().trim();
        if (hasCity) return e.getVenueCity().trim();
        return null;
    }

    /** Org display name (brand name preferred). Failure-isolated. */
    private String orgName(UUID orgId) {
        try {
            Organization org = organizations.findById(orgId).orElse(null);
            if (org == null) return null;
            return notBlank(org.getBrandName()) ? org.getBrandName().trim() : org.getName();
        } catch (Exception e) {
            log.debug("compose-variants org lookup failed for {}: {}", orgId, e.getMessage());
            return null;
        }
    }

    /**
     * "Audience: <segment name> (~N people)" when a segment is set. The size is a real count of the
     * segment's members; failure-isolated and omitted (name only) if it can't be resolved.
     */
    private java.util.Optional<String> segmentLine(UUID orgId, UUID segmentId) {
        if (segmentId == null) return java.util.Optional.empty();
        try {
            Segment seg = segments.findByIdAndOrgId(segmentId, orgId).orElse(null);
            if (seg == null) return java.util.Optional.empty();
            StringBuilder line = new StringBuilder("Audience: ").append(seg.getName());
            try {
                int size = segmentService.resolveMembershipIds(orgId, segmentId).size();
                line.append(" (~").append(size).append(" people)");
            } catch (Exception ignored) {
                // size is a nice-to-have; the name alone is still useful grounding.
            }
            return java.util.Optional.of(line.toString());
        } catch (Exception e) {
            log.debug("compose-variants segment lookup failed for {}: {}", segmentId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** A short tone hint per builtin template key (midnight=dark/edgy, mono=minimal, …). */
    private static String templateTone(String templateKey) {
        if (templateKey == null) return null;
        return switch (templateKey.trim()) {
            case "midnight" -> "dark, edgy, nightlife";
            case "mono" -> "minimal, understated, typographic";
            case "poster" -> "bold, image-led, hype";
            case "classic" -> "clean, friendly, brand-forward";
            // Org/AI templates carry a UUID key — no fixed tone.
            default -> null;
        };
    }

    /**
     * Load the campaign's event, org-scoped and failure-isolated. Returns null when there is no
     * linked event, when it is soft-deleted/missing, or when it resolves outside the caller's org.
     */
    private Event loadEvent(UUID orgId, UUID eventId) {
        if (eventId == null) return null;
        try {
            return events.findActive(eventId)
                    .filter(e -> orgId.equals(e.getOrgId()))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("compose-variants event lookup failed for {}: {}", eventId, e.getMessage());
            return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
