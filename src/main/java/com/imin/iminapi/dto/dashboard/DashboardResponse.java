package com.imin.iminapi.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.dto.event.PredictionDto;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardResponse(
        Greeting greeting,
        Now now,
        Cycle cycle,
        LastEvent lastEvent,
        PredictionDto prediction,
        Business business,
        List<Activity> activity) {

    public record Greeting(String name) {}

    public record Now(EventDto nextEvent, int pct, int daysOut) {}

    public record Cycle(String period, long revenueMinor, int ticketsSold, int squadRatePct,
                        int activeEvents, Deltas deltas) {}

    public record Deltas(int revenuePct, int ticketsPct) {}

    public record LastEvent(EventDto event, LastEventMetrics metrics) {}

    public record LastEventMetrics(int attended, int capacity, int avgTicketMinor, Integer nps) {}

    public record Business(long totalRevenueMinor, long eventsPublished, long eventsCompleted,
                           int audienceCount, int repeatRatePct) {}

    public record Activity(String time, String label) {}
}
