package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.dto.EmailTemplateDto;
import com.imin.iminapi.marketing.dto.GeneratedTemplateLlm;
import com.imin.iminapi.marketing.dto.GenerateTemplateRequest;
import com.imin.iminapi.marketing.model.CampaignEmailTemplate;
import com.imin.iminapi.marketing.repository.CampaignEmailTemplateRepository;
import com.imin.iminapi.marketing.template.BuiltinTemplates;
import com.imin.iminapi.marketing.template.ResolvedTemplate;
import com.imin.iminapi.marketing.template.TemplateTokenValidator;
import com.imin.iminapi.marketing.template.TemplateTokens;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Campaign email templates (spec §5): lists the four builtins + the org's saved templates,
 * resolves a campaign's {@code template_key} to a {@link ResolvedTemplate} for the renderer,
 * AI-generates a new org template from org/event context, and deletes org templates.
 *
 * <p>Org comes ONLY from the auth context; another org's template is an indistinguishable
 * 404 no-leak (mirrors {@code CampaignService}). The AI path makes exactly one paid LLM call
 * and NEVER trusts its output — tokens go through {@link TemplateTokenValidator} (hex-only
 * colours, whitelisted header style) before anything is stored or ever rendered into email.
 */
@Service
public class CampaignTemplateService {

    private static final Logger log = LoggerFactory.getLogger(CampaignTemplateService.class);

    /** Sanity cap on a stored template name (matches the V66 column width). */
    private static final int MAX_NAME = 64;
    private static final int MAX_HINT = 200;

    private final ChatClient chat;
    private final CampaignEmailTemplateRepository templates;
    private final EventRepository events;
    private final OrganizationRepository organizations;

    public CampaignTemplateService(ChatClient chat, CampaignEmailTemplateRepository templates,
                                   EventRepository events, OrganizationRepository organizations) {
        this.chat = chat;
        this.templates = templates;
        this.events = events;
        this.organizations = organizations;
    }

    /** Builtins (in display order) followed by the org's saved templates, newest first. */
    @Transactional(readOnly = true)
    public List<EmailTemplateDto> list(AuthPrincipal p) {
        List<EmailTemplateDto> out = new ArrayList<>();
        for (ResolvedTemplate t : BuiltinTemplates.all()) {
            out.add(EmailTemplateDto.fromBuiltin(t));
        }
        for (CampaignEmailTemplate row : templates.findByOrgIdOrderByCreatedAtDesc(p.orgId())) {
            out.add(EmailTemplateDto.fromRow(row));
        }
        return out;
    }

    /**
     * Resolve a campaign's {@code template_key} for rendering. A builtin key → its builtin;
     * a UUID → the org's saved row; null/blank/unknown/deleted → the default builtin
     * ({@code classic}). Never throws: a bad key must not break a live send, it just falls
     * back to a safe, complete template.
     */
    @Transactional(readOnly = true)
    public ResolvedTemplate resolve(UUID orgId, String templateKey) {
        if (templateKey == null || templateKey.isBlank()) {
            return BuiltinTemplates.defaultTemplate();
        }
        String key = templateKey.trim();
        ResolvedTemplate builtin = BuiltinTemplates.byKey(key);
        if (builtin != null) {
            return builtin;
        }
        UUID id;
        try {
            id = UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return BuiltinTemplates.defaultTemplate();
        }
        return templates.findByIdAndOrgId(id, orgId)
                .map(row -> new ResolvedTemplate(
                        row.getId().toString(), row.getName(), row.getSource(), row.getTokens()))
                .orElseGet(BuiltinTemplates::defaultTemplate);
    }

    /**
     * Generate + persist an org template from org identity (name, stored brand colours) and,
     * when {@code eventId} is given, that event's title/vibe/date. One paid LLM call; the
     * output is validated (bad hex → 422) and clamped before it is saved.
     */
    @Transactional
    public EmailTemplateDto generate(AuthPrincipal p, GenerateTemplateRequest req) {
        Organization org = organizations.findById(p.orgId())
                .orElseThrow(() -> ApiException.forbidden("Caller has no organization"));
        Event event = loadEvent(p.orgId(), req == null ? null : req.eventId());
        String hint = req == null ? null : req.hint();

        GeneratedTemplateLlm out = callModel(facts(org, event, hint));
        // Untrusted model output → known-safe tokens (hex-only, whitelisted header) or 422.
        TemplateTokens tokens = TemplateTokenValidator.sanitize(out == null ? null : out.tokens());
        String name = templateName(out == null ? null : out.name(), org, event);

        Instant now = Instant.now();
        CampaignEmailTemplate row = new CampaignEmailTemplate();
        row.setId(UUID.randomUUID());
        row.setOrgId(p.orgId());
        row.setName(name);
        row.setTokens(tokens);
        row.setSource(ResolvedTemplate.SOURCE_AI);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return EmailTemplateDto.fromRow(templates.save(row));
    }

