package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Send-time skips on top of SendGateService: for a campaign about an event, its holdout members and the
 * per-event and 30-day send caps; for an audience-plan campaign, a Momentum campaign sending to a plan
 * snapshot, or every campaign while {@code consent-gate-all-campaigns} is on, ConsentGate as well.
 */
@Service
public class SendPathGuard {

    public static final String EXPERIMENT_HOLDOUT = "experiment_holdout";
    public static final String EVENT_CAP = "event_cap";
    public static final String MONTHLY_CAP = "monthly_cap";
    public static final String CONSENT_GATE = "consent_gate";
    static final String MOMENTUM_ORIGIN = "momentum";

    /** Emails about one event at or above which a person gets no more about it. */
    public static final int EVENT_CAP_SENDS = 2;
    /** Emails in the last {@link #MONTHLY_CAP_DAYS} days at or above which a person gets no event email. */
    public static final int MONTHLY_CAP_SENDS = 4;
    public static final int MONTHLY_CAP_DAYS = 30;

    // Keeps each IN list far below Postgres's bind-parameter limit.
    static final int MAX_IDS_PER_QUERY = 1000;

    private final AudienceAssignmentRepository assignments;
    private final CampaignRecipientRepository recipients;
    private final ConsentGate consentGate;
    private final AudiencePlanProperties props;
    private final SegmentRepository segments;

    public SendPathGuard(AudienceAssignmentRepository assignments, CampaignRecipientRepository recipients,
                         ConsentGate consentGate, AudiencePlanProperties props, SegmentRepository segments) {
        this.assignments = assignments;
        this.recipients = recipients;
        this.consentGate = consentGate;
        this.props = props;
        this.segments = segments;
    }

    /** Skip reason per member that must not be emailed by this campaign now; members absent may be sent. */
    public Map<UUID, String> skipReasons(Campaign c, Collection<UUID> membershipIds, Instant now) {
        boolean aboutEvent = c.getEventId() != null;
        boolean consentGated = AudiencePlanAccess.CAMPAIGN_ORIGIN.equals(c.getOrigin())
                || Boolean.TRUE.equals(props.getConsentGateAllCampaigns())
                || sendsToPlanSnapshot(c);
        if ((!aboutEvent && !consentGated) || membershipIds == null) return Map.of();
        List<UUID> ids = membershipIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();

        Set<UUID> heldOut = new HashSet<>();
        Map<UUID, Long> eventSends = new HashMap<>();
        Map<UUID, Long> recentSends = new HashMap<>();
        if (aboutEvent) {
            Instant monthSince = now.minus(MONTHLY_CAP_DAYS, ChronoUnit.DAYS);
            for (int from = 0; from < ids.size(); from += MAX_IDS_PER_QUERY) {
                List<UUID> chunk = ids.subList(from, Math.min(from + MAX_IDS_PER_QUERY, ids.size()));
                heldOut.addAll(assignments.findHeldOut(c.getOrgId(), c.getEventId(), chunk));
                collect(recipients.countEventSendsByMembership(c.getOrgId(), c.getEventId(), chunk), eventSends);
                collect(recipients.countRecentSendsByMembership(chunk, monthSince), recentSends);
            }
        }
        Map<UUID, Optional<String>> gate = consentGated ? consentGate.reasons(c.getOrgId(), ids) : Map.of();

        Map<UUID, String> out = new HashMap<>();
        for (UUID id : ids) {
            String reason = null;
            if (heldOut.contains(id)) reason = EXPERIMENT_HOLDOUT;
            else if (eventSends.getOrDefault(id, 0L) >= EVENT_CAP_SENDS) reason = EVENT_CAP;
            else if (recentSends.getOrDefault(id, 0L) >= MONTHLY_CAP_SENDS) reason = MONTHLY_CAP;
            // A member ConsentGate did not return (another org, or gone) is not mailable either.
            else if (consentGated && gate.getOrDefault(id, Optional.of(CONSENT_GATE)).isPresent()) reason = CONSENT_GATE;
            if (reason != null) out.put(id, reason);
        }
        return out;
    }

    // Plan-derived members were consent-checked at snapshot time only; objections since then must still skip.
    private boolean sendsToPlanSnapshot(Campaign c) {
        if (!MOMENTUM_ORIGIN.equals(c.getOrigin()) || c.getSegmentId() == null) return false;
        return segments.findByIdAndOrgId(c.getSegmentId(), c.getOrgId())
                .map(s -> Segment.ORIGIN_MOMENTUM.equals(s.getOrigin()))
                .orElse(false);
    }

    private static void collect(List<Object[]> rows, Map<UUID, Long> into) {
        for (Object[] row : rows) into.merge((UUID) row[0], ((Number) row[1]).longValue(), Long::sum);
    }
}
