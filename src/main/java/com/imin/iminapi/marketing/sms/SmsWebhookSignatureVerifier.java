package com.imin.iminapi.marketing.sms;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Verifies the inbound SMS webhook signature (STOP replies + delivery receipts).
 *
 * <p>Computes {@code base64(HMAC-SHA256(secret, rawBody))} and compares it,
 * constant-time, to the value the provider sends in the signature header. This is
 * the same fail-closed posture as the Resend/Svix and Stripe verifiers: a <b>blank
 * secret rejects every webhook</b>, so an unconfigured deploy never accepts an
 * unauthenticated STOP.
 *
 * <p><b>Setup note:</b> this models a simple HMAC-over-body scheme. Bird/MessageBird's
 * live inbound signing (the {@code MessageBird-Signature-JWT} header, or the older
 * HMAC over {@code timestamp + query + sha256(body)}) must be reconciled here at
 * account setup — this class is the single place that changes.
 */
@Component
public class SmsWebhookSignatureVerifier {

    /** @return true iff {@code providedSignature} is a valid HMAC of {@code rawBody} under {@code secret}. */
    public boolean verify(String secret, String rawBody, String providedSignature) {
        if (secret == null || secret.isBlank()) return false;      // fail closed
        if (providedSignature == null || providedSignature.isBlank()) return false;
        String expected = hmacBase64(secret, rawBody == null ? "" : rawBody);
        return constantTimeEquals(expected, providedSignature.trim());
    }

    private static String hmacBase64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
