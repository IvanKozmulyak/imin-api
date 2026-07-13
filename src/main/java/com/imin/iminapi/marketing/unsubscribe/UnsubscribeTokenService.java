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
 * Signs opaque opt-out tokens for the owned /api/v1/public/unsubscribe/{token}
 * endpoint (spec §2.4/§3/§7). Token = base64url(payload) + "." + base64url(hmac).
 * Payload = orgId:membershipId:campaignId:channel. Stateless — no DB lookup needed
 * to resolve who is unsubscribing; the HMAC is the integrity proof.
 */
@Service
public class UnsubscribeTokenService {

    private final byte[] secret;

    public UnsubscribeTokenService(
            @Value("${imin.marketing.unsubscribe-token-secret:${imin.ticket.signing-secret:dev-unsub-secret-change-me-32bytes}}")
            String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record Claims(UUID orgId, UUID membershipId, UUID campaignId, String channel) {}

    public String sign(UUID orgId, UUID membershipId, UUID campaignId, String channel) {
        String payload = orgId + ":" + membershipId + ":" + campaignId + ":" + channel;
        String p = base64(payload.getBytes(StandardCharsets.UTF_8));
        String sig = base64(hmac(p));
        return p + "." + sig;
    }

    public Optional<Claims> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();
        String p = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        String expected = base64(hmac(p));
        if (!constantTimeEquals(expected, sig)) return Optional.empty();
        try {
            String payload = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8);
            String[] parts = payload.split(":", 4);
            if (parts.length != 4) return Optional.empty();
            return Optional.of(new Claims(
                    UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                    UUID.fromString(parts[2]), parts[3]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
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
