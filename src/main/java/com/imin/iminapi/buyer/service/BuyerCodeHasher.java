package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.BuyerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Mints and checks the six-digit buyer address-verification code (epic §2.2).
 *
 * <h2>Why six digits and not the organizer's four</h2>
 *
 * <p>The organizer code ({@code EmailVerificationService:58}) is four digits in
 * plaintext with five attempts per code and three resends per fifteen minutes —
 * roughly fifteen guesses per fifteen minutes against a 10,000-value space. On
 * that side the code confirms an address on an account that owns nothing yet.
 * On the buyer side the same code is the gate on <b>joining someone else's
 * purchase history</b>, and {@code GET /buyer/orders} hands back an
 * {@code orderToken} per order — a bearer credential that opens tickets. Six
 * digits (10⁶), five attempts per code, and the DB-counted per-address lockout
 * in {@link BuyerEmailVerificationService} put the expected time-to-hit in
 * centuries.
 *
 * <h2>Why HMAC and not a bare digest</h2>
 *
 * <p>A plain SHA-256 of a six-digit code is an offline dictionary of one million
 * entries — it protects against nothing that matters. The stored value is
 * {@code HMAC-SHA256(IMIN_BUYER_CODE_SECRET, code)}, so a database read without
 * the key yields no codes. (§15 settled the peppered-HMAC-vs-BCrypt question
 * this way; the secret is set in production.)
 *
 * <h2>What a blank secret does</h2>
 *
 * <p>Under the {@code prod} profile, <b>startup fails</b>. Anywhere else it
 * degrades to an ephemeral per-instance key and logs a warning — the same
 * fallback {@code OAuthStateService} uses for its HMAC key, and harmless here
 * because a code lives ten minutes: a restart invalidates outstanding codes and
 * a buyer asks for another. In production that same behaviour would silently
 * break verification across a replica boundary, which is why it is fatal there.
 */
@Component
public class BuyerCodeHasher {

    private static final Logger log = LoggerFactory.getLogger(BuyerCodeHasher.class);
    private static final String HMAC_ALG = "HmacSHA256";
    /** 10⁶ — see the class Javadoc for why not the organizer's 10⁴. */
    private static final int CODE_BOUND = 1_000_000;

    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;

    public BuyerCodeHasher(BuyerProperties props, Environment env) {
        String configured = props.getCodeSecret();
        if (configured != null && !configured.isBlank()) {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
            return;
        }
        boolean prod = java.util.Arrays.asList(env.getActiveProfiles()).contains("prod");
        if (prod) {
            throw new IllegalStateException(
                    "IMIN_BUYER_CODE_SECRET is unset. Buyer verification codes would be peppered with an "
                    + "ephemeral per-instance key, so a code issued by one replica could not be verified by "
                    + "another and a restart would invalidate every outstanding code. Set it before deploying.");
        }
        byte[] ephemeral = new byte[32];
        new SecureRandom().nextBytes(ephemeral);
        this.secret = ephemeral;
        log.warn("imin.buyer.code-secret is unset — using an ephemeral per-instance pepper for buyer "
                + "verification codes. Fine for dev/test; IMIN_BUYER_CODE_SECRET is mandatory in prod.");
    }

    /** A fresh zero-padded six-digit code. */
    public String generateCode() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(CODE_BOUND));
    }

    /** {@code HMAC-SHA256(secret, code)} as 64 lowercase hex chars — the {@code code_hash} column width. */
    public String hash(String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    /** Constant-time comparison — a timing side channel on a six-digit space is not academic. */
    public boolean matches(String code, String storedHash) {
        if (code == null || storedHash == null) return false;
        return MessageDigest.isEqual(
                hash(code).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
