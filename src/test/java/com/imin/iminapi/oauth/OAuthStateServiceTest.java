package com.imin.iminapi.oauth;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class OAuthStateServiceTest {

    private OAuthStateService withSecret(String secret) {
        OAuthProperties props = new OAuthProperties();
        props.setStateSecret(secret);
        return new OAuthStateService(props);
    }

    @Test
    void signed_state_round_trips_for_same_provider() {
        OAuthStateService sut = withSecret("test-secret-key");
        String state = sut.sign("google");
        assertThatCode(() -> sut.verify(state, "google")).doesNotThrowAnyException();
    }

    @Test
    void state_for_one_provider_is_rejected_for_another() {
        OAuthStateService sut = withSecret("test-secret-key");
        String state = sut.sign("google");
        assertThatThrownBy(() -> sut.verify(state, "apple"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_INVALID_STATE);
    }

    @Test
    void tampered_state_is_rejected() {
        OAuthStateService sut = withSecret("test-secret-key");
        String state = sut.sign("google");
        String tampered = state.substring(0, state.length() - 2) + (state.endsWith("aa") ? "bb" : "aa");
        assertThatThrownBy(() -> sut.verify(tampered, "google"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_INVALID_STATE);
    }

    @Test
    void state_signed_with_a_different_secret_does_not_verify() {
        String state = withSecret("secret-A").sign("google");
        assertThatThrownBy(() -> withSecret("secret-B").verify(state, "google"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_INVALID_STATE);
    }

    @Test
    void null_or_garbage_state_is_rejected() {
        OAuthStateService sut = withSecret("test-secret-key");
        assertThatThrownBy(() -> sut.verify(null, "google")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sut.verify("not-a-valid-state", "google")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sut.verify("a.b", "google")).isInstanceOf(ApiException.class);
    }

    // ── Audience (buyer-accounts epic §2.4) ────────────────────────────────

    @Test
    void a_buyer_state_is_rejected_by_the_organizer_verifier() {
        // THE dangerous direction: an organizer-side verify that accepted this
        // would let a buyer's code+state reach OAuthAccountService.resolve step
        // 5, which auto-provisions an Organization and an OWNER user.
        OAuthStateService sut = withSecret("test-secret-key");
        String buyerState = sut.sign("google", OAuthStateService.AUDIENCE_BUYER, "browser-nonce");

        assertThatThrownBy(() -> sut.verify(buyerState, "google"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_INVALID_STATE);
    }

    @Test
    void an_organizer_state_is_rejected_by_the_buyer_verifier() {
        OAuthStateService sut = withSecret("test-secret-key");
        String organizerState = sut.sign("google");

        assertThatThrownBy(() -> sut.verify(
                organizerState, "google", OAuthStateService.AUDIENCE_BUYER, "browser-nonce"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_INVALID_STATE);
    }

    @Test
    void a_buyer_state_round_trips_with_its_own_nonce() {
        OAuthStateService sut = withSecret("test-secret-key");
        String nonce = sut.newBrowserNonce();
        String state = sut.sign("google", OAuthStateService.AUDIENCE_BUYER, nonce);

        assertThatCode(() -> sut.verify(state, "google", OAuthStateService.AUDIENCE_BUYER, nonce))
                .doesNotThrowAnyException();
    }

    // ── Browser binding (§2.4) ─────────────────────────────────────────────

    @Test
    void a_buyer_state_is_rejected_when_the_nonce_cookie_is_missing() {
        // Login CSRF: the attacker completes a Google authorization and hands
        // the victim the callback URL. The victim's browser has no nonce cookie.
        OAuthStateService sut = withSecret("test-secret-key");
        String state = sut.sign("google", OAuthStateService.AUDIENCE_BUYER, sut.newBrowserNonce());

        assertThatThrownBy(() -> sut.verify(state, "google", OAuthStateService.AUDIENCE_BUYER, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_INVALID_STATE);
    }

    @Test
    void a_buyer_state_is_rejected_when_the_nonce_cookie_belongs_to_another_flow() {
        OAuthStateService sut = withSecret("test-secret-key");
        String state = sut.sign("google", OAuthStateService.AUDIENCE_BUYER, sut.newBrowserNonce());

        assertThatThrownBy(() -> sut.verify(
                state, "google", OAuthStateService.AUDIENCE_BUYER, sut.newBrowserNonce()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_INVALID_STATE);
    }

    @Test
    void an_unbound_buyer_state_is_refused_even_though_it_is_correctly_signed() {
        // Binding is mandatory for the buyer audience, so a state minted without
        // one cannot be used even by whoever minted it.
        OAuthStateService sut = withSecret("test-secret-key");
        String unbound = sut.sign("google", OAuthStateService.AUDIENCE_BUYER, null);

        assertThatThrownBy(() -> sut.verify(unbound, "google", OAuthStateService.AUDIENCE_BUYER, "anything"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_INVALID_STATE);
    }

    // ── One-release compatibility branch ───────────────────────────────────

    @Test
    void a_pre_epic_three_field_state_still_verifies_as_organizer() {
        // Without this branch, every organizer mid-login during the deploy that
        // adds the audience field fails their sign-in. Delete the branch and
        // this test together, after 2026-09-12.
        OAuthStateService sut = withSecret("test-secret-key");
        String legacy = legacyState(sut, "google", Instant.now().plusSeconds(300).getEpochSecond());

        assertThatCode(() -> sut.verify(legacy, "google")).doesNotThrowAnyException();
    }

    @Test
    void a_pre_epic_state_is_still_refused_by_the_buyer_verifier() {
        // The compat branch must not become a bypass: a legacy state reads as
        // organizer, which the buyer side rejects like any other wrong audience.
        OAuthStateService sut = withSecret("test-secret-key");
        String legacy = legacyState(sut, "google", Instant.now().plusSeconds(300).getEpochSecond());

        assertThatThrownBy(() -> sut.verify(legacy, "google", OAuthStateService.AUDIENCE_BUYER, "nonce"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_INVALID_STATE);
    }

    @Test
    void an_expired_state_is_rejected() {
        OAuthStateService sut = withSecret("test-secret-key");
        String expired = legacyState(sut, "google", Instant.now().minusSeconds(60).getEpochSecond());

        assertThatThrownBy(() -> sut.verify(expired, "google"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_INVALID_STATE);
    }

    /**
     * Hand-builds the pre-epic {@code provider:exp:nonce} payload and signs it
     * with the service's own key via reflection on the private {@code hmac}
     * method — the only way to produce a state in a layout the code can no
     * longer mint.
     */
    private static String legacyState(OAuthStateService sut, String provider, long exp) {
        String payload = provider + ":" + exp + ":" + b64("nonce".getBytes(StandardCharsets.UTF_8));
        try {
            var m = OAuthStateService.class.getDeclaredMethod("hmac", String.class);
            m.setAccessible(true);
            byte[] sig = (byte[]) m.invoke(sut, payload);
            return b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + b64(sig);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
