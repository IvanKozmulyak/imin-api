package com.imin.iminapi.audienceplan.dto;

/**
 * Body of {@code POST /api/v1/public/orgs/{slug}/follow}. {@code consentGiven} mirrors the unticked switch;
 * {@code consentText} is the sentence shown next to it, stored verbatim; validation is in {@code FollowOptInService}.
 */
public record FollowOptInRequest(String email, Boolean consentGiven, String consentText, String consentTextVersion,
                                 String locale) {}
