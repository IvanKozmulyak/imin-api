package com.imin.iminapi.predictor.sources.openevents;

/** Upstream answered 429: the job skips that source for the rest of the run. */
public class OpenEventsRateLimitedException extends RuntimeException {
    public OpenEventsRateLimitedException(String message) {
        super(message);
    }
}
