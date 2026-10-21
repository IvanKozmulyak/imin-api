package com.imin.iminapi.dto.event;

/** Body of {@code PUT /api/v1/events/{eventId}/reminders}; an omitted field stays as it is, an empty doorNote clears it. */
public record EventRemindersRequest(Boolean enabled, Boolean shareLinkEnabled, String doorNote) {}
