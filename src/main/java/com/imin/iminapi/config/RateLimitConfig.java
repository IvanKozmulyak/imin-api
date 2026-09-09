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
    @Value("${imin.ratelimit.wallet-pass.capacity}")
    private int walletPassCapacity;
    @Value("${imin.ratelimit.wallet-pass.window-minutes}")
    private int walletPassWindow;
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
    @Value("${imin.ratelimit.buyer-order-resend.capacity}")
    private int buyerOrderResendCapacity;
    @Value("${imin.ratelimit.buyer-order-resend.window-minutes}")
    private int buyerOrderResendWindow;
    @Value("${imin.ratelimit.buyer-password-change.capacity}")
    private int buyerPasswordChangeCapacity;
    @Value("${imin.ratelimit.buyer-password-change.window-minutes}")
    private int buyerPasswordChangeWindow;
    @Value("${imin.ratelimit.buyer-native-signin.capacity}")
    private int buyerNativeSignInCapacity;
    @Value("${imin.ratelimit.buyer-native-signin.window-minutes}")
    private int buyerNativeSignInWindow;
    @Value("${imin.ratelimit.verify-email.capacity}")
    private int verifyEmailCapacity;
    @Value("${imin.ratelimit.verify-email.window-minutes}")
    private int verifyEmailWindow;
    @Value("${imin.ratelimit.signup.capacity}")
    private int signupCapacity;
    @Value("${imin.ratelimit.signup.window-minutes}")
    private int signupWindow;
    @Value("${imin.ratelimit.reset-password-token.capacity}")
    private int resetPasswordTokenCapacity;
    @Value("${imin.ratelimit.reset-password-token.window-minutes}")
    private int resetPasswordTokenWindow;
    @Value("${imin.ratelimit.buyer-reset-password-token.capacity}")
    private int buyerResetPasswordTokenCapacity;
    @Value("${imin.ratelimit.buyer-reset-password-token.window-minutes}")
    private int buyerResetPasswordTokenWindow;
    @Value("${imin.ratelimit.unsubscribe.capacity}")
    private int unsubscribeCapacity;
    @Value("${imin.ratelimit.unsubscribe.window-minutes}")
    private int unsubscribeWindow;
    @Value("${imin.ratelimit.ai-content.capacity}")
    private int aiContentCapacity;
    @Value("${imin.ratelimit.ai-content.window-minutes}")
    private int aiContentWindow;
    @Value("${imin.ratelimit.public-track.capacity}")
    private int publicTrackCapacity;
    @Value("${imin.ratelimit.public-track.window-minutes}")
    private int publicTrackWindow;

    @Value("${imin.ratelimit.buyer-verify-email.capacity}")
    private int buyerVerifyEmailCapacity;
    @Value("${imin.ratelimit.buyer-verify-email.window-minutes}")
    private int buyerVerifyEmailWindow;

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
        // Signed .pkpass minting on the public per-ticket asset endpoint, keyed per
        // client IP. Unauthenticated, and each call is three DB reads plus an RSA
        // signature plus a ZIP — the most expensive thing reachable with only a URL.
        configs.put("wallet-pass", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(walletPassCapacity, Duration.ofMinutes(walletPassWindow)))
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
        // Password CHANGE, keyed per buyer account id. Distinct from
        // buyer-password-reset, which is the unauthenticated forgot-password mail:
        // this one sits behind a session and verifies the CURRENT password, so it
        // is an oracle, and it shipped with no bucket and no attempt counter at
        // all. Account-keyed because the secret belongs to the account and the
        // attacker (holding a session, not the password) can change IP freely.
        configs.put("buyer-password-change", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(buyerPasswordChangeCapacity,
                        Duration.ofMinutes(buyerPasswordChangeWindow)))
                .build());
        // Native app sign-in (Google AND Apple ID-token lanes), keyed per client
        // IP — the only key that exists before the token is verified. One bucket
        // for both providers, like wallet-pass: same act, same cost, and two
        // buckets would just hand an attacker double the budget for alternating
        // between them. Sized for carrier NAT; see application.yaml.
        configs.put("buyer-native-signin", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(buyerNativeSignInCapacity,
                        Duration.ofMinutes(buyerNativeSignInWindow)))
                .build());


        // ---- Previously unmetered open endpoints (legal/security audit) -----
        // Verify-email, keyed per address: a correct guess returns a live
        // organizer session, and resend-verification mints fresh codes, so the
        // per-code attempt cap alone bounded nothing.
        configs.put("verify-email", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(verifyEmailCapacity, Duration.ofMinutes(verifyEmailWindow)))
                .build());
        // Organizer account creation, keyed per client IP — per-email would let an
        // attacker burn a stranger's bucket. Mirrors buyer-signup.
        configs.put("signup", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(signupCapacity, Duration.ofMinutes(signupWindow)))
                .build());
        // The consume half of password reset, both surfaces. forgot-password was
        // metered per address from the start; these two were not metered at all.
        configs.put("reset-password-token", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(resetPasswordTokenCapacity,
                        Duration.ofMinutes(resetPasswordTokenWindow)))
                .build());
        configs.put("buyer-reset-password-token", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(buyerResetPasswordTokenCapacity,
                        Duration.ofMinutes(buyerResetPasswordTokenWindow)))
                .build());
        // Owned opt-out, keyed per client IP. Deliberately loose — an opt-out is
        // the one request that must never be hard to complete.
        configs.put("unsubscribe", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(unsubscribeCapacity, Duration.ofMinutes(unsubscribeWindow)))
                .build());
        // POST /events/ai-content: unauthenticated and billed to imin per call.
        configs.put("ai-content", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(aiContentCapacity, Duration.ofMinutes(aiContentWindow)))
                .build());
        // Public funnel beacon. A full bucket drops the beacon and still answers
        // 204 — see FunnelTrackingController for why the status must not change.
        configs.put("public-track", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(publicTrackCapacity, Duration.ofMinutes(publicTrackWindow)))
                .build());
        // Buyer verify-email, keyed per client IP. It had no bucket at all: the
        // DB-counted lockout was the stated control, and that counter was keyed
        // on the address in the request body — a stranger's failures locked the
        // owner out. The counter now keys on (address, IP); this is what bounds
        // a caller who rotates addresses.
        configs.put("buyer-verify-email", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(buyerVerifyEmailCapacity,
                        Duration.ofMinutes(buyerVerifyEmailWindow)))
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
