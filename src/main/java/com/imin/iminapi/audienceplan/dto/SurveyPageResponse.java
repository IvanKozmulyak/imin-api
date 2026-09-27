package com.imin.iminapi.audienceplan.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * What the survey page shows; {@code organizerName} is the name the consent sentence must contain. The legal
 * name and contact are the organizer's identity for the notice, null when the organizer has not set them.
 */
public record SurveyPageResponse(UUID eventId, String eventName, String organizerName, String organizerLegalName,
                                 String organizerLegalContact, Instant startsAt, String timezone, String venueCity) {}
