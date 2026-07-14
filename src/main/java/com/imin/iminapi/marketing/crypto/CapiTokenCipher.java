package com.imin.iminapi.marketing.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encrypt/decrypt for the Meta CAPI access token.
 *
 * <p>The token is organizer-minted in Meta Events Manager and must be stored
 * write-only: it is encrypted before persistence and never returned by any API.
 * A fresh 12-byte IV is generated per encryption and prepended to the ciphertext;
 * the 128-bit GCM auth tag makes tampering detectable. Ciphertext is stored
 * base64-encoded as {@code IV || ciphertext+tag}.
 *
 * <p>Key comes from {@code META_CAPI_ENC_KEY} (base64 of exactly 32 bytes).
 * The key is validated LAZILY on first encrypt/decrypt, not at construction —
 * a missing key must degrade the Meta feature (503 on token save), never block
 * application boot. (2026-07-14: an eager check here crash-looped prod because
 * the env landed after the deploy.)
 */
@Component
public class CapiTokenCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String keyB64;
    private volatile SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CapiTokenCipher(@Value("${imin.meta.enc-key:${META_CAPI_ENC_KEY:}}") String keyB64) {
        this.keyB64 = keyB64;
    }

    private SecretKeySpec requireKey() {
        SecretKeySpec k = key;
        if (k != null) return k;
        if (keyB64 == null || keyB64.isBlank()) {
            throw new IllegalStateException(
                    "META_CAPI_ENC_KEY is required to encrypt Meta CAPI access tokens "
                    + "(base64 of a 32-byte key). Set the environment variable and restart.");
        }
        byte[] raw = Base64.getDecoder().decode(keyB64);
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "META_CAPI_ENC_KEY must decode to exactly 32 bytes (AES-256); got " + raw.length);
        }
        k = new SecretKeySpec(raw, "AES");
        key = k;
        return k;
    }

    public String encrypt(String plaintext) {
        SecretKeySpec k = requireKey(); // outside the try: missing-key must not be masked by the wrap
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("CAPI token encryption failed", e);
        }
    }

    public String decrypt(String encB64) {
        SecretKeySpec k = requireKey(); // outside the try: missing-key must not be masked by the wrap
        try {
            byte[] all = Base64.getDecoder().decode(encB64);
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("CAPI token decryption failed", e);
        }
    }
}
