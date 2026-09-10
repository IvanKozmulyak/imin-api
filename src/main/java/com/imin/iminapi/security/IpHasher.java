package com.imin.iminapi.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Keyed hash for the IP addresses stored beside rate-limit counters.
 *
 * <h2>Why an unsalted digest was not enough</h2>
 *
 * <p>{@code order_recovery_attempts.ip_hash} and its refund-request sibling were
 * a bare {@code SHA-256(ip)}. IPv4 is 2³² values — a complete rainbow table is
 * hours of work on a laptop — so an unsalted digest is a reversible record of
 * which address asked about which order, i.e. personal data stored as if it were
 * pseudonymised when it is not.
 *
 * <p>{@code HMAC-SHA256(secret, ip)} makes the table useless without the key.
 *
 * <h2>Configuration</h2>
 *
 * <p>{@code IMIN_IP_HASH_SECRET}, defaulting to the ticket signing secret, which
 * is already set in production — so this needs no new environment variable to be
 * correct, and setting one later is a clean rotation. A blank value degrades to
 * an unkeyed digest with the previous behaviour rather than failing startup: the
 * only thing these hashes gate is a rate-limit count, and refusing to boot over
 * it would trade a privacy improvement for an outage.
 *
 * <p><b>Existing rows stop matching.</b> Changing the function changes every
 * hash, so counters keyed on an old value read as zero once. Both windows are an
 * hour or less and the rows are swept at 24h, so the effect is one hour of
 * slightly more generous limits on the day of deploy.
 */
@Component
public class IpHasher {

    private final byte[] secret;

    public IpHasher(@Value("${imin.security.ip-hash-secret:${imin.ticket.signing-secret:}}") String secret) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String ip) {
        String value = ip == null ? "" : ip;
        try {
            if (secret.length == 0) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // The column is NOT NULL and this is a rate-limit key, not an authorization
            // input — a constant is a working (if useless) bucket, an exception is a 500.
            return "";
        }
    }
}
