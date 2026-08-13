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
    @Value("${imin.ratelimit.buyer-login.capacity}")
    private int buyerLoginCapacity;
    @Value("${imin.ratelimit.buyer-login.window-minutes}")
    private int buyerLoginWindow;
    @Value("${imin.ratelimit.buyer-signup.capacity}")
    private int buyerSignupCapacity;
    @Value("${imin.ratelimit.buyer-signup.window-minutes}")
    private int buyerSignupWindow;
    @Value("${imin.ratelimit.buyer-password-reset.capacity}")
    private int buyerPasswordResetCapacity;
    @Value("${imin.ratelimit.buyer-password-reset.window-minutes}")
    private int buyerPasswordResetWindow;
    @Value("${imin.ratelimit.buyer-verification-resend.capacity}")
    private int buyerVerificationResendCapacity;
    @Value("${imin.ratelimit.buyer-verification-resend.window-minutes}")
    private int buyerVerificationResendWindow;
    @Value("${imin.ratelimit.buyer-email-add.capacity}")
    private int buyerEmailAddCapacity;
    @Value("${imin.ratelimit.buyer-email-add.window-minutes}")
    private int buyerEmailAddWindow;
    @Value("${imin.ratelimit.buyer-order-resend.capacity}")
    private int buyerOrderResendCapacity;
    @Value("${imin.ratelimit.buyer-order-resend.window-minutes}")
    private int buyerOrderResendWindow;

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

        // ---- Buyer accounts (buyer-accounts epic §2.2) --------------------
        // Five buckets, deliberately separate from the organizer ones even
        // where the numbers match: the two surfaces have different abuse
        // profiles and must be tunable apart, and sharing a bucket would let
        // buyer traffic throttle organizer sign-in.
        //
        // NOTE none of these is the whole story for verification: this class is
        // @Profile("!test"), so the code-guessing limit that actually has to
        // hold is the DB-counted lockout in BuyerEmailVerificationService.

        // Password sign-in, keyed per normalized email.
        configs.put("buyer-login", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(buyerLoginCapacity, Duration.ofMinutes(buyerLoginWindow)))
                .build());
        // Account creation, keyed per client IP — keying it per email would let
        // an attacker burn a stranger's bucket and block them from registering.
        configs.put("buyer-signup", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(buyerSignupCapacity, Duration.ofMinutes(buyerSignupWindow)))
                .build());
        // Forgot-password, keyed per normalized email — every token is a real
        // outbound email from our sending domain.
        configs.put("buyer-password-reset", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(buyerPasswordResetCapacity,
                        Duration.ofMinutes(buyerPasswordResetWindow)))
                .build());
        // Code resend, keyed per normalized email. Caps how fast fresh codes can
        // be minted, which is what keeps the per-code attempt cap meaningful.
        configs.put("buyer-verification-resend", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(buyerVerificationResendCapacity,
                        Duration.ofMinutes(buyerVerificationResendWindow)))
                .build());
        // Add-an-address, keyed per buyer account id. Consumed by R1.3's
        // POST /buyer/emails; the bucket ships here so all five arrive together.
        configs.put("buyer-email-add", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(buyerEmailAddCapacity, Duration.ofMinutes(buyerEmailAddWindow)))
                .build());
        // Re-send my tickets, keyed per buyer account id. Consumed by
        // POST /buyer/orders/{token}/resend.
        //
        // A bucket that is not registered here is not a soft failure: the
        // lambda below throws on an unknown name and the global handler turns
        // that into a 500. The test double invents a bucket for any name, so
        // an unregistered bucket is green in the suite and broken in prod —
        // which is exactly how this one was nearly shipped.
        configs.put("buyer-order-resend", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(buyerOrderResendCapacity, Duration.ofMinutes(buyerOrderResendWindow)))
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
