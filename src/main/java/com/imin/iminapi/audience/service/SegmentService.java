package com.imin.iminapi.audience.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.dto.SegmentDto;
import com.imin.iminapi.audience.dto.SegmentResolveDto;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
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
    private static final TypeReference<List<Map<String, String>>> RULES_TYPE = new TypeReference<>() {};

    /** Numeric membership fields the rule engine (matchRule) compares with a parsed long. */
    private static final Set<String> NUMERIC_FIELDS =
            Set.of("events", "spend_minor", "recency", "no_show", "nps");
    /** String membership fields the rule engine compares by equality. */
    private static final Set<String> STRING_FIELDS =
            Set.of("lifecycle", "consent_status", "consent_basis");
    /** Comparison operators the rule engine understands. */
    private static final Set<String> OPERATORS = Set.of(">=", "<=", ">", "<", "==");

    private final SegmentRepository segmentRepo;
    private final MembershipRepository membershipRepo;
    private final OrganizationRepository orgRepo;
    private final AuditLogger auditLogger;

    public SegmentService(SegmentRepository segmentRepo,
                          MembershipRepository membershipRepo,
                          OrganizationRepository orgRepo,
                          AuditLogger auditLogger) {
        this.segmentRepo = segmentRepo;
        this.membershipRepo = membershipRepo;
        this.orgRepo = orgRepo;
        this.auditLogger = auditLogger;
    }

    @Transactional(readOnly = true)
    public List<Segment> listSegments(UUID orgId) {
        return segmentRepo.findByOrgId(orgId);
    }

    @Transactional
    public Segment createSegment(UUID orgId, String name, String kind, String rulesJson, AuthPrincipal principal) {
        String trimmedName = name == null ? null : name.trim();
        if (trimmedName == null || trimmedName.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                    Map.of("name", "Segment name is required"));
        }
        validateRulesJson(rulesJson);
        // Names are how organizers tell segments apart, and a second "VIP" beside the
        // prebuilt one is exactly the row that used to be resolved with somebody else's
        // rules. Resolution no longer routes on the name (see PrebuiltSegment), so this is
        // a clarity guard rather than a correctness one — hence a clean 409 at create time
        // instead of a destructive de-duplicating migration over segments organizers own.
        if (segmentRepo.existsByOrgIdAndName(orgId, trimmedName)) {
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

    /**
     * Validate that {@code rulesJson} (when present) is a JSON array of {field, operator, value}
     * rules the engine actually supports — see {@link #matchRule}. A null/blank value means
     * "everyone" and is valid. Anything unparseable or referencing an unknown field/operator, or
     * a non-numeric value on a numeric field, is rejected with a clean 400 rather than being
     * silently coerced (the old engine matched such rules against nobody) or blowing up as a 500.
     */
    void validateRulesJson(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank()) return;
        List<Map<String, String>> rules;
        try {
            rules = MAPPER.readValue(rulesJson, RULES_TYPE);
        } catch (Exception e) {
            throw ruleError("must be a JSON array of {field, operator, value} rules");
        }
        for (int i = 0; i < rules.size(); i++) {
            Map<String, String> rule = rules.get(i);
            String field = rule.get("field");
            String op = rule.get("operator");
            String val = rule.get("value");
            if (field == null || field.isBlank()) {
                throw ruleError("rule " + (i + 1) + " is missing a field");
            }
            boolean numeric = NUMERIC_FIELDS.contains(field);
            if (!numeric && !STRING_FIELDS.contains(field)) {
                throw ruleError("rule " + (i + 1) + " uses an unknown field '" + field + "'");
            }
            if (op == null || !OPERATORS.contains(op)) {
                throw ruleError("rule " + (i + 1) + " uses an unsupported operator '" + op + "'");
            }
            if (val == null || val.isBlank()) {
                throw ruleError("rule " + (i + 1) + " is missing a value");
            }
            if (numeric) {
                try {
                    Long.parseLong(val.trim());
                } catch (NumberFormatException nfe) {
                    throw ruleError("rule " + (i + 1) + " on '" + field + "' needs a numeric value");
                }
            }
        }
    }

    private ApiException ruleError(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                Map.of("rulesJson", message));
    }

    @Transactional
    public void deleteSegment(UUID orgId, UUID segmentId, AuthPrincipal principal) {
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
        List<Membership> matched = resolveMembers(orgId, s);
        long avgLtv = matched.isEmpty() ? 0
                : matched.stream().mapToLong(Membership::getSpendMinor).sum() / matched.size();
        // mailable = subscribed + lawful basis + not counted here (gate not called in resolve — count only)
        long mailable = matched.stream()
                .filter(m -> "subscribed".equals(m.getConsentStatus()) && m.getConsentBasis() != null)
                .count();
        int excluded = matched.size() - (int) mailable;
        return new SegmentResolveDto(matched.size(), (int) mailable, excluded, avgLtv);
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
        // Generic rule evaluation — load all memberships and filter in Java
        // For Tier C with reasonable org sizes this is acceptable
        List<Membership> all = membershipRepo.findAllByOrgId(orgId);
        List<Map<String, String>> rules = parseRules(rulesJson);
        if (rules == null) return List.of();
        if (rules.isEmpty()) return all;
        return all.stream().filter(m -> rulesMatch(m, rules)).collect(Collectors.toList());
    }

    /**
     * Parsed rules: an EMPTY list for "no rules" (matches everyone, the documented meaning
     * of a blank rules_json) and {@code null} for a rule set the engine could not read.
     *
     * <p>Those two must not collapse into one another. An unreadable rule set used to fall
     * back to "the entire audience" — the wrong direction by a mile for a list that feeds
     * RecipientMaterializer. validateRulesJson guards the create path, but rows written
     * before it, a truncated TEXT value or any future writer all land here.
     */
    private List<Map<String, String>> parseRules(String rulesJson) {
        if (rulesJson == null || rulesJson.isBlank()) return List.of();
        try {
            return MAPPER.readValue(rulesJson, RULES_TYPE);
        } catch (Exception e) {
            log.warn("Segment rules_json could not be parsed; the segment matches nobody: {}",
                    e.getMessage());
            return null;
        }
    }

    private boolean rulesMatch(Membership m, List<Map<String, String>> rules) {
        for (Map<String, String> rule : rules) {
            String field = rule.get("field");
            String op = rule.get("operator");
            String val = rule.get("value");
            if (!matchRule(m, field, op, val)) return false;
        }
        return true;
    }

    private boolean matchRule(Membership m, String field, String op, String val) {
        try {
            long v = Long.parseLong(val);
            long actual = switch (field) {
                case "events"      -> m.getEvents();
                case "spend_minor" -> m.getSpendMinor();
                case "recency"     -> m.getRecencyDays() == null ? Long.MAX_VALUE : m.getRecencyDays();
                case "no_show"     -> m.getNoShow();
                case "nps"         -> m.getNps() == null ? Long.MIN_VALUE : m.getNps();
                default            -> 0;
            };
            return switch (op) {
                case ">="  -> actual >= v;
                case "<="  -> actual <= v;
                case ">"   -> actual > v;
                case "<"   -> actual < v;
                case "=="  -> actual == v;
                default    -> false;
            };
        } catch (NumberFormatException e) {
            // String comparison for non-numeric fields
            String actual = switch (field) {
                case "lifecycle"       -> m.getLifecycle();
                case "consent_status"  -> m.getConsentStatus();
                case "consent_basis"   -> m.getConsentBasis();
                default                -> "";
            };
            return val != null && val.equals(actual);
        }
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
        List<Membership> matched = applyJsonRules(orgId, rulesJson);
        long avgLtv = matched.isEmpty() ? 0
                : matched.stream().mapToLong(Membership::getSpendMinor).sum() / matched.size();
        long mailable = matched.stream()
                .filter(m -> "subscribed".equals(m.getConsentStatus()) && m.getConsentBasis() != null)
                .count();
        int excluded = matched.size() - (int) mailable;
        return new SegmentResolveDto(matched.size(), (int) mailable, excluded, avgLtv);
    }
}
