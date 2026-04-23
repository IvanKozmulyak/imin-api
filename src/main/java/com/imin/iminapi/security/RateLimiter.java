package com.imin.iminapi.security;

public interface RateLimiter {
    /**
     * Decrement one token from the bucket identified by (bucketName, key).
     * Throws {@link ApiException} with 429 / RATE_LIMITED if the bucket is empty.
     */
    void consume(String bucketName, String key);
}
