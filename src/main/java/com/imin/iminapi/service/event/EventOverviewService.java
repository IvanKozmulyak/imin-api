package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventOverviewResponse;
import com.imin.iminapi.dto.event.EventOverviewResponse.Metrics;
import com.imin.iminapi.dto.event.EventOverviewResponse.QuickAction;
import com.imin.iminapi.dto.event.PredictionDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.PredictionRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EventOverviewService {

    private static final List<QuickAction> ACTIONS = List.of(
            new QuickAction("send_campaign", "✉️", "Send campaign to audience"),
            new QuickAction("comp_tickets", "🎟️", "Generate comp tickets"),
            new QuickAction("copy_link", "🔗", "Copy buyer link"),
            new QuickAction("qr_scanner", "📱", "Open QR scanner")
    );

    private final EventRepository events;
    private final PredictionRepository predictions;

    public EventOverviewService(EventRepository events, PredictionRepository predictions) {
        this.events = events;
        this.predictions = predictions;
    }

    @Transactional(readOnly = true)
    public EventOverviewResponse overview(AuthPrincipal p, UUID id) {
        Event e = events.findActive(id).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");

        int daysOut = e.getStartsAt() == null ? 0
                : (int) Duration.between(Instant.now(), e.getStartsAt()).toDays();
        Metrics m = new Metrics(
                e.getSold(), e.getRevenueMinor(), e.getCurrency(),
                /* squadRatePct V1 stub */ 0,
                Math.max(0, daysOut));
        var prediction = predictions.findById(id).map(PredictionDto::from).orElse(null);
        // recentPurchases is sourced from a yet-to-exist purchases table. V1: empty list.
        return new EventOverviewResponse(m, List.of(), prediction, ACTIONS);
    }
}
