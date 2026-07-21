package com.imin.iminapi.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.UserIdentity;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.UserIdentityRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.nimbusds.jwt.JWTClaimsSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sign in with Apple provider. Builds the authorize URL (form_post response
 * mode, name+email scope), mints the ES256 client secret to exchange the code,
 * verifies the ID token against Apple's JWKS, and folds in the first-auth name
 * payload (Apple sends the user's name only once). Also handles Apple's
 * server-to-server notifications (consent-revoked / account-delete).
 */
@Service
public class AppleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(AppleOAuthService.class);

    static final String PROVIDER = "apple";
    private static final String AUTHORIZE_URL = "https://appleid.apple.com/auth/authorize";
    private static final String TOKEN_URL = "https://appleid.apple.com/auth/token";
    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String ISSUER = "https://appleid.apple.com";

    private final OAuthProperties props;
    private final OAuthStateService stateService;
    private final AppleClientSecretFactory clientSecrets;
    private final UserIdentityRepository identities;
    private final AuthSessionRepository sessions;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();
    private volatile OidcJwtVerifier verifier;

    public AppleOAuthService(OAuthProperties props,
                             OAuthStateService stateService,
                             AppleClientSecretFactory clientSecrets,
                             UserIdentityRepository identities,
                             AuthSessionRepository sessions,
                             @Qualifier("oauthRestClient") RestClient http) {
        this.props = props;
        this.stateService = stateService;
        this.clientSecrets = clientSecrets;
        this.identities = identities;
        this.sessions = sessions;
        this.http = http;
    }

    /** Build the Apple authorize URL. form_post is mandatory when requesting name/email scope. */
    public String buildAuthorizeUrl() {
        OAuthProperties.Apple a = props.getApple();
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", a.getClientId())
                .queryParam("redirect_uri", a.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("response_mode", "form_post")
                .queryParam("scope", "name email")
                .queryParam("state", stateService.sign(PROVIDER))
                .build()
                .encode()
                .toUriString();
    }

    /**
     * Validate state, exchange the code with the minted client secret, verify the
     * ID token, and merge the first-auth {@code user} JSON (name) if present.
     * Apple emails are always treated as verified (including relay addresses).
     */
    public OAuthUserInfo exchange(String code, String state, String userJson) {
        stateService.verify(state, PROVIDER);
        OAuthProperties.Apple a = props.getApple();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", a.getClientId());
        form.add("client_secret", clientSecrets.get());
        form.add("code", code);
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", a.getRedirectUri());

        Map<String, Object> tokenResponse = postForm(form);
        Object idToken = tokenResponse == null ? null : tokenResponse.get("id_token");
        if (idToken == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Apple did not return an ID token");
        }

        JWTClaimsSet claims = verifier().verify(idToken.toString());
        String sub;
        String email;
        try {
            sub = claims.getSubject();
            email = claims.getStringClaim("email");
        } catch (java.text.ParseException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Malformed Apple ID token claims");
        }

        String first = "";
        String last = "";
        if (userJson != null && !userJson.isBlank()) {
            try {
                JsonNode name = json.readTree(userJson).path("name");
                first = name.path("firstName").asText("");
                last = name.path("lastName").asText("");
            } catch (Exception e) {
                log.warn("Could not parse Apple first-auth user payload: {}", e.getMessage());
            }
        }
        String display = (first + " " + last).trim();
        // Apple asserts every email it returns (relay addresses included) as usable.
        return new OAuthUserInfo(PROVIDER, sub, email, true, first, last,
                display.isEmpty() ? null : display);
    }

    /**
     * Handle an Apple server-to-server notification. The single {@code payload}
     * form field is a signed JWT whose {@code events} claim is a JSON string
     * describing the change. On consent-revoked / account-delete we revoke all of
     * the linked user's sessions and drop the apple identity row. Best-effort and
     * always 200 — Apple retries on non-2xx, so we never surface a failure.
     */
    @Transactional
    public void handleServerNotification(String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("Apple notification received with empty payload");
            return;
        }
        JWTClaimsSet claims;
        try {
            claims = verifier().verify(payload);
        } catch (RuntimeException e) {
            log.warn("Apple notification signature verification failed: {}", e.getMessage());
            return;
        }
        try {
            String eventsRaw = claims.getStringClaim("events");
            if (eventsRaw == null) return;
            JsonNode event = json.readTree(eventsRaw);
            String type = event.path("type").asText("");
            String sub = event.path("sub").asText("");
            log.info("Apple notification: type={} sub={}", type, sub);
            if (!Set.of("consent-revoked", "account-delete").contains(type) || sub.isBlank()) {
                return;
            }
            Optional<UserIdentity> identity = identities.findByProviderAndProviderUserId(PROVIDER, sub);
            if (identity.isEmpty()) return;
            UserIdentity id = identity.get();
            int revoked = sessions.revokeAllForUser(id.getUserId(), Instant.now());
            identities.delete(id);
            log.info("Apple {} for user {}: revoked {} sessions, unlinked identity",
                    type, id.getUserId(), revoked);
        } catch (Exception e) {
            log.warn("Apple notification processing failed: {}", e.getMessage());
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
            log.warn("Apple token exchange rejected: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Apple rejected the authorization code");
        } catch (Exception e) {
            log.warn("Apple token exchange failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Apple token endpoint unreachable");
        }
    }

    private OidcJwtVerifier verifier() {
        OidcJwtVerifier v = verifier;
        if (v == null) {
            synchronized (this) {
                v = verifier;
                if (v == null) {
                    v = new OidcJwtVerifier(JWKS_URL, Set.of(ISSUER), props.getApple().getClientId());
                    verifier = v;
                }
            }
        }
        return v;
    }
}
