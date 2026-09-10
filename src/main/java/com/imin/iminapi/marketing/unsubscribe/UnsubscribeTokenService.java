package com.imin.iminapi.marketing.unsubscribe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Signs opaque opt-out tokens for the owned {@code /api/v1/public/unsubscribe/{token}}
 * endpoint (spec §2.4/§3/§7) and for the notify-me opt-out. Token =
 * base64url(payload) + "." + base64url(hmac). Stateless — no DB lookup is
 * needed to resolve who is unsubscribing; the HMAC is the integrity proof.
 *
 * <h2>Its own key</h2>
 *
 * <p>This used to sign with {@code imin.ticket.signing-secret} — the key that
 * tamper-proofs ticket QR payloads. One key across two unrelated trust domains:
 * rotating it to deal with a ticket problem would silently kill every
 * unsubscribe link already sitting in an inbox, and a compromise on either side
 * would be a compromise of both. {@code IMIN_MARKETING_UNSUBSCRIBE_SECRET} is
 * now the signing key.
 *
 * <p>The cutover is deliberately soft. A blank own-key means "not configured
 * yet" and everything behaves exactly as before, so deploying this code without
 * setting the variable changes nothing. Verification always tries the own key
 * first and then the legacy key, so links minted before the variable was set
 * keep working — for ever.
 *
 * <h2>No expiry, on purpose</h2>
 *
 * <p>These tokens never expire. An unsubscribe link that has expired is an
 * unsubscribe link that fails at the exact moment someone finally got round to
 * using it, which is the opposite of the obligation it exists to discharge. The
 * token authorises one thing — removing consent — and there is no state in which
 * imin would rather refuse that.
 */
@Service
public class UnsubscribeTokenService {

    /** Distinguishes the two token shapes so one verifier cannot accept the other's token. */
    private static final String NOTIFY_PREFIX = "notify:";

    private final byte[] secret;
    private final byte[] legacySecret;

    public UnsubscribeTokenService(
            @Value("${imin.marketing.unsubscribe-secret:}")
            String unsubscribeSecret,
            @Value("${imin.marketing.unsubscribe-token-secret:${imin.ticket.signing-secret:dev-unsub-secret-change-me-32bytes}}")
            String legacySecret) {
        boolean configured = unsubscribeSecret != null && !unsubscribeSecret.isBlank();
        this.legacySecret = legacySecret.getBytes(StandardCharsets.UTF_8);
        this.secret = configured
                ? unsubscribeSecret.getBytes(StandardCharsets.UTF_8)
                : this.legacySecret;
    }

    public record Claims(UUID orgId, UUID membershipId, UUID campaignId, String channel) {}

    public String sign(UUID orgId, UUID membershipId, UUID campaignId, String channel) {
        return signPayload(orgId + ":" + membershipId + ":" + campaignId + ":" + channel);
    }

    public Optional<Claims> verify(String token) {
        return verifiedPayload(token).flatMap(payload -> {
            if (payload.startsWith(NOTIFY_PREFIX)) return Optional.empty();
            String[] parts = payload.split(":", 4);
            if (parts.length != 4) return Optional.empty();
            try {
                return Optional.of(new Claims(
                        UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                        UUID.fromString(parts[2]), parts[3]));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Opt-out token for one {@code notify_subscriptions} row. A guest subscriber
     * has no account and no other way out, so the link in the email is their only
     * opt-out (CPCE L34-5).
     */
    public String signNotify(UUID subscriptionId) {
        return signPayload(NOTIFY_PREFIX + subscriptionId);
    }

    public Optional<UUID> verifyNotify(String token) {
        return verifiedPayload(token).flatMap(payload -> {
            if (!payload.startsWith(NOTIFY_PREFIX)) return Optional.empty();
            try {
                return Optional.of(UUID.fromString(payload.substring(NOTIFY_PREFIX.length())));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        });
    }

    private String signPayload(String payload) {
        String p = base64(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + base64(hmac(p, secret));
    }

    /** The decoded payload, or empty when neither key signed this token. */
    private Optional<String> verifiedPayload(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();
        String p = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        // Own key first, then the legacy one: links minted before the cutover
        // must keep working, and there is no moment at which it is acceptable to
        // start answering 404 to somebody's opt-out.
        if (!constantTimeEquals(base64(hmac(p, secret)), sig)
                && !constantTimeEquals(base64(hmac(p, legacySecret)), sig)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(String data, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static String base64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
