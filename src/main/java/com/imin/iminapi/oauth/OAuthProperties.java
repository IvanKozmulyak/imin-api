package com.imin.iminapi.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code imin.oauth.*} configuration for social sign-in (Google OIDC + Sign in
 * with Apple), bound from {@code application.yaml} (which sources env vars).
 *
 * <p>Both providers are <b>config-gated</b>: a provider is "enabled" only when
 * every required secret is present. When disabled, its endpoints return a clean
 * 404 and the frontend hides its button — so a partially-configured deploy never
 * dead-ends a user. All secrets default blank so an unconfigured build boots.
 *
 * <ul>
 *   <li>{@code google.client-id / client-secret} — the OAuth 2.0 Client ID and
 *       secret from a Google Cloud Console "Web application" OAuth client.</li>
 *   <li>{@code google.redirect-uri} — where Google returns the browser with the
 *       auth code. This is a FRONTEND URL (the SPA callback), e.g.
 *       {@code https://dashboard.imin.wtf/auth/callback/google}. Must be
 *       registered as an Authorized redirect URI in the Google client.</li>
 *   <li>{@code google.buyer-redirect-uri} — the SECOND Authorized redirect URI
 *       on the same client, for buyer sign-in on {@code imin-public}:
 *       {@code https://app.imin.wtf/auth/callback/google}. Blank ⇒ the buyer
 *       Google endpoints 404 and only email + password is offered.</li>
 *   <li>{@code apple.client-id} — the Services ID identifier (the OAuth
 *       {@code client_id} / audience), e.g. {@code wtf.imin.web}.</li>
 *   <li>{@code apple.team-id} — the 10-char Apple Developer Team ID.</li>
 *   <li>{@code apple.key-id} — the Key ID of the Sign in with Apple {@code .p8}
 *       private key.</li>
 *   <li>{@code apple.private-key-base64} — base64 of the {@code .p8} file
 *       contents (env vars can't carry raw PEM newlines). Used to mint the
 *       ES256 client-secret JWT.</li>
 *   <li>{@code apple.redirect-uri} — where Apple {@code form_post}s the auth
 *       code. This is a BACKEND URL, e.g.
 *       {@code https://imin-api-production.up.railway.app/api/v1/auth/apple/return}.</li>
 *   <li>{@code frontend-base-url} — organizer dashboard origin the Apple return
 *       handler 302-redirects back to. Default {@code https://dashboard.imin.wtf}.</li>
 *   <li>{@code state-secret} — HMAC key for the stateless CSRF {@code state}
 *       token. Optional; when blank an ephemeral per-instance key is generated
 *       (fine for single-instance / dev, set it for multi-instance prod).</li>
 *   <li>{@code default-country} — ISO-3166 alpha-2 assigned to an Organization
 *       auto-provisioned on first OAuth sign-in (no country is collected in the
 *       social flow). The organizer can change it later in settings.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "imin.oauth")
public class OAuthProperties {

    private final Google google = new Google();
    private final Apple apple = new Apple();
    private String frontendBaseUrl = "https://dashboard.imin.wtf";
    private String stateSecret = "";
    private String defaultCountry = "NL";

    public Google getGoogle() { return google; }
    public Apple getApple() { return apple; }

    public String getFrontendBaseUrl() { return frontendBaseUrl; }
    public void setFrontendBaseUrl(String frontendBaseUrl) { this.frontendBaseUrl = frontendBaseUrl; }

    public String getStateSecret() { return stateSecret; }
    public void setStateSecret(String stateSecret) { this.stateSecret = stateSecret; }

    public String getDefaultCountry() { return defaultCountry; }
    public void setDefaultCountry(String defaultCountry) { this.defaultCountry = defaultCountry; }

    public static class Google {
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "";
        private String buyerRedirectUri = "";

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
        public String getBuyerRedirectUri() { return buyerRedirectUri; }
        public void setBuyerRedirectUri(String buyerRedirectUri) { this.buyerRedirectUri = buyerRedirectUri; }

        /** Enabled only when the client id, secret and redirect URI are all set. */
        public boolean isEnabled() {
            return notBlank(clientId) && notBlank(clientSecret) && notBlank(redirectUri);
        }

        /**
         * Buyer Google sign-in is gated <b>separately</b> from the organizer
         * one: it needs a second Authorized redirect URI on the same client
         * ({@code https://app.imin.wtf/auth/callback/google}), and until that is
         * registered in the Google Cloud console the buyer endpoints must 404
         * rather than send a buyer to a {@code redirect_uri_mismatch} page.
         * Setting {@code GOOGLE_OAUTH_BUYER_REDIRECT_URI} is therefore the
         * switch that turns the flow on, and it should only be set once the
         * console change has landed.
         */
        public boolean isBuyerEnabled() {
            return notBlank(clientId) && notBlank(clientSecret) && notBlank(buyerRedirectUri);
        }
    }

    public static class Apple {
        private String clientId = "";
        private String teamId = "";
        private String keyId = "";
        private String privateKeyBase64 = "";
        private String redirectUri = "";

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getTeamId() { return teamId; }
        public void setTeamId(String teamId) { this.teamId = teamId; }
        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }
        public String getPrivateKeyBase64() { return privateKeyBase64; }
        public void setPrivateKeyBase64(String privateKeyBase64) { this.privateKeyBase64 = privateKeyBase64; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

        /** Enabled only when every field needed to mint the client secret + exchange is set. */
        public boolean isEnabled() {
            return notBlank(clientId) && notBlank(teamId) && notBlank(keyId)
                    && notBlank(privateKeyBase64) && notBlank(redirectUri);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
