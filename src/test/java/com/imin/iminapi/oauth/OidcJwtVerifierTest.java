package com.imin.iminapi.oauth;

import com.imin.iminapi.security.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * infra-16: the claims verifier required {@code iss} alone, and
 * {@code DefaultJWTClaimsVerifier} validates {@code exp} only when the claim is
 * present — so a signed ID token with no expiry verified for ever. This class is
 * the sole gate on {@code POST /api/v1/buyer/auth/{google,apple}/native}, which
 * accept a raw ID token with no state, no nonce and no code exchange.
 *
 * <p>Driven against a real local JWKS and a real RS256 signature, because the
 * defect is in what Nimbus is configured to demand, not in our own claim checks.
 */
class OidcJwtVerifierTest {

    private static final String ISSUER = "https://issuer.test";
    private static final String AUDIENCE = "wtf.imin.fan";

    private static HttpServer jwksServer;
    private static RSAKey signingKey;
    private static OidcJwtVerifier verifier;

    @BeforeAll
    static void startJwks() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);

        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        jwksServer.start();

        verifier = new OidcJwtVerifier(
                "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks",
                Set.of(ISSUER), AUDIENCE);
    }

    @AfterAll
    static void stopJwks() {
        if (jwksServer != null) jwksServer.stop(0);
    }

    @Test
    void a_normal_id_token_verifies() {
        String token = sign(claims().expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        assertThat(verifier.verify(token).getSubject()).isEqualTo("subject-1");
    }

    @Test
    void a_token_with_no_expiry_is_rejected() {
        String token = sign(claims().build());
        assertThatThrownBy(() -> verifier.verify(token))
                .as("an ID token with no exp is valid for ever; exp must be required, not merely honoured")
                .isInstanceOf(ApiException.class);
    }

    @Test
    void an_expired_token_is_still_rejected() {
        String token = sign(claims().expirationTime(Date.from(Instant.now().minusSeconds(300))).build());
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(ApiException.class);
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject("subject-1");
    }

    private static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
