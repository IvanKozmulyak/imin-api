package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.ProofRequirement;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who the audience plan tool may email and why everyone else is excluded; stricter than SendGateService
 * (proven basis only, and contact from the person within the retention window).
 */
@Service
public class ConsentGate {

    public static final String UNSUBSCRIBED = "unsubscribed";
    public static final String SUPPRESSED = "suppressed";
    public static final String OBJECTED = "objected";
    public static final String NO_BASIS = "no_basis";
    public static final String LEGACY_UNPROVEN = "legacy_unproven";
    public static final String RETENTION_3Y = "retention_3y";
    public static final String ERASE_PENDING = "erase_pending";
    public static final String NO_EMAIL = "no_email";

    /** Every exclusion reason, in the order the gate checks them. */
    public static final List<String> REASONS = List.of(
            ERASE_PENDING, NO_EMAIL, UNSUBSCRIBED, SUPPRESSED, OBJECTED, NO_BASIS, LEGACY_UNPROVEN, RETENTION_3Y);

    static final String SOFT_OPT_IN = "soft_opt_in";

    // The id list is bound twice per query; this keeps each statement far below Postgres's 32767 bind limit.
    static final int MAX_IDS_PER_QUERY = 1000;

    // Longer than any source (64) or text_version (32) column, so it never matches; stands in for an empty IN list.
    static final String NEVER_MATCHES = "~".repeat(65);

    /** Org size, plan-mailable count and a count for every reason (0 when none); counts sum to members − mailable. */
    public record Breakdown(int members, int mailable, Map<String, Integer> exclusions) {
        public int legacyNotMailable() { return exclusions.getOrDefault(LEGACY_UNPROVEN, 0); }
    }

    private final FanFeatureRepository repo;
    private final OrganizationRepository orgRepo;
    private final AudiencePlanLogic logic;
    private final AudiencePlanProperties props;
    private final Clock clock;

    public ConsentGate(FanFeatureRepository repo, OrganizationRepository orgRepo, AudiencePlanLogic logic,
                       AudiencePlanProperties props, Clock clock) {
        this.repo = repo;
        this.orgRepo = orgRepo;
        this.logic = logic;
        this.props = props;
        this.clock = clock;
    }

    /** Open/click tracking is off for everyone. */
    public boolean canTrack() {
        return false;
    }

    /** False for a membership of another org or one that does not exist. */
    @Transactional(readOnly = true)
    public boolean canMarket(UUID orgId, UUID membershipId) {
        if (membershipId == null) return false;
        Map<UUID, Optional<String>> r = reasons(orgId, List.of(membershipId));
        return r.containsKey(membershipId) && r.get(membershipId).isEmpty();
    }

    /** Verdict per id (empty = mailable); ids outside the org are silently absent, as in SendGateService. */
    @Transactional(readOnly = true)
    public Map<UUID, Optional<String>> reasons(UUID orgId, Collection<UUID> membershipIds) {
        if (orgId == null || membershipIds == null || membershipIds.isEmpty()) return Map.of();
        Params p = params(orgId);
        List<UUID> ids = membershipIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<UUID, Optional<String>> out = new HashMap<>();
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_QUERY) {
            List<UUID> chunk = ids.subList(from, Math.min(from + MAX_IDS_PER_QUERY, ids.size()));
            for (Object[] row : repo.findExclusionReasons(orgId, p.namedSources, p.namedVersions,
                    p.provenanceSources, p.textVersionSources, p.personSources, p.softOptInBases,
                    p.cutoffAt, p.cutoffDate, chunk)) {
                out.put(uuid(row[0]), Optional.ofNullable((String) row[1]));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<UUID> mailableMembershipIds(UUID orgId) {
        if (orgId == null) return List.of();
        Params p = params(orgId);
        List<UUID> out = new ArrayList<>();
        for (Object id : repo.findMailableMembershipIds(orgId, p.namedSources, p.namedVersions,
                p.provenanceSources, p.textVersionSources, p.personSources, p.softOptInBases,
                p.cutoffAt, p.cutoffDate)) {
            out.add(uuid(id));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Breakdown breakdown(UUID orgId) {
        Map<String, Integer> exclusions = new LinkedHashMap<>();
        for (String reason : REASONS) exclusions.put(reason, 0);
        if (orgId == null) return new Breakdown(0, 0, Collections.unmodifiableMap(exclusions));
        Params p = params(orgId);
        int members = 0;
        int mailable = 0;
        for (Object[] row : repo.countExclusionsByReason(orgId, p.namedSources, p.namedVersions,
                p.provenanceSources, p.textVersionSources, p.personSources, p.softOptInBases,
                p.cutoffAt, p.cutoffDate)) {
            int n = ((Number) row[1]).intValue();
            members += n;
            if (row[0] == null) mailable += n;
            else exclusions.merge((String) row[0], n, Integer::sum);
        }
        return new Breakdown(members, mailable, Collections.unmodifiableMap(exclusions));
    }

    private record Params(List<String> namedSources, List<String> namedVersions, List<String> provenanceSources,
                          List<String> textVersionSources, List<String> personSources,
                          List<String> softOptInBases, Instant cutoffAt, LocalDate cutoffDate) {}

    private Params params(UUID orgId) {
        AudiencePlanLogic.Legal legal = logic.logic().legal();
        List<String> named = new ArrayList<>();
        List<String> provenance = new ArrayList<>();
        List<String> textVersion = new ArrayList<>();
        legal.explicitSources().forEach((source, req) -> {
            if (req == ProofRequirement.ORGANIZER_NAMED_TEXT_VERSION) named.add(source);
            else if (req == ProofRequirement.PROVENANCE_ROW) provenance.add(source);
            else if (req == ProofRequirement.TEXT_VERSION) textVersion.add(source);
        });
        // Sources the person uses themselves; an import is never contact from the person.
        List<String> person = new ArrayList<>(named);
        person.addAll(textVersion);

        ZoneId zone = orgZone(orgId);
        LocalDate cutoffDate = LocalDate.now(clock.withZone(zone)).minusDays(legal.retentionDays());
        Instant cutoffAt = cutoffDate.atStartOfDay(zone).toInstant();
        List<String> softBases = Boolean.TRUE.equals(props.getSoftOptInEnabled()) ? List.of(SOFT_OPT_IN) : List.of();
        return new Params(orNever(named), orNever(List.copyOf(legal.organizerNamedTextVersions())),
                orNever(provenance), orNever(textVersion), orNever(person), orNever(softBases),
                cutoffAt, cutoffDate);
    }

    private ZoneId orgZone(UUID orgId) {
        String tz = orgRepo.findById(orgId).map(Organization::getTimezone).orElse(null);
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static List<String> orNever(List<String> values) {
        return values.isEmpty() ? List.of(NEVER_MATCHES) : values;
    }

    /** Native scalar UUIDs arrive as UUID on Postgres and as 16 raw bytes on H2. */
    static UUID uuid(Object o) {
        if (o instanceof UUID u) return u;
        if (o instanceof byte[] b && b.length == 16) {
            ByteBuffer buf = ByteBuffer.wrap(b);
            return new UUID(buf.getLong(), buf.getLong());
        }
        return UUID.fromString(o.toString());
    }
}
