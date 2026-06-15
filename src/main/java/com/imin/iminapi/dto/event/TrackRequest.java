package com.imin.iminapi.dto.event;

/**
 * Body of the public funnel beacon. {@code stage} must be one of the
 * client-instrumented stages (PAGE_VIEW | CHECKOUT_START); anything else is a
 * no-op. {@code anonId} is the buyer's per-session id from sessionStorage.
 */
public record TrackRequest(String stage, String anonId) {}
