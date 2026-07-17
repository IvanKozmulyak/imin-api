package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.dto.SubjectVariantsLlm;
import com.imin.iminapi.marketing.dto.SubjectVariantsResponse;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Generates THREE distinct email subject-line alternates for a campaign's email step
 * (spec §6 composer "✨ AI variants"), grounded in the campaign's real event data —
 * mirroring how {@code MomentumCopyGenerator} drafts marketing copy from an event via
 * a single one-shot {@link ChatClient} call.
 *
 * <p><b>Whole-response, not streamed.</b> Every existing AI path in this app
 * ({@code MomentumCopyGenerator}, {@code ConceptSetService}) calls
 * {@code chat.prompt().user(...).call().entity(...)} and returns a complete JSON body —
 * nothing uses SSE/Flux. This endpoint matches: it returns all three variants at once and
 * the composer reveals them one-by-one client-side.
 *
 * <p><b>One paid call per request.</b> The endpoint is authenticated and rate-limited on the
 * shared {@code ai-concept} bucket; internally there is exactly one LLM call — no retry loop —
 * so it can never fan out into repeated model hits.
 *
 * <p><b>Always three, never a 5xx from bad output.</b> A model that returns fewer than three
 * usable lines (or unparseable output) is not an error for a convenience helper: the result is
 * cleaned, de-duplicated, and padded with deterministic event-grounded fallbacks so the caller
 * always receives exactly three non-empty, distinct subjects.
 */
@Service
public class SubjectVariantsService {

    private static final Logger log = LoggerFactory.getLogger(SubjectVariantsService.class);

    private static final int WANT = 3;
    /** Abuse/sanity cap on any single subject the model returns (soft 60-char ask is in the prompt). */
    private static final int MAX_SUBJECT = 120;

    private final ChatClient chat;
    private final CampaignRepository campaigns;
    private final EventRepository events;
    private final TicketTierRepository tiers;

    public SubjectVariantsService(ChatClient chat, CampaignRepository campaigns,
                                  EventRepository events, TicketTierRepository tiers) {
        this.chat = chat;
        this.campaigns = campaigns;
        this.events = events;
        this.tiers = tiers;
    }

    /**
     * @param currentSubject optional: the subject currently typed in the composer, so the model
     *                       is told to write DIFFERENT alternatives instead of repeating it.
     */
    @Transactional(readOnly = true)
    public SubjectVariantsResponse generate(AuthPrincipal p, UUID campaignId, String currentSubject) {
        // Org-scoped load: another org's (or a missing) campaign is an indistinguishable 404 no-leak,
        // exactly like CampaignService.
        Campaign campaign = campaigns.findByIdAndOrgId(campaignId, p.orgId())
                .orElseThrow(() -> ApiException.notFound("Campaign"));

        Event event = loadEvent(p.orgId(), campaign.getEventId());
        String baseName = baseName(event, campaign);
        String facts = facts(event, campaign, currentSubject);

        List<String> modelVariants = callModel(facts);
        List<String> result = cleanAndPad(modelVariants, baseName);
        return new SubjectVariantsResponse(result);
    }

    /** Single, non-looping LLM call. Any failure degrades to the deterministic fallback (never a 5xx). */
    private List<String> callModel(String facts) {
        try {
            SubjectVariantsLlm out = chat.prompt().user(prompt(facts)).call().entity(SubjectVariantsLlm.class);
            return out == null ? List.of() : out.variants();
        } catch (Exception e) {
            log.warn("Subject-variants LLM call failed; using deterministic fallback: {}", e.getMessage());
            return List.of();
        }
    }

    private String prompt(String facts) {
        return """
                You are an expert email marketer for an events ticketing app.
                Write exactly THREE distinct email SUBJECT LINE alternatives for the campaign below.
                Each must take a DIFFERENT angle — one urgency/scarcity, one curiosity, one value/benefit —
                NOT three rewordings of the same idea. Ground them in the real event details provided.

                Rules for every subject:
                - max 60 characters, plain text, no emoji, no surrounding quotes
                - punchy and specific to THIS event, never generic filler

                Return JSON ONLY, matching this schema exactly:

                { "variants": ["<subject 1>", "<subject 2>", "<subject 3>"] }

                Campaign details:
                %s
                """.formatted(facts);
    }

