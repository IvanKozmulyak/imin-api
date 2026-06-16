package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.TrackRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Records public funnel beacons (PAGE_VIEW, CHECKOUT_START). All failure modes
 * are silent no-ops so the public endpoint can always answer 204 with no
 * information leak: unknown/non-public event, unknown stage, blank session id.
 */
@Service
public class FunnelTrackingService {

    private static final Set<String> ALLOWED_STAGES =
            Set.of(FunnelEvent.STAGE_PAGE_VIEW, FunnelEvent.STAGE_CHECKOUT_START);

    private final EventRepository events;
    private final FunnelEventRepository funnel;

    public FunnelTrackingService(EventRepository events, FunnelEventRepository funnel) {
        this.events = events;
        this.funnel = funnel;
    }

    @Transactional
    public void track(UUID eventId, TrackRequest req) {
        if (req == null || req.stage() == null || !ALLOWED_STAGES.contains(req.stage())) return;
        if (req.anonId() == null || req.anonId().isBlank()) return;

        Event e = events.findActive(eventId).orElse(null);
        if (e == null || e.getVisibility() != EventVisibility.PUBLIC) return;

        FunnelEvent row = new FunnelEvent();
        row.setEventId(eventId);
        row.setStage(req.stage());
        String anon = req.anonId().trim();
        row.setAnonId(anon.substring(0, Math.min(64, anon.length())));
        // UTM attribution tags (V43) — all optional, best-effort, capped to column width.
        row.setUtmSource(cap(req.utmSource(), 128));
        row.setUtmMedium(cap(req.utmMedium(), 128));
        row.setUtmCampaign(cap(req.utmCampaign(), 128));
        row.setUtmContent(cap(req.utmContent(), 128));
        row.setReferrerHost(cap(req.referrerHost(), 255));
        funnel.save(row);
    }

    /** Trim, null-out blanks, and cap to the column width. */
    private static String cap(String v, int max) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
