package com.imin.iminapi.audience.service;

/** Published when {@link AudienceBackfillJob#run()} finishes a pass. */
public record AudienceBackfillCompleted(int processed, int skippedErased) {}
