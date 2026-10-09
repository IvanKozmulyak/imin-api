package com.imin.iminapi.refund.dto;

import java.util.List;
import java.util.UUID;

/** Pending refund requests of an org: the total and one entry per event, newest pending first. */
public record RefundRequestPendingCountResponse(long total, List<EventCount> events) {

    public record EventCount(UUID eventId, String eventName, long count) {}
}
