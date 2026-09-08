package com.imin.iminapi.model;

/**
 * Lifecycle of one poster generation. RAW_READY and COMPOSITING were declared but never assigned —
 * PosterOrchestrator only ever writes PENDING then COMPLETE/FAILED — so no row has ever held them.
 * (The same-named PosterVariantStatus.RAW_READY is a different enum and IS used.)
 */
public enum PosterGenerationStatus {
    PENDING, COMPLETE, FAILED
}
