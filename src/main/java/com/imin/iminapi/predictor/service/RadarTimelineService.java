package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.config.DateCheckAccess;
import com.imin.iminapi.predictor.config.DateCheckProperties;
import com.imin.iminapi.predictor.dto.RadarMuteRequest;
import com.imin.iminapi.predictor.dto.RadarRunDto;
import com.imin.iminapi.predictor.dto.RadarTimelineResponse;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An event's Radar runs and its per-event alert mute. Gate first, then the org's event, then the body: a closed
 * gate, an unknown, deleted or foreign event are the same 404. Any org member may read and mute.
 */
@Service
public class RadarTimelineService {

    /** ponytail: 4 milestones per night; only repeated date moves add more, so 20 covers it. */
    static final int MAX_RUNS = 20;

    private final DateCheckAccess access;
    private final DateCheckProperties props;
    private final EventRepository events;
    private final DateCheckRepository checks;
    private final PredictorAlertStore alerts;

    public RadarTimelineService(DateCheckAccess access, DateCheckProperties props, EventRepository events,
                                DateCheckRepository checks, PredictorAlertStore alerts) {
        this.access = access;
        this.props = props;
        this.events = events;
        this.checks = checks;
        this.alerts = alerts;
    }

    @Transactional(readOnly = true)
    public RadarTimelineResponse timeline(AuthPrincipal p, UUID eventId) {
        access.requireEnabled(p.orgId());
        return build(owned(p, eventId));
    }

    /** Not transactional itself: {@code updateRadarMuted} carries its own transaction and clears the context. */
    public RadarTimelineResponse mute(AuthPrincipal p, UUID eventId, RadarMuteRequest req) {
        access.requireEnabled(p.orgId());
        Event e = owned(p, eventId);
        if (req == null || req.muted() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "muted is required",
                    Map.of("muted", "required"));
        }
        events.updateRadarMuted(e.getId(), req.muted());
        return timeline(p, eventId);
    }

    private Event owned(AuthPrincipal p, UUID id) {
        Event e = events.findActive(id).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    private RadarTimelineResponse build(Event e) {
        Set<UUID> alerted = alerts.radarAlertedCheckIds(e.getId());
        List<RadarRunDto> runs = checks.findRadarRuns(e.getOrgId(), e.getId(), PageRequest.of(0, MAX_RUNS)).stream()
                .map(c -> new RadarRunDto(c.getId(), c.getRadarMilestone(), c.getRadarNight(), c.getCreatedAt(),
                        c.getRadarPrevVerdict(), c.getRadarVerdict(), toInt(c.getRadarPrevRisk()),
                        toInt(c.getRadarRisk()),
                        alerted.contains(c.getId()) ? RadarRunDto.ALERT_SENT : RadarRunDto.ALERT_NONE))
                .toList();
        // Same switch RadarJob.pass checks; enabled is already required by the gate.
        return new RadarTimelineResponse(Boolean.TRUE.equals(props.getRadarEnabled()), e.isRadarMuted(), runs);
    }

    private static Integer toInt(Short s) {
        return s == null ? null : s.intValue();
    }
}
