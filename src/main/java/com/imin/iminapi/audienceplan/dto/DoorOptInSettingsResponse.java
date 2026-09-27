package com.imin.iminapi.audienceplan.dto;

/**
 * Organizer view of the door QR: {@code doorUrl} is null until it is first switched on; {@code signups} counts
 * distinct people who ticked the box at this event's door.
 */
public record DoorOptInSettingsResponse(boolean enabled, String doorUrl, long signups) {}
