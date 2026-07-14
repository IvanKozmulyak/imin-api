package com.imin.iminapi.marketing.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Standard-Webhooks (Svix) signature verification, as used by Resend. The
 * signed content is {@code id.timestamp.body}, HMAC-SHA256 keyed on the
 * base64-decoded secret payload (secret string is "whsec_&lt;base64&gt;"),
 * base64-encoded. The svix-signature header is space-separated
 * {@code v1,<sig>} entries; a match on ANY entry passes. Constant-time
 * compare. No external dependency — javax.crypto only.
 */
@Component
public class SvixSignatureVerifier {

    private static final String PREFIX = "whsec_";

    public boolean verify(String secret, String svixId, String svixTimestamp,
                          String body, String svixSignatureHeader, long toleranceSeconds) {
        if (secret == null || secret.isBlank()
                || svixId == null || svixTimestamp == null
                || svixSignatureHeader == null || svixSignatureHeader.isBlank()) {
            return false;
        }
        if (!withinTolerance(svixTimestamp, toleranceSeconds)) {
            return false;
        }
        String expected;
        try {
            String keyB64 = secret.startsWith(PREFIX) ? secret.substring(PREFIX.length()) : secret;
            byte[] key = Base64.getDecoder().decode(keyB64);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String toSign = svixId + "." + svixTimestamp + "." + body;
            byte[] digest = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            expected = Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        for (String part : svixSignatureHeader.split(" ")) {
            int comma = part.indexOf(',');
            String candidate = comma >= 0 ? part.substring(comma + 1) : part;
            if (MessageDigest.isEqual(expectedBytes, candidate.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private boolean withinTolerance(String timestamp, long toleranceSeconds) {
        try {
            long ts = Long.parseLong(timestamp.trim());
            long now = System.currentTimeMillis() / 1000L;
            return Math.abs(now - ts) <= toleranceSeconds;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
