package com.imin.iminapi.support;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Records every consume call; unlimited unless a test sets a per-key limit on a bucket. */
public final class RecordingRateLimiter implements RateLimiter {

    public record Call(String bucket, String key) {}

    private final List<Call> calls = new ArrayList<>();
    private final Map<String, Integer> limits = new HashMap<>();
    private final Map<Call, Integer> counts = new HashMap<>();

    @Override
    public synchronized void consume(String bucketName, String key) {
        Call call = new Call(bucketName, key);
        calls.add(call);
        int used = counts.merge(call, 1, Integer::sum);
        Integer limit = limits.get(bucketName);
        if (limit != null && used > limit) throw ApiException.rateLimited();
    }

    /** Call {@code n + 1} for any one key of {@code bucket} is rejected with 429. */
    public synchronized void limit(String bucket, int n) {
        limits.put(bucket, n);
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    public synchronized void reset() {
        calls.clear();
        limits.clear();
        counts.clear();
    }
}
