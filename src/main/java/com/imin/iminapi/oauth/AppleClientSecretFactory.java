package com.imin.iminapi.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Mints the ES256 "client secret" JWT that Sign in with Apple requires in place
 * of a static secret at the token endpoint. Signed with the {@code .p8} private
 * key (base64 config); {@code iss = teamId}, {@code sub = clientId (Services ID)},
 * {@code aud = https://appleid.apple.com}. Apple caps the lifetime at 6 months;
 * we mint 24h tokens and cache the instance ~12h so we re-sign at most twice a day.
 */
@Component
public class AppleClientSecretFactory {

    private static final String AUDIENCE = "https://appleid.apple.com";
    private static final Duration TOKEN_TTL = Duration.ofHours(24);
    private static final Duration REFRESH_AFTER = Duration.ofHours(12);

    private final OAuthProperties props;

    private volatile String cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public AppleClientSecretFactory(OAuthProperties props) {
        this.props = props;
    }

    /** Return a valid client-secret JWT, re-signing only when the cached one is stale. */
    public String get() {
        String c = cached;
        if (c != null && Duration.between(cachedAt, Instant.now()).compareTo(REFRESH_AFTER) < 0) {
            return c;
        }
        synchronized (this) {
            if (cached != null && Duration.between(cachedAt, Instant.now()).compareTo(REFRESH_AFTER) < 0) {
                return cached;
            }
            String fresh = mint();
            cached = fresh;
            cachedAt = Instant.now();
            return fresh;
        }
    }

    private String mint() {
        OAuthProperties.Apple a = props.getApple();
        Instant now = Instant.now();
        try {
            ECPrivateKey key = parsePrivateKey(a.getPrivateKeyBase64());
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .keyID(a.getKeyId())
                    .build();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(a.getTeamId())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(TOKEN_TTL)))
                    .audience(AUDIENCE)
                    .subject(a.getClientId())
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint Apple client secret: " + e.getMessage(), e);
        }
    }

    /**
     * Parse the EC private key. Accepts base64 of the raw {@code .p8} PEM file
     * (the common case) or base64 of the already-stripped DER body.
     */
    static ECPrivateKey parsePrivateKey(String base64) throws Exception {
        byte[] first = Base64.getMimeDecoder().decode(base64.trim());
        String asText = new String(first, StandardCharsets.UTF_8);
        byte[] der;
        if (asText.contains("BEGIN")) {
            String inner = asText
                    .replaceAll("-----BEGIN[^-]+-----", "")
                    .replaceAll("-----END[^-]+-----", "")
                    .replaceAll("\\s", "");
            der = Base64.getMimeDecoder().decode(inner);
        } else {
            der = first;
        }
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
