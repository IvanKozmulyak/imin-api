package com.imin.iminapi.dto.event;

/** The exact reminder the sweep would render for this event, with example tickets and no real order link. */
public record ReminderPreviewResponse(String subject, String fromName, String html, String text) {}
