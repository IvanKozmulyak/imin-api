package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.dto.AudienceMemberClass;
import com.imin.iminapi.audience.dto.RateRange;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read side of fan_features and ConsentGate for the Audience tab: org metrics and per-member fields. */
@Service
public class AudienceReadModel {

    private static final Logger log = LoggerFactory.getLogger(AudienceReadModel.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Double>> TASTE = new TypeReference<>() {};
    static final Duration NEW_WINDOW = Duration.ofDays(30);
    static final Duration SENDS_WINDOW = Duration.ofDays(30);
    private static final int IN_CHUNK = 1000;
    // ponytail: taste is TEXT JSON, not jsonb, so it is averaged in Java; paging caps memory, not the full scan.
    static final int TASTE_PAGE = 1000;

    /** The fan-feature part of the metrics. */
    public record Metrics(long newLast30Days, RateRange showedUpPct, RateRange cameBackPct, int mailable,
                          int legacyNotMailable, Map<String, Integer> mailableByBasis,
                          Map<String, Integer> exclusions, Map<String, Long> classCounts,
                          Map<String, Double> tasteShares, long tasteMembers) {}

    /** Per-member fields; class and taste are null when the member has no feature row yet. */
    public record MemberFields(AudienceMemberClass guestClass, Map<String, Double> taste, int sends30d) {}

    private final FanFeatureRepository features;
    private final MembershipRepository memberships;
    private final TicketRepository tickets;
    private final CampaignRecipientRepository recipients;
    private final ConsentGate consentGate;
    private final AudiencePlanLogic logic;
    private final Clock clock;

    public AudienceReadModel(FanFeatureRepository features, MembershipRepository memberships,
                             TicketRepository tickets, CampaignRecipientRepository recipients,
                             ConsentGate consentGate, AudiencePlanLogic logic, Clock clock) {
        this.features = features;
        this.memberships = memberships;
        this.tickets = tickets;
        this.recipients = recipients;
        this.consentGate = consentGate;
        this.logic = logic;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Metrics metrics(UUID orgId) {
        Instant now = clock.instant();
        long newLast30Days = memberships.countCreatedSince(orgId, now.minus(NEW_WINDOW));

        Object[] showUp = first(tickets.countShowUpForEndedPaidEvents(orgId, now));
        RateRange showedUp = RateRange.of(number(showUp, 1), number(showUp, 0));

        long buyers = features.countWithPaidOrdersAtLeast(orgId, 1);
        long cameBack = features.countWithPaidOrdersAtLeast(orgId, 2);

        // One gate query: mailable, exclusions and the basis split come from the same verdict rows.
        ConsentGate.Breakdown gate = consentGate.breakdown(orgId);

        Map<String, Long> classCounts = new LinkedHashMap<>();
        for (AudienceMemberClass c : AudienceMemberClass.values()) classCounts.put(c.key(), 0L);
        for (Object[] row : features.countByClass(orgId)) {
            String key = AudienceMemberClass.parse((String) row[0]).orElse(AudienceMemberClass.NONE).key();
            classCounts.merge(key, ((Number) row[1]).longValue(), Long::sum);
        }

        TasteSums taste = new TasteSums(logic.genres().whitelist());
        List<Object[]> page = features.findTastePage(orgId, Limit.of(TASTE_PAGE));
        while (!page.isEmpty()) {
            for (Object[] row : page) taste.add(parseTaste((String) row[1]));
            if (page.size() < TASTE_PAGE) break;
            page = features.findTastePageAfter(orgId, (UUID) page.get(page.size() - 1)[0], Limit.of(TASTE_PAGE));
        }

        return new Metrics(newLast30Days, showedUp, RateRange.of(cameBack, buyers), gate.mailable(),
                gate.legacyNotMailable(), gate.mailableByBasis(), gate.exclusions(),
                Collections.unmodifiableMap(classCounts), taste.shares(), taste.members());
    }

    /** Fields for the given members of the org; every id gets an entry. */
    @Transactional(readOnly = true)
    public Map<UUID, MemberFields> memberFields(UUID orgId, Collection<UUID> membershipIds) {
        if (membershipIds.isEmpty()) return Map.of();
        List<UUID> ids = membershipIds.stream().distinct().toList();
        Map<UUID, FanFeature> byId = new HashMap<>();
        Map<UUID, Integer> sends = new HashMap<>();
        Instant since = clock.instant().minus(SENDS_WINDOW);
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            List<UUID> chunk = ids.subList(i, Math.min(ids.size(), i + IN_CHUNK));
            for (FanFeature f : features.findByMembershipIdIn(chunk)) {
                if (orgId.equals(f.getOrgId())) byId.put(f.getMembershipId(), f);
            }
            for (Object[] row : recipients.countRecentSendsByMembership(orgId, chunk, since)) {
                sends.put((UUID) row[0], ((Number) row[1]).intValue());
            }
        }
        Map<UUID, MemberFields> out = new HashMap<>();
        for (UUID id : ids) {
            FanFeature f = byId.get(id);
            AudienceMemberClass cls = f == null ? null
                    : AudienceMemberClass.parse(f.getFanClass()).orElse(AudienceMemberClass.NONE);
            Map<String, Double> taste = f == null ? null : parseTaste(f.getTaste());
            out.put(id, new MemberFields(cls, taste, sends.getOrDefault(id, 0)));
        }
        return out;
    }

    /** Mean of the normalized taste vectors, every whitelisted bucket present; null for no vectors. */
    static Map<String, Double> tasteShares(List<Map<String, Double>> tastes, List<String> whitelist) {
        TasteSums sums = new TasteSums(whitelist);
        tastes.forEach(sums::add);
        return sums.shares();
    }

    /** Running per-bucket sums, so the org's tastes are folded page by page instead of held in memory. */
    static final class TasteSums {
        private final Map<String, Double> sums = new LinkedHashMap<>();
        private double total;
        private long members;

        TasteSums(List<String> whitelist) {
            for (String bucket : whitelist) sums.put(bucket, 0d);
        }

        void add(Map<String, Double> t) {
            if (t == null || t.isEmpty()) return;
            members++;
            for (Map.Entry<String, Double> e : t.entrySet()) {
                if (!sums.containsKey(e.getKey()) || e.getValue() == null || e.getValue() <= 0) continue;
                sums.merge(e.getKey(), e.getValue(), Double::sum);
                total += e.getValue();
            }
        }

        long members() { return members; }

        Map<String, Double> shares() {
            if (members == 0 || total <= 0) return null;
            Map<String, Double> out = new LinkedHashMap<>(sums);
            double t = total;
            out.replaceAll((k, v) -> v / t);
            return Collections.unmodifiableMap(out);
        }
    }

    /** Null for a missing or unreadable value; a stored "{}" is an empty taste, not an unknown one. */
    static Map<String, Double> parseTaste(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Double> t = JSON.readValue(json, TASTE);
            return t == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(t));
        } catch (Exception e) {
            log.warn("AudienceReadModel: unreadable taste JSON ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    private static Object[] first(List<Object[]> rows) {
        return rows.isEmpty() ? new Object[] {0L, 0L} : rows.get(0);
    }

    private static long number(Object[] row, int i) {
        return row.length > i && row[i] instanceof Number n ? n.longValue() : 0L;
    }
}
