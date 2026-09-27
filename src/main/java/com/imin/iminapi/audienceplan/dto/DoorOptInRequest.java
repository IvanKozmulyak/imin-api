package com.imin.iminapi.audienceplan.dto;

/**
 * Body of {@code POST /api/v1/public/events/{eventId}/door-optin}. {@code consentGiven} mirrors the unticked box;
 * {@code consentText} is the sentence shown next to it, stored verbatim; validation is in {@code DoorOptInService}.
 */
public record DoorOptInRequest(String token, String email, Boolean consentGiven, String consentText,
                               String consentTextVersion, String locale) {}
