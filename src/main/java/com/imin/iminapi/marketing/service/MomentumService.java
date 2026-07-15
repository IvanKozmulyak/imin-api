package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.dto.CampaignDto;      // Phase 2
import com.imin.iminapi.marketing.dto.MomentumEngineStateDto;
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.Campaign;        // Phase 2
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.CampaignRepository;   // Phase 2
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Org-scoped read/approve/dismiss orchestration for Momentum suggestions (spec §6.4).
 * approve creates a {@code campaigns} row with {@code origin='momentum'} and returns it
 * (the FE then opens it in the composer). This service does NOT write suggestions or
 * notifications — the evaluator owns creation (avoids a bean cycle).
 */
@Service
public class MomentumService {

    private static final Duration ACTED_WINDOW = Duration.ofDays(30);
    private static final int LOG_LIMIT = 10;

    private final MomentumSuggestionRepository suggestions;
    private final CampaignRepository campaigns;
    private final EventRepository events;
    private final MomentumThresholds thresholds;
    private final ObjectMapper json = new ObjectMapper();

    public MomentumService(MomentumSuggestionRepository suggestions, CampaignRepository campaigns,
                           EventRepository events, MomentumThresholds thresholds) {
        this.suggestions = suggestions;
        this.campaigns = campaigns;
        this.events = events;
        this.thresholds = thresholds;
    }

    @Transactional(readOnly = true)
    public List<MomentumSuggestionDto> list(AuthPrincipal principal, String status) {
        String s = status == null || status.isBlank() ? "suggested" : status;
        return suggestions.findByOrgIdAndStatusOrderBySuggestedAtDesc(principal.orgId(), s)
                .stream().map(MomentumSuggestionDto::from).toList();
    }

    /**
     * Real Momentum-engine state + activity log for the org's Momentum tab (spec §6.4).
     * Every number is REAL (DB / thresholds) or a literal 0 when the source genuinely
     * does not exist — nothing is a placeholder (HARD RULE). Org comes ONLY from the
     * principal, so no cross-org data can leak.
     */
    @Transactional(readOnly = true)
    public MomentumEngineStateDto state(AuthPrincipal principal) {
        UUID orgId = principal.orgId();
        Instant now = Instant.now();

        // watching: the org's events currently eligible for Momentum evaluation. The
        // candidate query spans all orgs (MomentumEvaluator.runOnce), so filter to this org.
        int watching = 0;
        for (Event e : events.findMomentumCandidates(now)) {
            if (orgId.equals(e.getOrgId())) watching++;
        }

        // waiting: the org's live 'suggested' suggestions.
        int waiting = suggestions.findByOrgIdAndStatusOrderBySuggestedAtDesc(orgId, "suggested").size();

        // approved/dismissed acted-on in the trailing 30 days.
        Instant since = now.minus(ACTED_WINDOW);
        int approved30d = (int) suggestions.countByOrgIdAndStatusAndActedAtAfter(orgId, "approved", since);
        int dismissed30d = (int) suggestions.countByOrgIdAndStatusAndActedAtAfter(orgId, "dismissed", since);

        // attributedMinor: 30-day attributed REVENUE in minor units for momentum campaigns.
        // No per-campaign revenue-minor source exists today (orders carry no utm key — see
        // MarketingHubService.attributedRevMinor / CampaignAttributionService), so it is a
        // literal 0, never faked.
        long attributedMinor = 0L;

        // log: 10 most recent non-'suggested' suggestions, newest first.
        List<MomentumEngineStateDto.LogEntry> log = new ArrayList<>();
        for (MomentumSuggestion s : suggestions.findRecentActedByOrg(orgId, PageRequest.of(0, LOG_LIMIT))) {
            log.add(toLogEntry(s, now));
        }

        return new MomentumEngineStateDto(
                watching, waiting, approved30d, dismissed30d, attributedMinor,
                thresholds.getMinAudienceFloor(), thresholds.getCooldownDays(), log);
    }

