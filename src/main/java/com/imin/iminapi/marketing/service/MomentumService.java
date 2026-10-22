package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.marketing.dto.CampaignDto;      // Phase 2
import com.imin.iminapi.marketing.dto.MomentumEngineStateDto;
import com.imin.iminapi.marketing.dto.MomentumFacts;
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.Campaign;        // Phase 2
import com.imin.iminapi.marketing.model.MomentumFloorSkip;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.model.MomentumTriggerType;
import com.imin.iminapi.marketing.repository.CampaignRepository;   // Phase 2
import com.imin.iminapi.marketing.repository.MomentumFloorSkipRepository;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    /** A skip older than two hourly passes is no longer the current reason Momentum is quiet. */
    private static final Duration FLOOR_SKIP_FRESHNESS = Duration.ofHours(2);
    private static final int LOG_LIMIT = 10;
    // V52 column widths the approved draft lands in — model copy is clamped to them.
    private static final int MAX_NAME = 120;
    private static final int MAX_SUBJECT = 200;
    private static final int MAX_PREHEADER = 200;

    private final MomentumSuggestionRepository suggestions;
    private final CampaignRepository campaigns;
    private final EventRepository events;
    private final SegmentRepository segmentRepo;
    private final MembershipRepository memberships;
    private final MomentumThresholds thresholds;
    private final CampaignAttributionService attribution;
    private final MomentumFloorSkipRepository floorSkips;
    private final ObjectMapper json = new ObjectMapper();

    public MomentumService(MomentumSuggestionRepository suggestions, CampaignRepository campaigns,
                           EventRepository events, SegmentRepository segmentRepo,
                           MembershipRepository memberships, MomentumThresholds thresholds,
                           CampaignAttributionService attribution, MomentumFloorSkipRepository floorSkips) {
        this.suggestions = suggestions;
        this.campaigns = campaigns;
        this.events = events;
        this.segmentRepo = segmentRepo;
        this.memberships = memberships;
        this.thresholds = thresholds;
        this.attribution = attribution;
        this.floorSkips = floorSkips;
    }

    /**
     * The org's suggestions, enriched into the card read-model (spec §6.4).
     *
     * <p>Resolution strategy is per-field and deliberate — see the field-by-field rationale on
     * {@link MomentumSuggestionDto}. In short: the METRICS stay frozen in {@code metrics_snapshot}
     * (they are the evidence for why the engine fired and must not drift), the FACTS restate that
     * frozen snapshot on read for the webapp to word in the organizer's language, and
     * IDENTITY/CAPABILITY ({@code eventName}, {@code segmentLabel}, {@code smsLocked}) is resolved
     * LIVE so a rename or a fresh SMS opt-in is reflected.
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
        // Live #2: segment rows for the whole org (prebuilt + custom is a small set).
        Map<UUID, Segment> segmentsById = new HashMap<>();
        for (Segment seg : segmentRepo.findByOrgId(orgId)) {
            segmentsById.put(seg.getId(), seg);
        }
        // Live #3: SMS is locked when the org has zero opted-in phones to send to.
        boolean smsLocked = memberships.countSmsSubscribedByOrgId(orgId) == 0;

        return rows.stream()
                .map(r -> toDto(r, eventNames, segmentsById, smsLocked))
                .toList();
    }

    private MomentumSuggestionDto toDto(MomentumSuggestion s,
                                        Map<UUID, String> eventNames,
                                        Map<UUID, Segment> segmentsById,
                                        boolean smsLocked) {
        Snapshot snap = parseSnapshot(s.getMetricsSnapshot());

        // Null rather than a stand-in when the event is genuinely gone: the FE can say so.
        String eventName = s.getEventId() == null ? null : eventNames.get(s.getEventId());
        Segment segment = resolveSegment(s.getDraftPayload(), segmentsById);

        // An unparseable snapshot gives no facts: the card shows less instead of inventing numbers.
        MomentumFacts facts = snap == null ? null : MomentumFacts.from(snap.metrics(), snap.clickedNoTicket());
        List<Integer> spark = snap == null || !snap.metrics().hasSpark() ? null : snap.metrics().spark();

        return new MomentumSuggestionDto(
                s.getId(), s.getEventId(), eventName, s.getTriggerType(), s.getStatus(),
                s.getMetricsSnapshot(), s.getDraftPayload(), s.getCampaignId(), s.getSuggestedAt(),
                facts, spark,
                segment == null ? null : segment.getName(),
                segment == null ? null : segment.getPrebuiltKey(),
                smsLocked);
    }

    /** The draft's target segment, or null when unset/deleted (never guessed). */
    private Segment resolveSegment(String draftPayload, Map<UUID, Segment> segmentsById) {
        if (draftPayload == null || draftPayload.isBlank()) return null;
        try {
            JsonNode n = json.readTree(draftPayload);
            String id = text(n, "segmentId", null);
            if (id == null || id.isBlank()) return null;
            return segmentsById.get(UUID.fromString(id));
        } catch (Exception e) {
            return null; // malformed draft or non-UUID segment id — unknown, so say nothing
        }
    }

    /** A frozen snapshot read back: its metrics and, for clicked_no_buy, the clicker count. */
    private record Snapshot(MomentumMetrics metrics, Integer clickedNoTicket) {}

    /**
     * Rehydrate the frozen metrics snapshot. Tolerant by design: rows written before a field
     * existed simply lack it, and this must not 500 the whole list.
     */
    private Snapshot parseSnapshot(String snapshot) {
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
            JsonNode clicked = n.get("clickedNoTicket");
            Integer clickedNoTicket = clicked != null && clicked.isNumber() ? clicked.asInt() : null;
            return new Snapshot(new MomentumMetrics(
                    n.path("sold").asInt(),
                    n.path("capacity").asInt(),
                    n.path("sellThroughPct").asInt(),
                    n.path("daysOut").asInt(),
                    n.path("hoursSinceOnSale").asLong(),
                    n.path("velocity7d").asDouble(),
                    n.path("hoursToStart").asLong(),
                    List.copyOf(spark),
                    sparkStart,
                    n.path("ticketsPerDay7d").asDouble(),
                    // Snapshots before seatsLeft read sold over every mode, so capacity − sold was the seats.
                    n.has("seatsLeft") ? n.path("seatsLeft").asInt()
                            : Math.max(0, n.path("capacity").asInt() - n.path("sold").asInt())),
                    clickedNoTicket);
        } catch (Exception e) {
            return null;
        }
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
        Map<UUID, String> watched = new LinkedHashMap<>();
        for (Event e : events.findMomentumCandidates(now)) {
            if (orgId.equals(e.getOrgId())) watched.put(e.getId(), e.getName());
        }
        int watching = watched.size();

        // waiting: the org's live 'suggested' suggestions.
        int waiting = suggestions.findByOrgIdAndStatusOrderBySuggestedAtDesc(orgId, "suggested").size();

        // approved/dismissed acted-on in the trailing 30 days.
        Instant since = now.minus(ACTED_WINDOW);
        int approved30d = (int) suggestions.countByOrgIdAndStatusAndActedAtAfter(orgId, "approved", since);
        int dismissed30d = (int) suggestions.countByOrgIdAndStatusAndActedAtAfter(orgId, "dismissed", since);

        // attributedMinor: lifetime ticket sales of the org's Momentum campaigns created (approved) in the window.
        List<UUID> momentumCampaigns = campaigns.findByOrgCreatedSince(orgId, since).stream()
                .filter(c -> "momentum".equals(c.getOrigin()))
                .map(Campaign::getId)
                .toList();
        long attributedMinor = attribution.attributedRevenueMinorByCampaign(orgId, momentumCampaigns)
                .values().stream().mapToLong(Long::longValue).sum();

        // log: 10 most recent non-'suggested' suggestions, newest first; event names in one batch.
        List<MomentumSuggestion> acted = suggestions.findRecentActedByOrg(orgId, PageRequest.of(0, LOG_LIMIT));
        Map<UUID, String> logNames = new HashMap<>();
        for (Event e : events.findAllById(acted.stream().map(MomentumSuggestion::getEventId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()))) {
            logNames.put(e.getId(), e.getName());
        }
        List<MomentumEngineStateDto.LogEntry> log = new ArrayList<>();
        for (MomentumSuggestion s : acted) {
            Instant at = s.getActedAt() != null ? s.getActedAt() : s.getSuggestedAt();
            log.add(new MomentumEngineStateDto.LogEntry(
                    s.getStatus(), s.getTriggerType(), s.getEventId(), logNames.get(s.getEventId()), at));
        }

        // belowFloor: fresh skips of events still watched, so a past night or an old skip never shows.
        List<MomentumEngineStateDto.FloorSkipEntry> belowFloor = floorSkips
                .findByOrgIdAndCheckedAtAfter(orgId, now.minus(FLOOR_SKIP_FRESHNESS)).stream()
                .filter(k -> watched.containsKey(k.getEventId()))
                .sorted(Comparator.comparing(MomentumFloorSkip::getCheckedAt).reversed())
                .map(k -> new MomentumEngineStateDto.FloorSkipEntry(k.getEventId(), watched.get(k.getEventId()),
                        k.getTriggerType(), k.getSendable(), k.getFloor(), k.getCheckedAt()))
                .toList();

        MomentumEngineStateDto.Rules rules = new MomentumEngineStateDto.Rules(
                thresholds.getLaunchAfterHours(), thresholds.getLaunchMaxSellThroughPct(),
                thresholds.getSlumpMinDaysOut(), thresholds.getSlumpMinSellThroughPct(), thresholds.getSlumpTargetPct(),
                thresholds.getUrgencyBeforeHours(), thresholds.getUrgencyMinSellThroughPct(),
                thresholds.getUrgencyMaxSellThroughPct(), (int) MomentumEvaluator.CLICKED_NO_BUY_WINDOW.toDays());
        List<String> triggers = Arrays.stream(MomentumTriggerType.values()).map(MomentumTriggerType::wireValue).toList();

        return new MomentumEngineStateDto(
                watching, waiting, approved30d, dismissed30d, attributedMinor,
                thresholds.getMinAudienceFloor(), thresholds.getCooldownDays(), log, belowFloor, rules, triggers);
    }

    /**
     * Turns a live suggestion into one draft: the conditional claim runs before the insert, so a second click,
     * another tab, a dismissal or the expiry sweep that got there first answers 409 and no draft is made. The draft
     * leaves out people already holding a ticket for the event, and a draft carrying the event poster uses the
     * poster template.
     */
    @Transactional
    public CampaignDto approve(AuthPrincipal principal, UUID suggestionId) {
        MomentumSuggestion s = loadOwned(principal, suggestionId);
        JsonNode draft = parse(s.getDraftPayload());

        Instant now = Instant.now();
        UUID campaignId = UUID.randomUUID();
        if (suggestions.approveIfLive(s.getId(), principal.orgId(), campaignId, now) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Suggestion is not live");
        }
        Campaign c = new Campaign();
        c.setId(campaignId);
        c.setOrgId(principal.orgId());
        c.setChannel("email");
        c.setName(clamp(text(draft, "subject", "Momentum campaign"), MAX_NAME));
        c.setStatus("draft");
        c.setEventId(s.getEventId());
        c.setSegmentId(uuidOrNull(text(draft, "segmentId", null)));
        c.setOrigin("momentum");
        c.setMomentumSuggestionId(s.getId());
        c.setSkipTicketHolders(true);
        c.setSubject(clamp(text(draft, "subject", null), MAX_SUBJECT));
        c.setPreheader(clamp(text(draft, "preheader", null), MAX_PREHEADER));
        c.setBodyMd(text(draft, "bodyMd", null));
        String posterUrl = text(draft, "posterUrl", null);
        if (posterUrl != null && !posterUrl.isBlank()) c.setTemplateKey("poster");
        // MomentumCopyGenerator wrote this copy: mark it for the Art.50(2) marker.
        c.setSubjectAiGenerated(c.getSubject() != null);
        c.setBodyAiGenerated(c.getBodyMd() != null || c.getPreheader() != null);
        // Drafts written before the language was recorded were English.
        String draftLocale = com.imin.iminapi.email.EmailLocale.normalizeOrNull(text(draft, "locale", null));
        c.setLocale(draftLocale != null ? draftLocale : "en");
        c.setCreatedBy(principal.userId());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        campaigns.save(c);

        return CampaignDto.from(c);
    }

    /** Dismisses a live suggestion through one conditional update; an approved or expired one answers 409. */
    @Transactional
    public void dismiss(AuthPrincipal principal, UUID suggestionId) {
        MomentumSuggestion s = loadOwned(principal, suggestionId);
        if (suggestions.dismissIfLive(s.getId(), principal.orgId(), Instant.now()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Suggestion is not live");
        }
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

    /**
     * Clamp model copy to the column it lands in (V52: name VARCHAR(120), subject and
     * preheader VARCHAR(200)). MomentumCopyGenerator only ASKS the model for 60/90 chars, so
     * an over-long subject was a varchar overflow -> DataIntegrityViolationException -> a 500
     * on the organizer's Approve click. CampaignService.create does NOT clamp subject or
     * preheader — it only truncates `name` — so this clamp is the only guard on the momentum
     * path; the manual path is bounded by @Size on CampaignRequests instead (mkt-edge-7).
     */
    private static String clamp(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
