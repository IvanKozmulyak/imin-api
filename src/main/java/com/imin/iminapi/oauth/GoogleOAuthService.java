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
 * trusted (no userinfo call). All network + crypto lives here; the resulting
 * {@link OAuthUserInfo} is handed to {@link OAuthAccountService}.
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

    /** Build the Google authorize URL (fresh signed state, openid/email/profile scopes). */
    public String buildAuthorizeUrl() {
        OAuthProperties.Google g = props.getGoogle();
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", g.getClientId())
                .queryParam("redirect_uri", g.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", stateService.sign(PROVIDER))
                .queryParam("access_type", "online")
                .queryParam("prompt", "select_account")
                .build()
                .encode()
                .toUriString();
    }

    /** Validate state, exchange the code, verify the ID token, extract the identity. */
    public OAuthUserInfo exchange(String code, String state) {
        stateService.verify(state, PROVIDER);
        OAuthProperties.Google g = props.getGoogle();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", g.getClientId());
        form.add("client_secret", g.getClientSecret());
        form.add("redirect_uri", g.getRedirectUri());
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
