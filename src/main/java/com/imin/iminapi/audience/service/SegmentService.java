package com.imin.iminapi.audience.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.dto.SegmentDto;
import com.imin.iminapi.audience.dto.SegmentResolveDto;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Segment CRUD, snapshot, and resolve.
 * Prebuilt segments are provisioned at org creation (or lazily on first list call).
 */
@Service
public class SegmentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SegmentService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SegmentRepository segmentRepo;
    private final MembershipRepository membershipRepo;
    private final OrganizationRepository orgRepo;
    private final AuditLogger auditLogger;
    private final FanFeatureRepository fanFeatureRepo;
    private final EventRepository eventRepo;
    private final ConsentGate consentGate;
    private final AudiencePlanLogic planLogic;

    public SegmentService(SegmentRepository segmentRepo,
                          MembershipRepository membershipRepo,
                          OrganizationRepository orgRepo,
                          AuditLogger auditLogger,
                          FanFeatureRepository fanFeatureRepo,
                          EventRepository eventRepo,
                          ConsentGate consentGate,
                          AudiencePlanLogic planLogic) {
        this.segmentRepo = segmentRepo;
        this.membershipRepo = membershipRepo;
        this.orgRepo = orgRepo;
        this.auditLogger = auditLogger;
        this.fanFeatureRepo = fanFeatureRepo;
        this.eventRepo = eventRepo;
        this.consentGate = consentGate;
        this.planLogic = planLogic;
    }

    /** The 8 genre bucket keys a {@code genre} rule accepts. */
    public Set<String> genreBuckets() {
        return Set.copyOf(planLogic.genres().whitelist());
    }

    @Transactional(readOnly = true)
    public List<Segment> listSegments(UUID orgId) {
        return segmentRepo.findByOrgId(orgId).stream()
                .filter(seg -> !PrebuiltSegment.isRetired(seg.getPrebuiltKey()))
                .filter(seg -> !seg.isSystemOrigin())
                .toList();
    }

    @Transactional
    public Segment createSegment(UUID orgId, String name, String kind, String rulesJson, AuthPrincipal principal) {
        String trimmedName = name == null ? null : name.trim();
        if (trimmedName == null || trimmedName.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                    Map.of("name", "Segment name is required"));
        }
        validateRulesJson(orgId, rulesJson);
        // Names are how organizers tell segments apart, and a second "VIP" beside the
        // prebuilt one is exactly the row that used to be resolved with somebody else's
        // rules. Resolution no longer routes on the name (see PrebuiltSegment), so this is
        // a clarity guard rather than a correctness one — hence a clean 409 at create time
        // instead of a destructive de-duplicating migration over segments organizers own.
        if (segmentRepo.existsByOrgIdAndName(orgId, trimmedName) && visibleNameTaken(orgId, trimmedName)) {
            throw ApiException.duplicate("name", "A segment named \"" + trimmedName + "\" already exists");
        }
        Segment s = new Segment();
        s.setOrgId(orgId);
        s.setName(trimmedName);
        s.setKind(kind);
        s.setRulesJson(rulesJson);
        Segment saved = segmentRepo.save(s);
        auditLogger.record(principal, AuditActions.SEGMENT_CREATED, "segment", saved.getId(),
                "Segment created: " + trimmedName);
        return saved;
    }

    /** A retired prebuilt is hidden from the list, so its name does not block a new segment. */
    private boolean visibleNameTaken(UUID orgId, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        return listSegments(orgId).stream()
                .anyMatch(seg -> seg.getName() != null && seg.getName().toLowerCase(Locale.ROOT).equals(wanted));
    }

    /**
     * Rejects rules the engine cannot run with a clean 400 (see {@link SegmentRules}): unreadable JSON,
     * unknown fields or operators, non-numeric values on numeric fields, genres outside the 8 buckets and
     * events of another org. Null/blank means everyone and is valid.
     */
    void validateRulesJson(UUID orgId, String rulesJson) {
        String problem = SegmentRules.problem(rulesJson, genreBuckets(), ids -> foreignEvents(orgId, ids));
        if (problem != null) throw ruleError(problem);
    }

    private Set<UUID> foreignEvents(UUID orgId, Set<UUID> ids) {
        Set<UUID> own = eventRepo.findAllById(ids).stream()
                .filter(e -> orgId.equals(e.getOrgId()))
                .map(Event::getId)
                .collect(Collectors.toSet());
        Set<UUID> foreign = new LinkedHashSet<>(ids);
        foreign.removeAll(own);
        return foreign;
    }

    private ApiException ruleError(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                Map.of("rulesJson", message));
    }

    @Transactional
    public void deleteSegment(UUID orgId, UUID segmentId, AuthPrincipal principal) {
        // A plan arm or Momentum snapshot is hidden and backs a campaign, so it is not the organizer's to delete.
        segmentRepo.findByIdAndOrgId(segmentId, orgId)
                .filter(Segment::isSystemOrigin)
                .ifPresent(seg -> { throw ApiException.notFound("Segment"); });
        int rows = segmentRepo.deleteByIdAndOrgIdAndNotPrebuilt(segmentId, orgId);
        if (rows == 0) throw ApiException.notFound("Segment");
        auditLogger.record(principal, AuditActions.SEGMENT_DELETED, "segment", segmentId, "Segment deleted");
    }

    /**
     * Freeze a segment's current members onto the row, turning it static.
     *
     * <p>Refused for the prebuilt seven, and the refusal is the point: snapshot is
     * one-way — there is no un-snapshot endpoint, and deleteSegment will not remove a
     * prebuilt row so it cannot be dropped and re-created either. One click on "Repeat"
     * therefore used to pin every future Momentum campaign (whose default target IS that
     * segment) to a member list frozen on the day of the click.
     */
    @Transactional
    public Segment snapshot(UUID orgId, UUID segmentId, AuthPrincipal principal) {
        Segment s = requireSegment(orgId, segmentId);
        if (s.isPrebuilt()) {
            throw ApiException.invalidState(
                    "A prebuilt segment always re-evaluates and cannot be snapshotted. "
                            + "Create a segment with these rules and snapshot that instead.");
        }
        List<Membership> resolved = resolveMembers(orgId, s);
        List<String> ids = resolved.stream()
                .map(m -> m.getMembershipId().toString()).toList();
        try {
            s.setSnapshotIds(MAPPER.writeValueAsString(ids));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Serializing a List<String> cannot fail; an empty snapshot would silently
            // empty the segment, so refuse rather than pretend.
            throw new IllegalStateException("Could not serialize segment snapshot", e);
        }
        s.setKind("static");
        Segment saved = segmentRepo.save(s);
        auditLogger.record(principal, AuditActions.SEGMENT_SNAPSHOT, "segment", segmentId,
                "Snapshot taken: " + ids.size() + " members");
        return saved;
    }

    @Transactional(readOnly = true)
    public SegmentResolveDto resolve(UUID orgId, UUID segmentId) {
        Segment s = requireSegment(orgId, segmentId);
        return counts(orgId, resolveMembers(orgId, s));
    }

    /**
     * Matched, ConsentGate-mailable and a count per exclusion reason; the reasons sum to {@code excluded}.
     * {@code matched} counts only members the gate returned a verdict for, so matched = mailable + excluded.
     */
    private SegmentResolveDto counts(UUID orgId, List<Membership> matched) {
        long avgLtv = matched.isEmpty() ? 0
                : matched.stream().mapToLong(Membership::getSpendMinor).sum() / matched.size();
        Map<String, Integer> exclusions = new LinkedHashMap<>();
        for (String reason : ConsentGate.REASONS) exclusions.put(reason, 0);
        int mailable = 0;
        if (!matched.isEmpty()) {
            Map<UUID, Optional<String>> verdicts = consentGate.reasons(orgId,
                    matched.stream().map(Membership::getMembershipId).toList());
            for (Membership m : matched) {
                Optional<String> reason = verdicts.get(m.getMembershipId());
                if (reason == null) continue;       // no verdict (not this org's member): not counted as matched
                if (reason.isEmpty()) mailable++;
                else exclusions.merge(reason.get(), 1, Integer::sum);
            }
        }
        int excluded = exclusions.values().stream().mapToInt(Integer::intValue).sum();
        return new SegmentResolveDto(mailable + excluded, mailable, excluded, avgLtv,
                Collections.unmodifiableMap(exclusions));
    }

    /** Resolve members matching a segment's rules. For static segments, returns snapshot members. */
    public List<Membership> resolveMembers(UUID orgId, Segment segment) {
        if ("static".equals(segment.getKind()) && segment.getSnapshotIds() != null) {
            List<UUID> ids = parseSnapshotIds(segment.getSnapshotIds());
            if (ids.isEmpty()) return List.of();
            // A snapshot taken before an Art.17 request must not carry that member forward:
            // findByIdsAndOrgId is id-keyed and status-blind, unlike the dynamic queries.
            return membershipRepo.findByIdsAndOrgId(ids, orgId).stream()
                    .filter(m -> !"erase_pending".equals(m.getStatus()))
                    .collect(Collectors.toList());
        }
        return applyRules(orgId, segment);
    }

    /**
     * Route to the indexed prebuilt query by the segment's STABLE KEY. A segment with no
     * key is custom and always evaluates its own rules, whatever it is named — routing on
     * the display name meant an organizer's (or the AI namer's) "VIP" silently resolved to
     * the prebuilt VIP query while the dashboard showed that organizer's own rules.
     */
    private List<Membership> applyRules(UUID orgId, Segment segment) {
        PrebuiltSegment prebuilt = PrebuiltSegment.byKey(segment.getPrebuiltKey());
        if (prebuilt == null) return applyJsonRules(orgId, segment.getRulesJson());
        return switch (prebuilt) {
            case REPEAT           -> membershipRepo.findRepeats(orgId);
            case VIP              -> membershipRepo.findVips(orgId);
            case LAPSED           -> membershipRepo.findLapsed(orgId);
            case FIRST_TIMERS     -> membershipRepo.findFirstTimers(orgId);
            case PROMOTERS        -> membershipRepo.findPromoters(orgId);
            case BOUGHT_NO_SHOWED -> membershipRepo.findBoughtNoShowed(orgId);
            case NEWEST_30D       -> membershipRepo.findNewest30d(orgId);
        };
    }

    private List<Membership> applyJsonRules(UUID orgId, String rulesJson) {
        // Generic rule evaluation: load the org's memberships and filter in Java.
        List<Membership> all = membershipRepo.findAllByOrgId(orgId);
        SegmentRules.Parsed rules = parseRules(rulesJson);
        if (rules == null) return List.of();
        if (rules.everyone()) return all;
        SegmentFacts facts = loadFacts(orgId, rules);
        return all.stream()
                .filter(m -> SegmentRules.matches(rules, SegmentRuleRow.of(m), facts))
                .collect(Collectors.toList());
    }

    /**
     * Parsed rules, "everyone" for no rules (the documented meaning of a blank rules_json) and
     * {@code null} for a rule set the engine could not read or holding any rule it cannot run, which matches nobody: falling back to the
     * entire audience would be the wrong direction for a list that feeds RecipientMaterializer.
     */
    private SegmentRules.Parsed parseRules(String rulesJson) {
        SegmentRules.Parsed parsed = SegmentRules.parse(rulesJson);
        if (parsed == null) {
            log.warn("Segment rules_json could not be parsed; the segment matches nobody");
            return null;
        }
        if (!SegmentRules.runnable(parsed)) {
            // A rule the engine cannot run is false; inside a not group that would mean everyone.
            log.warn("Segment rules_json holds a rule the engine cannot run; the segment matches nobody");
            return null;
        }
        return parsed;
    }

    /** Fan features and ticket facts, read only for the fields the rules use. */
    SegmentFacts loadFacts(UUID orgId, SegmentRules.Parsed rules) {
        Set<String> fields = rules.fields();
        Map<UUID, SegmentFacts.Features> features = Map.of();
        if (fields.stream().anyMatch(SegmentRules.FAN_FEATURE_FIELDS::contains)) {
            features = new HashMap<>();
            for (Object[] row : fanFeatureRepo.findSegmentFactsByOrgId(orgId)) {
                features.put((UUID) row[0], new SegmentFacts.Features(
                        (String) row[1], tasteGenres((String) row[2]), jsonStrings((String) row[3])));
            }
        }
        Map<UUID, Set<String>> attended = Map.of();
        Set<UUID> eventIds = new LinkedHashSet<>();
        for (SegmentRules.Rule r : rules.rulesOn("attended_event")) {
            for (String v : SegmentRules.values(r)) {
                try {
                    eventIds.add(UUID.fromString(v));
                } catch (IllegalArgumentException ignored) {
                    // Not an event id: that value matches nobody.
                }
            }
        }
        if (!eventIds.isEmpty()) {
            attended = new HashMap<>();
            for (Object[] row : membershipRepo.findAttendedEventPairs(orgId, eventIds)) {
                attended.computeIfAbsent((UUID) row[0], k -> new HashSet<>())
                        .add(row[1].toString().toLowerCase(Locale.ROOT));
            }
        }
        return new SegmentFacts(features, attended);
    }

    /** Buckets with a positive weight in a taste JSON object. */
    private static Set<String> tasteGenres(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            Map<String, Double> taste = MAPPER.readValue(json, new TypeReference<Map<String, Double>>() {});
            Set<String> out = new HashSet<>();
            taste.forEach((k, v) -> {
                if (v != null && v > 0) out.add(k);
            });
            return out;
        } catch (Exception e) {
            return Set.of();
        }
    }

    private static Set<String> jsonStrings(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try {
            return new HashSet<>(MAPPER.readValue(json, new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * How many members a segment currently holds, WITHOUT materializing them.
     *
     * <p>{@code GET /audience/segments} asks this of every segment on every call. It used
     * to go through resolveMembers, so each custom segment loaded the org's entire
     * memberships table as entities — N segments, N full copies, per dashboard load.
     * Prebuilts now count with their indexed query, static segments count their snapshot
     * ids, and custom segments run the rule engine over a narrow projection.
     */
    @Transactional(readOnly = true)
    public int liveCount(UUID orgId, Segment segment) {
        if ("static".equals(segment.getKind()) && segment.getSnapshotIds() != null) {
            List<UUID> ids = parseSnapshotIds(segment.getSnapshotIds());
            return ids.isEmpty() ? 0 : (int) membershipRepo.countByIdsAndOrgId(ids, orgId);
        }
        PrebuiltSegment prebuilt = PrebuiltSegment.byKey(segment.getPrebuiltKey());
        if (prebuilt != null) {
            return (int) switch (prebuilt) {
                case REPEAT           -> membershipRepo.countRepeats(orgId);
                case VIP              -> membershipRepo.countVips(orgId);
                case LAPSED           -> membershipRepo.countLapsed(orgId);
                case FIRST_TIMERS     -> membershipRepo.countFirstTimers(orgId);
                case PROMOTERS        -> membershipRepo.countPromoters(orgId);
                case BOUGHT_NO_SHOWED -> membershipRepo.countBoughtNoShowed(orgId);
                case NEWEST_30D       -> membershipRepo.countNewest30d(orgId);
            };
        }
        List<SegmentRuleRow> rows = membershipRepo.findRuleRowsByOrgId(orgId);
        SegmentRules.Parsed rules = parseRules(segment.getRulesJson());
        if (rules == null) return 0;                 // unreadable rules match nobody
        if (rules.everyone()) return rows.size();    // no rules means everyone
        SegmentFacts facts = loadFacts(orgId, rules);
        return (int) rows.stream().filter(r -> SegmentRules.matches(rules, r, facts)).count();
    }

    private List<UUID> parseSnapshotIds(String json) {
        try {
            List<String> strings = MAPPER.readValue(json, new TypeReference<>() {});
            return strings.stream().map(UUID::fromString).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Public accessor for controller CSV export — returns 404 for cross-org or unknown id. */
    @Transactional(readOnly = true)
    public Segment requireSegmentForOrg(UUID orgId, UUID segmentId) {
        return requireSegment(orgId, segmentId);
    }

    private Segment requireSegment(UUID orgId, UUID segmentId) {
        return segmentRepo.findByIdAndOrgId(segmentId, orgId)
                .orElseThrow(() -> ApiException.notFound("Segment"));
    }

    /**
     * Provision the 7 prebuilt segments for a new org. Idempotent AND concurrency-safe:
     * the cheap existence check short-circuits the common case, and the first-time path takes
     * a {@code FOR UPDATE} lock on the org row and re-checks under it, so two parallel first
     * calls (e.g. two concurrent GET /segments) can never both insert the prebuilt set.
     * If the org row is absent (some unit tests seed segments without an org), the lock is a
     * no-op and the double-check still keeps single-threaded callers correct.
     */
    @Transactional
    public void ensurePrebuiltSegments(UUID orgId) {
        if (segmentRepo.hasPrebuiltSegments(orgId)) return;
        // Serialize concurrent first-time seeders for this org.
        orgRepo.findByIdForUpdate(orgId);
        if (segmentRepo.hasPrebuiltSegments(orgId)) return; // re-check under the lock
        for (PrebuiltSegment pb : PrebuiltSegment.values()) {
            if (pb.retired()) continue;
            Segment s = new Segment();
            s.setOrgId(orgId);
            s.setName(pb.displayName());
            s.setKind("dynamic");
            s.setPrebuilt(true);
            s.setPrebuiltKey(pb.key());
            s.setRulesJson(pb.rulesJson());
            segmentRepo.save(s);
        }
    }

    /**
     * The org's prebuilt Repeat segment id (Momentum's v1 default target), or null if not
     * provisioned. Resolved by stable key: matching on the display name would have picked
     * up any segment an organizer happened to call "Repeat".
     */
    @Transactional(readOnly = true)
    public UUID defaultTargetSegmentId(UUID orgId) {
        return segmentRepo.findByOrgIdAndPrebuiltKey(orgId, PrebuiltSegment.REPEAT.key())
                .map(Segment::getId)
                .orElse(null);
    }

    /** Membership ids for a segment id; tolerant of a null/unknown id (returns empty). */
    @Transactional(readOnly = true)
    public List<UUID> resolveMembershipIds(UUID orgId, UUID segmentId) {
        if (segmentId == null) return List.of();
        return segmentRepo.findByIdAndOrgId(segmentId, orgId)
                .map(seg -> resolveMembers(orgId, seg).stream()
                        .map(Membership::getMembershipId)
                        .toList())
                .orElse(List.of());
    }

    /**
     * Preview the matched / mailable counts for a TRANSIENT (unsaved) set of custom JSON rules,
     * evaluated directly against the org's memberships — WITHOUT persisting a segment and WITHOUT
     * the prebuilt name-based routing in {@link #applyRules}. Used by the AI-draft preview so the
     * organizer sees real counts before confirming a create. Mirrors {@link #resolve}'s math
     * (an empty/blank {@code rulesJson} matches everyone, exactly like the engine).
     */
    @Transactional(readOnly = true)
    public SegmentResolveDto previewRules(UUID orgId, String rulesJson) {
        return counts(orgId, applyJsonRules(orgId, rulesJson));
    }

    /** {@link #previewRules} for organizer-typed rules: invalid rules are a 400, not an empty preview. */
    @Transactional(readOnly = true)
    public SegmentResolveDto previewValidated(UUID orgId, String rulesJson) {
        validateRulesJson(orgId, rulesJson);
        return previewRules(orgId, rulesJson);
    }
}