    /**
     * Grounding block from the campaign's event (name/date/venue/genre/urgency), degrading to the
     * campaign name when there is no linked event. Failure-isolated: any lookup hiccup still yields
     * a usable (possibly thinner) block rather than failing the request.
     */
    private String facts(Event event, Campaign campaign, String currentSubject) {
        StringBuilder sb = new StringBuilder();
        if (event != null && notBlank(event.getName())) {
            sb.append("Event name: ").append(event.getName().trim()).append('\n');
            if (event.getStartsAt() != null) {
                sb.append("Date: ").append(event.getStartsAt()).append('\n');
            }
            String venue = venueLabel(event);
            if (venue != null) sb.append("Venue: ").append(venue).append('\n');
            if (notBlank(event.getGenre())) sb.append("Genre: ").append(event.getGenre().trim()).append('\n');
            if (notBlank(event.getType())) sb.append("Type: ").append(event.getType().trim()).append('\n');
            String urgency = urgency(event.getId());
            if (urgency != null) sb.append("Ticket sales: ").append(urgency).append('\n');
        } else {
            sb.append("Campaign name: ").append(campaign.getName()).append('\n');
        }
        String avoid = notBlank(currentSubject) ? currentSubject.trim()
                : (notBlank(campaign.getSubject()) ? campaign.getSubject().trim() : null);
        if (avoid != null) {
            sb.append("Current subject (write DIFFERENT alternatives — do not repeat this): ")
                    .append(avoid).append('\n');
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

    /**
     * Sell-through summary for urgency grounding — "N of M tickets left (P% sold)".
     * Failure-isolated and only produced when a real capacity exists; null otherwise.
     */
    private String urgency(UUID eventId) {
        try {
            int capacity = tiers.sumQuantityByEventId(eventId);
            if (capacity <= 0) return null;
            int sold = Math.max(0, tiers.sumSoldByEventId(eventId));
            int remaining = Math.max(0, capacity - sold);
            int pct = (int) Math.round(Math.min(sold, capacity) * 100.0 / capacity);
            return remaining + " of " + capacity + " tickets left (" + pct + "% sold)";
        } catch (Exception e) {
            log.debug("urgency lookup failed for event {}: {}", eventId, e.getMessage());
            return null;
        }
    }

    /**
     * Load the campaign's event, org-scoped and failure-isolated. Returns null when there is no
     * linked event, when it is soft-deleted/missing, or when it resolves outside the caller's org
     * (defensive — a campaign and its event normally share an org).
     */
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

    /** Preferred name for deterministic fallbacks: event name, else campaign name, else null. */
    private static String baseName(Event event, Campaign campaign) {
        if (event != null && notBlank(event.getName())) return event.getName().trim();
        if (campaign != null && notBlank(campaign.getName())) return campaign.getName().trim();
        return null;
    }

    /**
     * Clean the model output (trim, unquote, drop blanks, clamp length, de-dup case-insensitively,
     * keep at most three), then pad to exactly three with deterministic, event-grounded fallbacks.
     * The result is always exactly three non-empty, distinct subjects.
     */
    private List<String> cleanAndPad(List<String> raw, String baseName) {
        // LinkedHashSet on the lowercased key preserves order while de-duplicating.
        Set<String> seenKeys = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String s : raw) {
                String v = normalize(s);
                if (v == null) continue;
                if (seenKeys.add(v.toLowerCase(Locale.ROOT))) out.add(v);
                if (out.size() == WANT) return out;
            }
        }
        for (String fb : fallbacks(baseName)) {
            if (out.size() == WANT) break;
            String v = normalize(fb);
            if (v != null && seenKeys.add(v.toLowerCase(Locale.ROOT))) out.add(v);
        }
        // Ultimate guard: fallbacks() always supplies >=5 distinct non-blank options, so this is
        // unreachable in practice — but it makes the "exactly three, never empty" contract total.
        int n = 1;
        while (out.size() < WANT) {
            String v = "Your invite is waiting " + (n++);
            if (seenKeys.add(v.toLowerCase(Locale.ROOT))) out.add(v);
        }
        return out;
    }

    /** Trim, strip a single pair of wrapping quotes, drop blanks, clamp to {@link #MAX_SUBJECT}. */
    private static String normalize(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        if (v.isBlank()) return null;
        if (v.length() > MAX_SUBJECT) v = v.substring(0, MAX_SUBJECT).trim();
        return v.isBlank() ? null : v;
    }

    /** Deterministic distinct-angle fallbacks, grounded in the event/campaign name when present. */
    private static List<String> fallbacks(String baseName) {
        if (notBlank(baseName)) {
            String n = baseName.trim();
            return List.of(
                    "Don't miss " + n,
                    n + ": your spot is waiting",
                    "Last chance for " + n,
                    "Going fast — " + n,
                    "You're invited: " + n);
        }
        return List.of(
                "Don't miss out",
                "Your spot is waiting",
                "Last chance to join",
                "Going fast",
                "You're invited");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