    private MomentumEngineStateDto.LogEntry toLogEntry(MomentumSuggestion s, Instant now) {
        String status = s.getStatus();
        String icon;
        String tone;
        String verb;
        switch (status) {
            case "approved"  -> { icon = "check"; tone = "green"; verb = "Approved"; }
            case "dismissed" -> { icon = "x";     tone = "muted"; verb = "Dismissed"; }
            case "expired"   -> { icon = "clock"; tone = "amber"; verb = "Expired"; }
            default          -> { icon = "clock"; tone = "muted"; verb = capitalize(status); }
        }
        String eventName = resolveEventName(s.getEventId());
        String trigger = s.getTriggerType() == null ? "" : s.getTriggerType().replace('_', ' ');
        String text = (verb + " — " + eventName + " " + trigger).trim();
        Instant age = s.getActedAt() != null ? s.getActedAt() : s.getSuggestedAt();
        return new MomentumEngineStateDto.LogEntry(icon, tone, text, humanizeAge(age, now));
    }

    /** Event name via the events repo; falls back to the event id prefix if the event is gone. */
    private String resolveEventName(UUID eventId) {
        if (eventId == null) return "event";
        String name = events.findById(eventId).map(Event::getName).orElse(null);
        if (name != null && !name.isBlank()) return name;
        return eventId.toString().substring(0, 8);
    }

    /** Static, library-free relative-age humanizer, e.g. "2 days ago". */
    private String humanizeAge(Instant when, Instant now) {
        if (when == null) return "";
        long secs = Math.max(0, Duration.between(when, now).getSeconds());
        long minutes = secs / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days >= 7) {
            long weeks = days / 7;
            return weeks + (weeks == 1 ? " week ago" : " weeks ago");
        }
        if (days >= 1) return days + (days == 1 ? " day ago" : " days ago");
        if (hours >= 1) return hours + (hours == 1 ? " hour ago" : " hours ago");
        if (minutes >= 1) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        return "just now";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Transactional
    public CampaignDto approve(AuthPrincipal principal, UUID suggestionId) {
        MomentumSuggestion s = loadOwned(principal, suggestionId);
        if (!"suggested".equals(s.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Suggestion is not live");
        }
        JsonNode draft = parse(s.getDraftPayload());

        Instant now = Instant.now();
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(principal.orgId());
        c.setChannel("email");
        c.setName(text(draft, "subject", "Momentum campaign"));
        c.setStatus("draft");
        c.setEventId(s.getEventId());
        c.setSegmentId(uuidOrNull(text(draft, "segmentId", null)));
        c.setOrigin("momentum");
        c.setMomentumSuggestionId(s.getId());
        c.setSubject(text(draft, "subject", null));
        c.setPreheader(text(draft, "preheader", null));
        c.setBodyMd(text(draft, "bodyMd", null));
        c.setCreatedBy(principal.userId());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        campaigns.save(c);

        s.setStatus("approved");
        s.setCampaignId(c.getId());
        s.setActedAt(now);
        suggestions.save(s);

        return CampaignDto.from(c);
    }

    @Transactional
    public void dismiss(AuthPrincipal principal, UUID suggestionId) {
        MomentumSuggestion s = loadOwned(principal, suggestionId);
        s.setStatus("dismissed");
        s.setActedAt(Instant.now());
        suggestions.save(s);
    }

    private MomentumSuggestion loadOwned(AuthPrincipal principal, UUID id) {
        MomentumSuggestion s = suggestions.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.NOT_FOUND, "Suggestion not found"));
        if (!s.getOrgId().equals(principal.orgId())) { // no cross-org leak
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Suggestion not found");
        }
        return s;
    }

    private JsonNode parse(String s) {
        try { return json.readTree(s); }
        catch (Exception e) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL, "Bad draft payload"); }
    }
    private String text(JsonNode n, String field, String dflt) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? dflt : v.asText();
    }
    private UUID uuidOrNull(String s) { return s == null ? null : UUID.fromString(s); }
}
