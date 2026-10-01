package com.imin.iminapi.predictor.dto;

import java.util.List;

/**
 * An event's Radar state: {@code radarOn} is the global Radar switch, {@code muted} the per-event alert mute, and
 * {@code runs} the event's radar re-checks, newest first.
 */
public record RadarTimelineResponse(boolean radarOn, boolean muted, List<RadarRunDto> runs) {
    public RadarTimelineResponse {
        runs = List.copyOf(runs);
    }
}
