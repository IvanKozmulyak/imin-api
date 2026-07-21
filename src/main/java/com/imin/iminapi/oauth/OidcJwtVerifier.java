package com.imin.iminapi.oauth;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifies an RS256-signed OIDC ID token against a provider's published JWKS.
 * The remote JWKS is fetched lazily on first use and cached by Nimbus (with
 * rate-limited refresh), so there is no network hit at construction time and
 * repeated verifications reuse the cached keys.
 *
 * <p>The signature, {@code exp}/{@code nbf} and required claims are checked by
 * Nimbus; issuer and audience are then asserted explicitly (issuer against an
 * allow-set so Google's two issuer spellings both pass).
 */
public class OidcJwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(OidcJwtVerifier.class);

    private final DefaultJWTProcessor<SecurityContext> processor;
    private final Set<String> allowedIssuers;
    private final String expectedAudience;

    public OidcJwtVerifier(String jwkSetUrl, Set<String> allowedIssuers, String expectedAudience) {
        this.allowedIssuers = Set.copyOf(allowedIssuers);
        this.expectedAudience = expectedAudience;
        try {
            URL url = URI.create(jwkSetUrl).toURL();
            JWKSource<SecurityContext> keySource = JWKSourceBuilder.create(url).build();
            DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
            p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));
            // exp/nbf are checked automatically when present. Only require `iss`,
            // which both ID tokens and Apple's server-to-server notification JWTs
            // carry (the latter has no top-level `sub` — it lives inside `events`).
            p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                    new JWTClaimsSet.Builder().build(),
                    new HashSet<>(List.of("iss"))));
            this.processor = p;
        } catch (Exception e) {
            throw new IllegalStateException("Bad JWKS URL: " + jwkSetUrl, e);
        }
    }

    /** Verify the raw compact JWT and return its claims, or throw a 401 ApiException. */
    public JWTClaimsSet verify(String rawToken) {
        JWTClaimsSet claims;
        try {
            claims = processor.process(rawToken, null);
        } catch (Exception e) {
            log.warn("OIDC token verification failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Could not verify the provider identity token");
        }
        if (claims.getIssuer() == null || !allowedIssuers.contains(claims.getIssuer())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Unexpected token issuer");
        }
        List<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(expectedAudience)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Token audience mismatch");
        }
        return claims;
    }
}