    /** Delete an org template (404 no-leak when missing / another org's). Builtins are code, not rows. */
    @Transactional
    public void delete(AuthPrincipal p, UUID id) {
        CampaignEmailTemplate row = templates.findByIdAndOrgId(id, p.orgId())
                .orElseThrow(() -> ApiException.notFound("Template"));
        templates.delete(row);
    }

    /** Single, non-looping LLM call. Any failure surfaces as null → validator yields the classic fallback. */
    private GeneratedTemplateLlm callModel(String facts) {
        try {
            return chat.prompt().user(prompt(facts)).call().entity(GeneratedTemplateLlm.class);
        } catch (Exception e) {
            log.warn("Template-generate LLM call failed; using classic fallback tokens: {}", e.getMessage());
            return null;
        }
    }

    private String prompt(String facts) {
        return """
                You are an email art director for an events ticketing platform. Design a
                reusable EMAIL TEMPLATE STYLE (colours, header treatment, font) for one
                organizer. Any campaign text will later flow into this template, so design a
                STYLE, not a specific message. Match the organizer's brand and event vibe below.

                Return JSON ONLY, matching this schema exactly:
                {
                  "name": "<2-3 word style name>",
                  "tokens": {
                    "palette": {
                      "bg": "#rrggbb", "card": "#rrggbb", "text": "#rrggbb",
                      "accent": "#rrggbb", "muted": "#rrggbb",
                      "buttonBg": "#rrggbb", "buttonText": "#rrggbb"
                    },
                    "header": { "style": "wordmark|banner|posterHero", "title": "<optional short header text or empty>" },
                    "fontStack": "<a css font-family list of common web-safe fonts>"
                  }
                }

                Hard rules:
                - Every colour MUST be a hex value like #2d5cff. No names, no rgb(), no gradients.
                - Body text on the card MUST stay clearly readable (strong text/card contrast).
                - Prefer "posterHero" only for image-led events; otherwise "wordmark" or "banner".
                - fontStack must be plain font names separated by commas (no @import, no url()).

                Organizer & event:
                %s
                """.formatted(facts);
    }

    /** Grounding block: org name + stored brand colours, and the event vibe when linked. */
    private String facts(Organization org, Event event, String hint) {
        StringBuilder sb = new StringBuilder();
        String orgName = org.getBrandName() != null && !org.getBrandName().isBlank()
                ? org.getBrandName().trim() : org.getName();
        sb.append("Organizer: ").append(orgName).append('\n');
        List<String> brandColors = org.getBrandAccentColors();
        if (brandColors != null && !brandColors.isEmpty()) {
            sb.append("Brand colours (use or harmonize with these): ")
                    .append(String.join(", ", brandColors)).append('\n');
        }
        if (event != null) {
            if (notBlank(event.getName())) sb.append("Event: ").append(event.getName().trim()).append('\n');
            if (notBlank(event.getGenre())) sb.append("Genre/vibe: ").append(event.getGenre().trim()).append('\n');
            if (notBlank(event.getType())) sb.append("Type: ").append(event.getType().trim()).append('\n');
            if (event.getStartsAt() != null) sb.append("Date: ").append(event.getStartsAt()).append('\n');
            if (notBlank(event.getPosterUrl())) sb.append("Has a poster image: yes\n");
        }
        if (notBlank(hint)) {
            String h = hint.trim();
            sb.append("Style hint: ").append(h.length() > MAX_HINT ? h.substring(0, MAX_HINT) : h).append('\n');
        }
        return sb.toString();
    }

    /** Clean the model's name; fall back to "<org> style" / "Custom template" when unusable. */
    private String templateName(String raw, Organization org, Event event) {
        if (notBlank(raw)) {
            String v = raw.trim();
            // strip a single pair of wrapping quotes the model sometimes adds
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1).trim();
            }
            if (!v.isBlank()) return v.length() > MAX_NAME ? v.substring(0, MAX_NAME) : v;
        }
        String base = org.getBrandName() != null && !org.getBrandName().isBlank()
                ? org.getBrandName().trim() : org.getName();
        String name = notBlank(base) ? base + " style" : "Custom template";
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    /** Load the event org-scoped + failure-isolated; null when absent/missing/other-org. */
    private Event loadEvent(UUID orgId, UUID eventId) {
        if (eventId == null) return null;
        try {
            return events.findActive(eventId)
                    .filter(e -> orgId.equals(e.getOrgId()))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("event lookup failed for {}: {}", eventId, e.getMessage());
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
