package com.imin.iminapi.service.ticket;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * Signs and verifies tamper-evident QR payloads.
 *
 * <p>Format: {@code imin1.<ticketToken>.<hmacB64UrlNoPad>}, where the HMAC is
 * the first 16 bytes of HMAC-SHA256(secret, "v1|" + ticketToken).
 *
 * <p>The version prefix lets us rotate the signing scheme without re-issuing
 * every outstanding ticket — a v2 verifier would accept both during a window.
 * Total payload is ~60 chars and fits a scannable QR at {@code
 * ErrorCorrectionLevel.H}.
 */
@Component
public class QrPayloadSigner {

    private static final String VERSION = "imin1";
    private static final int HMAC_BYTES = 16;
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] key;

    public QrPayloadSigner(TicketProperties props) {
        if (props.getSigningSecret() == null || props.getSigningSecret().isBlank()) {
            throw new IllegalStateException(
                    "IMIN_TICKET_SIGNING_SECRET is required to sign/verify ticket QR payloads");
        }
        this.key = props.getSigningSecret().getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String ticketToken) {
        byte[] sig = mac(ticketToken);
        return VERSION + "." + ticketToken + "." + ENC.encodeToString(sig);
    }

    public Optional<String> verify(String payload) {
        if (payload == null) return Optional.empty();
        String[] parts = payload.split("\\.", 3);
        if (parts.length != 3) return Optional.empty();
        if (!VERSION.equals(parts[0])) return Optional.empty();
        String token = parts[1];
        byte[] presented;
        try {
            presented = DEC.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        byte[] expected = mac(token);
        if (!MessageDigest.isEqual(presented, expected)) return Optional.empty();
        return Optional.of(token);
    }

    private byte[] mac(String token) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] full = m.doFinal(("v1|" + token).getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(full, HMAC_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
