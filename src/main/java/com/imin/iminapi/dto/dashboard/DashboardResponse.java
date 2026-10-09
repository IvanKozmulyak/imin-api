package com.imin.iminapi.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.dto.event.PredictionDto;
import io.swagger.v3.oas.annotations.media.Schema;

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

    public record Now(EventDto nextEvent, int pct, int daysOut, int ticketsTotal) {}

    public record Cycle(String period,
                        @Schema(description = "Orders placed in the window, test mode included: totals less their"
                                + " succeeded refunds, the unrefunded booking fee and the organizer share of open"
                                + " or lost chargebacks (what the organizer still holds from each disputed order,"
                                + " booking fee excluded)."
                                + " Minor units, never negative.")
                        long revenueMinor,
                        @Schema(description = "Tickets on orders placed in the window that are not refunded or"
                                + " revoked, test mode included.")
                        int ticketsSold,
                        int activeEvents, Deltas deltas) {}

    public record Deltas(
            @Schema(types = {"integer", "null"}, format = "int32",
                    description = "Percent change of cycle.revenueMinor against the equal-length window before it,"
                            + " rounded. Null when the prior window is empty or the period is all.")
            Integer revenuePct,
            @Schema(types = {"integer", "null"}, format = "int32",
                    description = "Percent change of cycle.ticketsSold against the equal-length window before it,"
                            + " rounded. Null when the prior window is empty or the period is all.")
            Integer ticketsPct) {}

    public record LastEvent(EventDto event, LastEventMetrics metrics) {}

    public record LastEventMetrics(
            @Schema(description = "Tickets sold net of chargebacks, all modes. Not door scans.")
            int attended,
            int capacity,
            @Schema(types = {"integer", "null"}, format = "int32",
                    description = "The last event's net (as cycle.revenueMinor) divided by its tickets not refunded"
                            + " or revoked, rounded half up. Null when there are none.")
            Integer avgTicketMinor,
            Integer nps) {}

    public record Business(
            @Schema(description = "Same definition as cycle.revenueMinor, over the business period.")
            long totalRevenueMinor,
            long eventsPublished, long eventsCompleted,
            @Schema(description = "People in the org's audience, all time; the Audience page total. Not windowed.")
            long audienceCount) {}

    public record Activity(String time, String label) {}
}
