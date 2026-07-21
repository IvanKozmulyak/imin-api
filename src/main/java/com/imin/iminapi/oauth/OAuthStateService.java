package com.imin.iminapi.oauth;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints and verifies the OAuth {@code state} parameter — a stateless, HMAC-signed
 * token that binds the authorize request to its callback with no server-side
 * storage. Layout: {@code base64url(provider:expEpochSec:nonce) + "." +
 * base64url(HMAC-SHA256(payload))}. Verification recomputes the MAC (constant-time
 * compare), checks the provider matches, and rejects anything past its 10-minute
 * expiry. This is the CSRF defence for the redirect flow.
 */
@Service
public class OAuthStateService {

    private static final Logger log = LoggerFactory.getLogger(OAuthStateService.class);
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String HMAC_ALG = "HmacSHA256";

    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;

    public OAuthStateService(OAuthProperties props) {
        String configured = props.getStateSecret();
        if (configured != null && !configured.isBlank()) {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        } else {
            byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            this.secret = ephemeral;
            log.warn("imin.oauth.state-secret is unset — using an ephemeral per-instance HMAC key. "
                    + "OAuth state will not verify across restarts or multiple instances. "
                    + "Set IMIN_OAUTH_STATE_SECRET in production.");
        }
    }

    /** Sign a fresh state token for the given provider. */
    public String sign(String provider) {
        long exp = Instant.now().plus(TTL).getEpochSecond();
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String payload = provider + ":" + exp + ":" + b64(nonce);
        return b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + b64(hmac(payload));
    }

    /** Verify a state token, or throw a clean 400 if it is missing/tampered/expired/mismatched. */
    public void verify(String state, String expectedProvider) {
        if (state == null || state.isBlank()) throw invalid();
        String[] parts = state.split("\\.", 2);
        if (parts.length != 2) throw invalid();

        String payload;
        try {
            payload = new String(b64d(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }

        byte[] expectedSig = hmac(payload);
        byte[] actualSig;
        try {
            actualSig = b64d(parts[1]);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(expectedSig, actualSig)) throw invalid();

        String[] fields = payload.split(":", 3);
        if (fields.length != 3) throw invalid();
        if (!expectedProvider.equals(fields[0])) throw invalid();

        long exp;
        try {
            exp = Long.parseLong(fields[1]);
        } catch (NumberFormatException e) {
            throw invalid();
        }
        if (Instant.now().getEpochSecond() > exp) throw invalid();
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.OAUTH_INVALID_STATE,
                "Invalid or expired sign-in state");
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] b64d(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
