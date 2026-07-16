package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.marketing.dto.CampaignDto;      // Phase 2
import com.imin.iminapi.marketing.dto.MomentumEngineStateDto;
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.Campaign;        // Phase 2
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.model.MomentumTriggerType;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final SegmentRepository segmentRepo;
    private final MembershipRepository memberships;
    private final MomentumThresholds thresholds;
    private final ObjectMapper json = new ObjectMapper();

    public MomentumService(MomentumSuggestionRepository suggestions, CampaignRepository campaigns,
                           EventRepository events, SegmentRepository segmentRepo,
                           MembershipRepository memberships, MomentumThresholds thresholds) {
        this.suggestions = suggestions;
        this.campaigns = campaigns;
        this.events = events;
        this.segmentRepo = segmentRepo;
        this.memberships = memberships;
        this.thresholds = thresholds;
    }

    /**
     * The org's suggestions, enriched into the card read-model (spec §6.4).
     *
     * <p>Resolution strategy is per-field and deliberate — see the field-by-field rationale on
     * {@link MomentumSuggestionDto}. In short: the METRICS stay frozen in {@code metrics_snapshot}
     * (they are the evidence for why the engine fired and must not drift), the PROSE is derived
     * from that frozen snapshot on read (pure function of a frozen input ⇒ same answer, but it
     * also backfills pre-existing rows and keeps wording fixable), and IDENTITY/CAPABILITY
     * ({@code eventName}, {@code segmentLabel}, {@code smsLocked}) is resolved LIVE so a rename
     * or a fresh SMS opt-in is reflected instead of being stale prose.
     *
     * <p>All three live lookups are batched — one events query, one segments query, one phone
     * count for the whole page — so enriching N suggestions stays 3 queries, not 3N.
     */
    @Transactional(readOnly = true)
    public List<MomentumSuggestionDto> list(AuthPrincipal principal, String status) {
        String s = status == null || status.isBlank() ? "suggested" : status;
        UUID orgId = principal.orgId();
        List<MomentumSuggestion> rows =
                suggestions.findByOrgIdAndStatusOrderBySuggestedAtDesc(orgId, s);
        if (rows.isEmpty()) return List.of();

        // Live #1: event names, one batch fetch keyed by id.
        Set<UUID> eventIds = rows.stream().map(MomentumSuggestion::getEventId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> eventNames = new HashMap<>();
        for (Event e : events.findAllById(eventIds)) {
            eventNames.put(e.getId(), e.getName());
        }
        // Live #2: segment names for the whole org (prebuilt + custom is a small set).
        Map<UUID, String> segmentNames = new HashMap<>();
        for (Segment seg : segmentRepo.findByOrgId(orgId)) {
            segmentNames.put(seg.getId(), seg.getName());
        }
        // Live #3: SMS is locked when the org has zero opted-in phones to send to.
        boolean smsLocked = memberships.countSmsSubscribedByOrgId(orgId) == 0;

        return rows.stream()
                .map(r -> toDto(r, eventNames, segmentNames, smsLocked))
                .toList();
    }

    private MomentumSuggestionDto toDto(MomentumSuggestion s,
                                        Map<UUID, String> eventNames,
                                        Map<UUID, String> segmentNames,
                                        boolean smsLocked) {
        MomentumMetrics m = parseMetrics(s.getMetricsSnapshot());
        MomentumTriggerType trigger = parseTrigger(s.getTriggerType());

        // Null rather than a stand-in when the event is genuinely gone: the FE can say so.
        String eventName = s.getEventId() == null ? null : eventNames.get(s.getEventId());
        String segmentLabel = resolveSegmentLabel(s.getDraftPayload(), segmentNames);

        // Prose only where the snapshot actually supports it. An unparseable/legacy snapshot
        // yields nulls — the card degrades to its raw numbers instead of inventing a story.
        String headline = m == null ? null : MomentumProse.headline(trigger, m);
        String pace = m == null ? null : MomentumProse.pace(trigger, m);
        String daysOutLabel = m == null ? null : MomentumProse.daysOutLabel(trigger, m);
        List<Integer> spark = m == null || !m.hasSpark() ? null : m.spark();

        return new MomentumSuggestionDto(
                s.getId(), s.getEventId(), eventName, s.getTriggerType(), s.getStatus(),
                s.getMetricsSnapshot(), s.getDraftPayload(), s.getCampaignId(), s.getSuggestedAt(),
                headline, pace, daysOutLabel, spark, segmentLabel, smsLocked);
    }

    /** Human name of the draft's target segment, or null when unset/deleted (never guessed). */
    private String resolveSegmentLabel(String draftPayload, Map<UUID, String> segmentNames) {
        if (draftPayload == null || draftPayload.isBlank()) return null;
        try {
            JsonNode n = json.readTree(draftPayload);
            String id = text(n, "segmentId", null);
            if (id == null || id.isBlank()) return null;
            return segmentNames.get(UUID.fromString(id));
        } catch (Exception e) {
            return null; // malformed draft or non-UUID segment id — unknown, so say nothing
        }
    }

    /**
     * Rehydrate the frozen metrics snapshot. Tolerant by design: rows written before a field
     * existed simply lack it, and this must not 500 the whole list — the missing field becomes
     * absent prose, not fabricated prose.
     */
    private MomentumMetrics parseMetrics(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return null;
        try {
            JsonNode n = json.readTree(snapshot);
            if (!n.isObject()) return null;
            List<Integer> spark = new ArrayList<>();
            JsonNode sparkNode = n.get("spark");
            if (sparkNode != null && sparkNode.isArray()) {
                for (JsonNode p : sparkNode) spark.add(p.asInt());
            }
            JsonNode startNode = n.get("sparkStartDate");
            LocalDate sparkStart = startNode == null || startNode.isNull()
                    ? null : LocalDate.parse(startNode.asText());
            return new MomentumMetrics(
                    n.path("sold").asInt(),
                    n.path("capacity").asInt(),
                    n.path("sellThroughPct").asInt(),
                    n.path("daysOut").asInt(),
                    n.path("hoursSinceOnSale").asLong(),
                    n.path("velocity7d").asDouble(),
                    n.path("hoursToStart").asLong(),
                    List.copyOf(spark),
                    sparkStart,
                    n.path("ticketsPerDay7d").asDouble());
        } catch (Exception e) {
            return null;
        }
    }

    private MomentumTriggerType parseTrigger(String wire) {
        if (wire == null) return null;
        try { return MomentumTriggerType.fromWire(wire); }
        catch (Exception e) { return null; }
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
