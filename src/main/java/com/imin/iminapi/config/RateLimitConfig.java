package com.imin.iminapi.config;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Profile("!test")
@Configuration
public class RateLimitConfig {

    @Value("${imin.ratelimit.login.capacity}")
    private int loginCapacity;
    @Value("${imin.ratelimit.login.window-minutes}")
    private int loginWindow;
    @Value("${imin.ratelimit.ai-concept.capacity}")
    private int aiCapacity;
    @Value("${imin.ratelimit.ai-concept.window-minutes}")
    private int aiWindow;
    @Value("${imin.ratelimit.verification-resend.capacity}")
    private int verificationResendCapacity;
    @Value("${imin.ratelimit.verification-resend.window-minutes}")
    private int verificationResendWindow;
    @Value("${imin.ratelimit.password-reset.capacity}")
    private int passwordResetCapacity;
    @Value("${imin.ratelimit.password-reset.window-minutes}")
    private int passwordResetWindow;
    @Value("${imin.ratelimit.checkout.capacity}")
    private int checkoutCapacity;
    @Value("${imin.ratelimit.checkout.window-minutes}")
    private int checkoutWindow;
    @Value("${imin.ratelimit.predictor-rescore.capacity}")
    private int predictorRescoreCapacity;
    @Value("${imin.ratelimit.predictor-rescore.window-minutes}")
    private int predictorRescoreWindow;
    @Value("${imin.ratelimit.audience-import.capacity}")
    private int audienceImportCapacity;
    @Value("${imin.ratelimit.audience-import.window-minutes}")
    private int audienceImportWindow;
    @Value("${imin.ratelimit.notify-subscribe.capacity}")
    private int notifySubscribeCapacity;
    @Value("${imin.ratelimit.notify-subscribe.window-minutes}")
    private int notifySubscribeWindow;

    @Bean
    public RedisClient redisClient(@Value("${spring.data.redis.url}") String url) {
        return RedisClient.create(url);
    }

    @Bean
    public StatefulRedisConnection<String, byte[]> redisConnection(RedisClient client) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        return client.connect(codec);
    }

    @Bean
    public ProxyManager<String> bucketProxyManager(StatefulRedisConnection<String, byte[]> conn) {
        return LettuceBasedProxyManager.builderFor(conn).build();
    }

    @Bean
    public RateLimiter rateLimiter(ProxyManager<String> proxy) {
        Map<String, BucketConfiguration> configs = new ConcurrentHashMap<>();
        configs.put("login", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(loginCapacity, Duration.ofMinutes(loginWindow)))
                .build());
        configs.put("ai-concept", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(aiCapacity, Duration.ofMinutes(aiWindow)))
                .build());
        configs.put("verification-resend", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(verificationResendCapacity, Duration.ofMinutes(verificationResendWindow)))
                .build());
        configs.put("password-reset", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(passwordResetCapacity, Duration.ofMinutes(passwordResetWindow)))
                .build());
        configs.put("checkout", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(checkoutCapacity, Duration.ofMinutes(checkoutWindow)))
                .build());
        // Predictor manual re-score throttle (spec §4.1), keyed per user id.
        configs.put("predictor-rescore", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(predictorRescoreCapacity, Duration.ofMinutes(predictorRescoreWindow)))
                .build());
        // Audience CSV import throttle, keyed per org — imports are heavy + consent-sensitive.
        configs.put("audience-import", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(audienceImportCapacity, Duration.ofMinutes(audienceImportWindow)))
                .build());
        // Public unauthenticated notify-me subscribe, keyed per client IP. Every stored row
        // becomes a real outbound email once the event releases tickets, so an unthrottled
        // route is a spam relay.
        configs.put("notify-subscribe", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(notifySubscribeCapacity, Duration.ofMinutes(notifySubscribeWindow)))
                .build());

        return (bucketName, key) -> {
            BucketConfiguration cfg = configs.get(bucketName);
            if (cfg == null) throw new IllegalArgumentException("Unknown bucket " + bucketName);
            String redisKey = "ratelimit:" + bucketName + ":" + key;
            Bucket bucket = proxy.builder().build(redisKey, () -> cfg);
            if (!bucket.tryConsume(1)) throw ApiException.rateLimited();
        };
    }
}
