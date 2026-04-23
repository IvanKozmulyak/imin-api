package com.imin.iminapi.config;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@TestConfiguration
public class TestRateLimitConfig {

    @Bean @Primary
    public RateLimiter testRateLimiter() {
        Map<String, Bucket> buckets = new ConcurrentHashMap<>();
        return (bucketName, key) -> {
            Bucket b = buckets.computeIfAbsent(bucketName + ":" + key, k ->
                    Bucket.builder().addLimit(Bandwidth.simple(1000, Duration.ofMinutes(1))).build());
            if (!b.tryConsume(1)) throw ApiException.rateLimited();
        };
    }
}
