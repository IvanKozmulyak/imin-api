package com.imin.iminapi.audience.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.dto.SegmentDto;
import com.imin.iminapi.audience.dto.SegmentResolveDto;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, String>>> RULES_TYPE = new TypeReference<>() {};

    private final SegmentRepository segmentRepo;
    private final MembershipRepository membershipRepo;
    private final AuditLogger auditLogger;

    public SegmentService(SegmentRepository segmentRepo,
                          MembershipRepository membershipRepo,
                          AuditLogger auditLogger) {
        this.segmentRepo = segmentRepo;
        this.membershipRepo = membershipRepo;
        this.auditLogger = auditLogger;
    }

    @Transactional(readOnly = true)
    public List<Segment> listSegments(UUID orgId) {
        return segmentRepo.findByOrgId(orgId);
    }

    @Transactional
    public Segment createSegment(UUID orgId, String name, String kind, String rulesJson, AuthPrincipal principal) {
        Segment s = new Segment();
        s.setOrgId(orgId);
        s.setName(name);
        s.setKind(kind);
        s.setRulesJson(rulesJson);
        Segment saved = segmentRepo.save(s);
        auditLogger.record(principal, AuditActions.SEGMENT_CREATED, "segment", saved.getId(),
                "Segment created: " + name);
        return saved;
    }

    @Transactional
    public void deleteSegment(UUID orgId, UUID segmentId, AuthPrincipal principal) {
        int rows = segmentRepo.deleteByIdAndOrgIdAndNotPrebuilt(segmentId, orgId);
        if (rows == 0) throw ApiException.notFound("Segment");
        auditLogger.record(principal, AuditActions.SEGMENT_DELETED, "segment", segmentId, "Segment deleted");
    }

    @Transactional
    public Segment snapshot(UUID orgId, UUID segmentId, AuthPrincipal principal) {
        Segment s = requireSegment(orgId, segmentId);
        List<Membership> resolved = resolveMembers(orgId, s);
        List<String> ids = resolved.stream()
                .map(m -> m.getMembershipId().toString()).toList();
        try {
            s.setSnapshotIds(MAPPER.writeValueAsString(ids));
            s.setKind("static");
        } catch (Exception e) {
            s.setSnapshotIds("[]");
        }
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
            return ids.isEmpty() ? List.of() : membershipRepo.findByIdsAndOrgId(ids, orgId);
        }
        return applyRules(orgId, segment.getName(), segment.getRulesJson());
    }

    private List<Membership> applyRules(UUID orgId, String name, String rulesJson) {
        // Route to prebuilt query methods by segment name (prebuilt segments)
        return switch (name) {
            case "Repeat"           -> membershipRepo.findRepeats(orgId);
            case "VIP"              -> membershipRepo.findVips(orgId);
            case "Lapsed"           -> membershipRepo.findLapsed(orgId);
            case "First-timers"     -> membershipRepo.findFirstTimers(orgId);
            case "Promoters"        -> membershipRepo.findPromoters(orgId);
            case "Bought-no-showed" -> membershipRepo.findBoughtNoShowed(orgId);
            case "Newest-30d"       -> membershipRepo.findNewest30d(orgId);
            default                 -> applyJsonRules(orgId, rulesJson);
        };
    }

    private List<Membership> applyJsonRules(UUID orgId, String rulesJson) {
        // Generic rule evaluation — load all memberships and filter in Java
        // For Tier C with reasonable org sizes this is acceptable
        List<Membership> all = membershipRepo.findAllByOrgId(orgId);
        if (rulesJson == null || rulesJson.isBlank()) return all;
        try {
            List<Map<String, String>> rules = MAPPER.readValue(rulesJson, RULES_TYPE);
            return all.stream().filter(m -> rulesMatch(m, rules)).collect(Collectors.toList());
        } catch (Exception e) {
            return all;
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

    /** Provision the 7 prebuilt segments for a new org. Idempotent — skips if already present. */
    @Transactional
    public void ensurePrebuiltSegments(UUID orgId) {
        if (segmentRepo.hasPrebuiltSegments(orgId)) return;
        List<String[]> prebuilt = List.of(
            new String[]{"Repeat",           "[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"2\"}]"},
            new String[]{"VIP",              "[{\"field\":\"spend_minor\",\"operator\":\">=\",\"value\":\"20000\"},{\"field\":\"events\",\"operator\":\">=\",\"value\":\"4\"}]"},
            new String[]{"Lapsed",           "[{\"field\":\"recency\",\"operator\":\">=\",\"value\":\"90\"},{\"field\":\"consent_status\",\"operator\":\"==\",\"value\":\"subscribed\"}]"},
            new String[]{"First-timers",     "[{\"field\":\"events\",\"operator\":\"==\",\"value\":\"1\"}]"},
            new String[]{"Promoters",        "[{\"field\":\"nps\",\"operator\":\">=\",\"value\":\"9\"}]"},
            new String[]{"Bought-no-showed", "[{\"field\":\"no_show\",\"operator\":\">\",\"value\":\"0\"}]"},
            new String[]{"Newest-30d",       "[{\"field\":\"recency\",\"operator\":\"<=\",\"value\":\"30\"},{\"field\":\"events\",\"operator\":\"<=\",\"value\":\"1\"}]"}
        );
        for (String[] pb : prebuilt) {
            Segment s = new Segment();
            s.setOrgId(orgId);
            s.setName(pb[0]);
            s.setKind("dynamic");
            s.setPrebuilt(true);
            s.setRulesJson(pb[1]);
            segmentRepo.save(s);
        }
    }

    /** The org's prebuilt "Repeat" segment id (Momentum's v1 default target), or null if not provisioned. */
    @Transactional(readOnly = true)
    public UUID defaultTargetSegmentId(UUID orgId) {
        return segmentRepo.findByOrgId(orgId).stream()
                .filter(s -> "Repeat".equals(s.getName()))
                .map(Segment::getId)
                .findFirst()
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
}
