package com.imin.iminapi.audienceplan.dto;

import java.time.Instant;
import java.util.UUID;

/** What the door page shows; {@code organizerName} is the name the consent sentence must contain. */
public record DoorOptInPageResponse(UUID eventId, String eventName, String organizerName, Instant startsAt,
                                    String timezone, String venueCity) {}
