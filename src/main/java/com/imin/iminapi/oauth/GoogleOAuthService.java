package com.imin.iminapi.oauth;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.nimbusds.jwt.JWTClaimsSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Set;

/**
 * Google OIDC provider: builds the authorize URL, exchanges the auth code, and
 * verifies the returned ID token against Google's JWKS. Only ID-token claims are
 * trusted (no userinfo call). All network + crypto lives here.
 *
 * <h2>Two audiences, one Google client</h2>
 *
 * <p>Organizer sign-in and buyer sign-in (buyer-accounts epic §2.4) share this
 * class for the HTTP exchange and ID-token verification, and nothing else. They
 * are otherwise physically separate: different endpoints, different redirect
 * URIs, different resolution services, different sessions. In particular
 * {@link OAuthAccountService} — whose step 5 auto-provisions an
 * {@code Organization} — is reached only from the organizer path.
 *
 * <p>The <b>redirect URI is a parameter</b> rather than a field read twice,
 * because Google requires the URI in the token exchange to equal the one the
 * authorize URL used, and the two audiences land the browser on different
 * frontends ({@code dashboard.imin.wtf} vs {@code app.imin.wtf}). The organizer
 * entry points below keep today's behaviour exactly by passing
 * {@code props.getGoogle().getRedirectUri()}; {@code GoogleOAuthServiceTest} is
 * the regression net that pins it.
 */
@Service
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);

    static final String PROVIDER = "google";
    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final OAuthProperties props;
    private final OAuthStateService stateService;
    private final RestClient http;
    private volatile OidcJwtVerifier verifier;

    public GoogleOAuthService(OAuthProperties props,
                              OAuthStateService stateService,
                              @Qualifier("oauthRestClient") RestClient http) {
        this.props = props;
        this.stateService = stateService;
        this.http = http;
    }

    /**
     * Build the ORGANIZER authorize URL (fresh signed organizer-audience state,
     * openid/email/profile scopes). Unchanged behaviour; the buyer flow calls
     * {@link #buildAuthorizeUrl(String, String)} with its own redirect URI and
     * its own state.
     */
    public String buildAuthorizeUrl() {
        return buildAuthorizeUrl(props.getGoogle().getRedirectUri(), stateService.sign(PROVIDER));
    }

    /**
     * Build a Google authorize URL for a caller-chosen redirect URI and state.
     *
     * <p>{@code redirectUri} must be registered on the Google client and must be
     * the same value later handed to {@link #exchangeCode(String, String)} —
     * Google rejects the exchange otherwise, and it does so <i>after</i> the
     * user has already consented.
     */
    public String buildAuthorizeUrl(String redirectUri, String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", props.getGoogle().getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("access_type", "online")
                .queryParam("prompt", "select_account")
                .build()
                .encode()
                .toUriString();
    }

    /**
     * ORGANIZER entry point: validate the state as an organizer-audience state,
     * then exchange against the organizer redirect URI.
     *
     * <p>State verification happens <b>before</b> the network call, and that
     * ordering is the CSRF defence — a forged state, a state minted for Apple,
     * or (since the buyer epic) a state minted for the buyer audience must never
     * reach Google's token endpoint, let alone
     * {@code OAuthAccountService.resolve} step 5.
     */
    public OAuthUserInfo exchange(String code, String state) {
        stateService.verify(state, PROVIDER);
        return exchangeCode(code, props.getGoogle().getRedirectUri());
    }

    /**
     * Exchange an authorization code and verify the returned ID token.
     *
     * <p><b>Verifies no state.</b> Callers own that, because the two audiences
     * check different things — the buyer flow additionally requires an audience
     * match and a browser-nonce binding. Never call this without having verified
     * a state first.
     */
    public OAuthUserInfo exchangeCode(String code, String redirectUri) {
        OAuthProperties.Google g = props.getGoogle();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", g.getClientId());
        form.add("client_secret", g.getClientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        Map<String, Object> tokenResponse = postForm(form);
        Object idToken = tokenResponse == null ? null : tokenResponse.get("id_token");
        if (idToken == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Google did not return an ID token");
        }

        JWTClaimsSet claims = verifier().verify(idToken.toString());
        try {
            String sub = claims.getSubject();
            String email = claims.getStringClaim("email");
            boolean emailVerified = asBool(claims.getClaim("email_verified"));
            String given = claims.getStringClaim("given_name");
            String family = claims.getStringClaim("family_name");
            String name = claims.getStringClaim("name");
            return new OAuthUserInfo(PROVIDER, sub, email, emailVerified, given, family, name);
        } catch (java.text.ParseException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Malformed Google ID token claims");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForm(MultiValueMap<String, String> form) {
        try {
            return http.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            log.warn("Google token exchange rejected: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Google rejected the authorization code");
        } catch (Exception e) {
            log.warn("Google token exchange failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Google token endpoint unreachable");
        }
    }

    private OidcJwtVerifier verifier() {
        OidcJwtVerifier v = verifier;
        if (v == null) {
            synchronized (this) {
                v = verifier;
                if (v == null) {
                    v = new OidcJwtVerifier(JWKS_URL, ISSUERS, props.getGoogle().getClientId());
                    verifier = v;
                }
            }
        }
        return v;
    }

    private static boolean asBool(Object claim) {
        if (claim instanceof Boolean b) return b;
        return claim != null && "true".equalsIgnoreCase(String.valueOf(claim));
    }
}
