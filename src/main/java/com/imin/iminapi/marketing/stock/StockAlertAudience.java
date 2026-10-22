package com.imin.iminapi.marketing.stock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.audienceplan.service.SendPathGuard;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.service.CampaignVolumeGuard;
import com.imin.iminapi.marketing.service.MarketingGuardProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who a stock alert can reach now, by the send's own rules: ConsentGate-mailable minus ticket holders, then the Send
 * Gate, the stock-alert send-path skips and the frequency floor; serves the card, the evaluator and the snapshot.
 */
@Component
public class StockAlertAudience {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_NAME = 128;

    /** The dry run: {@code canReceive} plus the left-out counts add up to {@code mailable} (ConsentGate-mailable). */
    public record Reach(int mailable, int canReceive, int haveTicket, int alreadyAlerted, int recentlyEmailed,
                        int eventCap, int monthlyCap, int noConsent, int experimentHoldout, int frequencyFloorHours,
                        List<UUID> members) {
        public Reach {
            members = List.copyOf(members);
        }
    }

    private final ConsentGate consentGate;
    private final FanFeatureRepository fanFeatures;
    private final SendGateService sendGate;
    private final SendPathGuard sendPathGuard;
    private final CampaignVolumeGuard volumeGuard;
    private final MarketingGuardProperties guardProps;
    private final SegmentRepository segments;

    public StockAlertAudience(ConsentGate consentGate, FanFeatureRepository fanFeatures, SendGateService sendGate,
                              SendPathGuard sendPathGuard, CampaignVolumeGuard volumeGuard,
                              MarketingGuardProperties guardProps, SegmentRepository segments) {
        this.consentGate = consentGate;
        this.fanFeatures = fanFeatures;
        this.sendGate = sendGate;
        this.sendPathGuard = sendPathGuard;
        this.volumeGuard = volumeGuard;
        this.guardProps = guardProps;
        this.segments = segments;
    }

    @Transactional(readOnly = true)
    public Reach dryRun(UUID orgId, UUID eventId, Instant now) {
        List<UUID> mailable = consentGate.mailableMembershipIds(orgId);
        Set<UUID> holders = new HashSet<>();
        for (Object id : fanFeatures.findMembershipIdsHoldingEventTicket(orgId, eventId)) holders.add(uuid(id));
        List<UUID> rest = new ArrayList<>(mailable.size());
        int haveTicket = 0;
        for (UUID id : mailable) {
            if (holders.contains(id)) haveTicket++;
            else rest.add(id);
        }
        SendGateService.GateResult gate = sendGate.evaluate(orgId, rest);
        int noConsent = rest.size() - gate.sendable().size();
        Map<UUID, String> skipped = sendPathGuard.skipReasons(syntheticAlert(orgId, eventId), gate.sendable(), now);
        List<UUID> unguarded = gate.sendable().stream().filter(id -> !skipped.containsKey(id)).toList();
        Set<UUID> floor = volumeGuard.frequencyCapped(unguarded, now);
        List<UUID> members = unguarded.stream().filter(id -> !floor.contains(id)).toList();
        return new Reach(mailable.size(), members.size(),
                haveTicket + count(skipped, SendPathGuard.HAS_TICKET),
                count(skipped, SendPathGuard.STOCK_ALERT_CAP), floor.size(),
                count(skipped, SendPathGuard.EVENT_CAP), count(skipped, SendPathGuard.MONTHLY_CAP),
                noConsent + count(skipped, SendPathGuard.CONSENT_GATE),
                count(skipped, SendPathGuard.EXPERIMENT_HOLDOUT),
                guardProps.getFrequencyFloorHours(), members);
    }

    /** Freezes {@code members} into a hidden static segment the draft sends to; the send re-applies every rule. */
    public UUID snapshot(UUID orgId, String name, List<UUID> members) {
        Segment s = new Segment();
        s.setOrgId(orgId);
        String clamped = name == null ? "Stock alert" : name;
        s.setName(clamped.length() <= MAX_NAME ? clamped : clamped.substring(0, MAX_NAME));
        s.setKind("static");
        s.setOrigin(Segment.ORIGIN_STOCK_ALERT);
        try {
            s.setSnapshotIds(JSON.writeValueAsString(members.stream().map(UUID::toString).sorted().toList()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize stock alert audience", e);
        }
        Instant at = Instant.now();
        s.setCreatedAt(at);
        s.setUpdatedAt(at);
        return segments.save(s).getId();
    }

    /** An unsaved campaign carrying what SendPathGuard reads: org, event and the stock-alert origin. */
    static Campaign syntheticAlert(UUID orgId, UUID eventId) {
        Campaign c = new Campaign();
        c.setOrgId(orgId);
        c.setEventId(eventId);
        c.setChannel("email");
        c.setOrigin(SendPathGuard.STOCK_ALERT_ORIGIN);
        c.setSkipTicketHolders(true);
        return c;
    }

    private static UUID uuid(Object o) {
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }

    private static int count(Map<UUID, String> skipped, String reason) {
        return (int) skipped.values().stream().filter(reason::equals).count();
    }
}
