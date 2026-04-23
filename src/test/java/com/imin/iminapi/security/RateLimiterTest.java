package com.imin.iminapi.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    @Test
    void consumes_until_bucket_is_empty_then_throws() {
        Map<String, Bucket> store = new HashMap<>();
        RateLimiter limiter = (bucket, key) -> {
            Bucket b = store.computeIfAbsent(bucket + ":" + key, k ->
                    Bucket.builder().addLimit(Bandwidth.simple(2, Duration.ofMinutes(15))).build());
            if (!b.tryConsume(1)) throw ApiException.rateLimited();
        };

        limiter.consume("login", "alice@example.com");
        limiter.consume("login", "alice@example.com");
        assertThatThrownBy(() -> limiter.consume("login", "alice@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many requests");

        // Different key still has budget
        limiter.consume("login", "bob@example.com");
    }
}
