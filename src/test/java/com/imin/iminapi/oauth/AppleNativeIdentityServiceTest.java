package com.imin.iminapi.oauth;

import com.imin.iminapi.security.ApiException;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code email_verified} gate, which is the entire reason this class exists
 * separately from {@link AppleOAuthService} — that one hardcodes {@code true} at
 * its line 131, and inheriting it would make the buyer-side gate a permanent
 * no-op.
 *
 * <p>{@code BuyerNativeAppleSignInTest} cannot cover this: it replaces the
 * service with a mock whose stubs return {@code emailVerified = true}, so a
 * {@code verify} implemented as {@code new OAuthUserInfo(…, true, …)} would
 * leave every one of those tests green. The false and absent cases below are the
 * ones that fail when someone takes that shortcut, and they are the point of the
 * file.
 */
class AppleNativeIdentityServiceTest {

    private OidcJwtVerifier jwt;
    private AppleNativeIdentityService service;

    @BeforeEach
    void setUp() {
        OAuthProperties props = new OAuthProperties();
        props.getApple().setNativeAudience("wtf.imin.fan");
        jwt = mock(OidcJwtVerifier.class);
        service = new AppleNativeIdentityService(props);
        service.setVerifierForTest(jwt);
    }

    @Test
    void booleanTrueIsVerified() {
        stub(Map.of("sub", "apple-1", "email", "a@b.test", "email_verified", true));
        assertThat(service.verify("tok", null).emailVerified()).isTrue();
    }

    /** Apple emits the string form on some tokens. Both must be accepted. */
    @Test
    void stringTrueIsAlsoVerified() {
        stub(Map.of("sub", "apple-2", "email", "a@b.test", "email_verified", "true"));
        assertThat(service.verify("tok", null).emailVerified()).isTrue();
    }

    @Test
    void booleanFalseIsNotVerified() {
        stub(Map.of("sub", "apple-3", "email", "a@b.test", "email_verified", false));
        assertThat(service.verify("tok", null).emailVerified()).isFalse();
    }

    /** No claim means no assertion — never assume true. */
    @Test
    void absentClaimIsNotVerified() {
        stub(Map.of("sub", "apple-4", "email", "a@b.test"));
        assertThat(service.verify("tok", null).emailVerified()).isFalse();
    }

    @Test
    void aTokenWithNoSubjectIsRejected() {
        stub(Map.of("email", "a@b.test", "email_verified", true));
        assertThatThrownBy(() -> service.verify("tok", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aBlankIdTokenIsRejectedBeforeAnyVerification() {
        assertThatThrownBy(() -> service.verify("   ", null))
                .isInstanceOf(ApiException.class);
    }

    /** The subject and the address are carried through exactly as claimed. */
    @Test
    void theSubjectAndRelayAddressAreCarriedThrough() {
        stub(Map.of("sub", "apple-6", "email", "relay@privaterelay.appleid.com",
                "email_verified", true));
        OAuthUserInfo info = service.verify("tok", null);
        assertThat(info.provider()).isEqualTo("apple");
        assertThat(info.subject()).isEqualTo("apple-6");
        assertThat(info.email()).isEqualTo("relay@privaterelay.appleid.com");
    }

    /** Apple returns the name once, on first authorization, out of band. */
    @Test
    void theDisplayNameComesFromTheParameterNotTheToken() {
        stub(Map.of("sub", "apple-5", "email_verified", true));
        assertThat(service.verify("tok", "  Sofiya K  ").displayName()).isEqualTo("Sofiya K");
        assertThat(service.verify("tok", "   ").displayName()).isNull();
        assertThat(service.verify("tok", null).displayName()).isNull();
    }

    @Test
    void blankAudienceDisablesTheProvider() {
        OAuthProperties blank = new OAuthProperties();
        assertThat(new AppleNativeIdentityService(blank).enabled()).isFalse();
    }

    @Test
    void aConfiguredAudienceEnablesTheProvider() {
        assertThat(service.enabled()).isTrue();
    }

    private void stub(Map<String, Object> claims) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder();
        new HashMap<>(claims).forEach((k, v) -> {
            if ("sub".equals(k)) b.subject(String.valueOf(v)); else b.claim(k, v);
        });
        when(jwt.verify(anyString())).thenReturn(b.build());
    }
}
