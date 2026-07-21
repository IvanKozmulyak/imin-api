package com.imin.iminapi.oauth;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

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
}
