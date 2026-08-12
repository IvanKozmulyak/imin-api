package com.imin.iminapi.oauth;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * <b>Regression net for the ORGANIZER Google sign-in flow.</b>
 *
 * <p>Written before {@link GoogleOAuthService} was parameterised for a second
 * audience (buyer accounts, epic §2.4). The epic claimed that refactor was
 * "covered by the existing organizer OAuth tests"; it was not — before this file
 * a grep over {@code src/test} returned zero references to
 * {@code GoogleOAuthService}, {@code buildAuthorizeUrl}, any exchange method or
 * {@code redirectUri}, and {@code OAuthControllerTest} exercises only the
 * config-disabled paths. Parameterising this class is the single change the
 * buyer epic makes to live organizer sign-in, so it gets a net first.
 *
 * <p>What this pins, and why each one would be a production outage:
 *
 * <ul>
 *   <li><b>The authorize URL carries the ORGANIZER redirect URI</b>
 *       ({@code imin.oauth.google.redirect-uri}). Send the buyer one and every
 *       organizer sign-in dies at Google with {@code redirect_uri_mismatch}.</li>
 *   <li><b>The token exchange posts the SAME redirect URI</b> it authorized
 *       with. Google requires the two to match; a mismatch is
 *       {@code invalid_grant} <i>after</i> the user has already consented, which
 *       is the worst place to fail.</li>
 *   <li><b>The authorize URL's other parameters</b> — scopes, response type,
 *       {@code access_type}, {@code prompt}. A dropped {@code email} scope makes
 *       {@code OAuthAccountService.resolve} reject every sign-in at step 2.</li>
 *   <li><b>{@code state} is verified BEFORE any network call.</b> That ordering
 *       is the CSRF defence: a forged or foreign-provider state must never reach
 *       Google's token endpoint, let alone {@code OAuthAccountService.resolve}
 *       step 5, which auto-provisions an Organization.</li>
 *   <li><b>The state this class mints is the state it accepts</b> — a
 *       round-trip, so the two sides cannot drift apart when the state layout
 *       gains an audience field.</li>
 * </ul>
 *
 * <p>No Google network is involved: the {@link RestClient} is bound to a
 * {@link MockRestServiceServer}, and the token response deliberately omits
 * {@code id_token} so the flow stops before JWKS verification — by which point
 * every assertion this test cares about has already been made.
 */
class GoogleOAuthServiceTest {

    private static final String CLIENT_ID = "organizer-client-id.apps.googleusercontent.com";
    private static final String CLIENT_SECRET = "organizer-client-secret";
    private static final String ORGANIZER_REDIRECT = "https://dashboard.imin.wtf/auth/callback/google";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    private OAuthProperties props;
    private OAuthStateService stateService;
    private MockRestServiceServer server;
    private GoogleOAuthService google;

    @BeforeEach
    void setUp() {
        props = new OAuthProperties();
        props.setStateSecret("regression-test-state-secret");
        props.getGoogle().setClientId(CLIENT_ID);
        props.getGoogle().setClientSecret(CLIENT_SECRET);
        props.getGoogle().setRedirectUri(ORGANIZER_REDIRECT);

        stateService = new OAuthStateService(props);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        google = new GoogleOAuthService(props, stateService, builder.build());
    }

    // ── The authorize URL ──────────────────────────────────────────────────

    @Test
    void authorize_url_carries_the_organizer_redirect_uri_and_the_full_scope_set() {
        Map<String, String> q = queryOf(google.buildAuthorizeUrl());

        assertThat(q.get("redirect_uri"))
                .as("organizer sign-in must authorize against imin.oauth.google.redirect-uri")
                .isEqualTo(ORGANIZER_REDIRECT);
        assertThat(q.get("client_id")).isEqualTo(CLIENT_ID);
        assertThat(q.get("response_type")).isEqualTo("code");
        assertThat(q.get("scope"))
                .as("dropping `email` makes OAuthAccountService.resolve reject every sign-in")
                .isEqualTo("openid email profile");
        assertThat(q.get("access_type")).isEqualTo("online");
        assertThat(q.get("prompt")).isEqualTo("select_account");
        assertThat(q.get("state")).isNotBlank();
    }

    @Test
    void authorize_url_points_at_googles_authorize_endpoint() {
        assertThat(google.buildAuthorizeUrl())
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
    }

    @Test
    void the_state_it_mints_is_the_state_it_accepts() {
        String state = queryOf(google.buildAuthorizeUrl()).get("state");

        // A full round-trip through the real exchange path: if state signing and
        // state verification ever drift apart, this fails before the HTTP call.
        expectTokenCall();
        assertThatThrownBy(() -> google.exchange("auth-code", state))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        server.verify();
    }

    // ── The token exchange ─────────────────────────────────────────────────

    @Test
    void exchange_posts_the_same_redirect_uri_it_authorized_with() {
        String state = queryOf(google.buildAuthorizeUrl()).get("state");

        MultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("code", "auth-code");
        expected.add("client_id", CLIENT_ID);
        expected.add("client_secret", CLIENT_SECRET);
        // Google requires redirect_uri here to equal the one used to authorize.
        expected.add("redirect_uri", ORGANIZER_REDIRECT);
        expected.add("grant_type", "authorization_code");

        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // "{}" carries no id_token, so the flow stops right after the exchange —
        // after every assertion above has run, and before any JWKS fetch.
        assertThatThrownBy(() -> google.exchange("auth-code", state))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("did not return an ID token");

        server.verify();
    }

    // ── state is the CSRF gate, and it runs before the network ─────────────

    @Test
    void a_tampered_state_is_rejected_without_ever_calling_google() {
        // No expectations registered: any outbound call fails the test outright.
        assertThatThrownBy(() -> google.exchange("auth-code", "not-a-real-state"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.OAUTH_INVALID_STATE);

        server.verify();
    }

    @Test
    void a_state_signed_for_another_provider_is_rejected_without_calling_google() {
        String appleState = stateService.sign("apple");

        assertThatThrownBy(() -> google.exchange("auth-code", appleState))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.OAUTH_INVALID_STATE);

        server.verify();
    }

    @Test
    void a_null_state_is_rejected_without_calling_google() {
        assertThatThrownBy(() -> google.exchange("auth-code", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.OAUTH_INVALID_STATE);

        server.verify();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void expectTokenCall() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    }

    /** Decoded query parameters of an authorize URL. */
    private static Map<String, String> queryOf(String url) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap()
                .entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> java.net.URLDecoder.decode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8)));
    }
}
