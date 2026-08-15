package com.imin.iminapi.oauth;

import com.imin.iminapi.buyer.model.BuyerIdentity;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Verifies the ID token that {@code expo-apple-authentication} receives from
 * iOS, for the <b>buyer</b> surface.
 *
 * <h2>Why this is not {@link AppleOAuthService}</h2>
 *
 * <p>That class serves the organizer dashboard's web {@code form_post} flow and
 * hardcodes {@code emailVerified = true} at its line 131.
 * {@link com.imin.iminapi.buyer.service.BuyerOAuthService}'s Javadoc names that
 * exact line as a trap: inheriting it would make the buyer-side
 * {@code email_verified} gate a permanent no-op, and that gate is what stops a
 * token naming someone else's address from joining their order history.
 *
 * <p>So this class exists, it is small on purpose, and it <b>asserts</b> the
 * claim rather than assuming it. Apple does populate {@code email_verified} on
 * ID tokens (as a boolean or as the string {@code "true"} — both spellings
 * occur), and a Hide My Email relay address is always verified, so the gate
 * costs a real Apple user nothing.
 *
 * <h2>What native does not need</h2>
 *
 * <p>No client secret, no {@code .p8} key, no team id, no redirect URI, no
 * {@code state}: the OS performs the authorization and hands the app a signed
 * token. Only the JWKS and the audience are required, and the audience is the
 * app's bundle identifier rather than the web Services ID — which is why
 * {@link OAuthProperties.Apple#getNativeAudience()} has no fallback to
 * {@code client-id}.
 *
 * <h2>The name, and why it arrives only once</h2>
 *
 * <p>Apple returns the user's name on the <b>first</b> authorization only and
 * never again. The app must forward it on that first call; afterwards the token
 * carries a subject and often nothing else — no email either, which is why the
 * resolution matrix matches on subject. That is why {@link #verify(String,
 * String)} takes the name out-of-band instead of reading it from the token.
 */
@Service
public class AppleNativeIdentityService {

    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final Set<String> ISSUERS = Set.of("https://appleid.apple.com");

    private final OAuthProperties props;
    private volatile OidcJwtVerifier verifier;

    public AppleNativeIdentityService(OAuthProperties props) {
        this.props = props;
    }

    /** False until the app's bundle id is configured — reported as 404, never as a broken button. */
    public boolean enabled() {
        String audience = props.getApple().getNativeAudience();
        return audience != null && !audience.isBlank();
    }

    /**
     * Verify an Apple ID token and project it onto {@link OAuthUserInfo}.
     *
     * @param fullName the display name from the first authorization, or null on
     *                 every subsequent sign-in. Never trusted for anything but
     *                 display — it is client-supplied, not a token claim.
     */
    public OAuthUserInfo verify(String idToken, String fullName) {
        if (idToken == null || idToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "idToken is required");
        }
        JWTClaimsSet claims = verifier().verify(idToken);
        String subject;
        String email;
        Object verifiedClaim;
        try {
            subject = claims.getSubject();
            email = claims.getStringClaim("email");
            verifiedClaim = claims.getClaim("email_verified");
        } catch (java.text.ParseException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Malformed Apple ID token claims");
        }
        if (subject == null || subject.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Apple ID token has no subject");
        }

        // Apple spells this as a boolean on some tokens and as the string "true"
        // on others. Assert it either way rather than assuming it — assuming is
        // exactly the bug this class exists to avoid, and an absent claim is an
        // absent assertion, so it resolves to false.
        boolean emailVerified = Boolean.TRUE.equals(verifiedClaim)
                || "true".equalsIgnoreCase(String.valueOf(verifiedClaim));

        String display = fullName == null || fullName.isBlank() ? null : fullName.trim();
        return new OAuthUserInfo(BuyerIdentity.PROVIDER_APPLE, subject, email, emailVerified,
                null, null, display);
    }

    OidcJwtVerifier verifier() {          // package-private: the test injects a stub
        OidcJwtVerifier v = verifier;
        if (v == null) {
            synchronized (this) {
                v = verifier;
                if (v == null) {
                    v = new OidcJwtVerifier(JWKS_URL, ISSUERS, props.getApple().getNativeAudience());
                    verifier = v;
                }
            }
        }
        return v;
    }

    /** Test seam — lets a unit test drive {@link #verify} over canned claim sets. */
    void setVerifierForTest(OidcJwtVerifier stub) {
        this.verifier = stub;
    }
}
