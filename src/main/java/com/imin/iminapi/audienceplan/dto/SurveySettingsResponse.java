package com.imin.iminapi.audienceplan.dto;

/**
 * Organizer view of the survey: {@code surveyUrl} is null until it is first switched on; {@code responses}
 * counts every answer stored for this event.
 */
public record SurveySettingsResponse(boolean enabled, String surveyUrl, long responses) {}
