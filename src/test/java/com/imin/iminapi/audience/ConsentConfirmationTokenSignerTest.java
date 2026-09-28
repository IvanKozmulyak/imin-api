package com.imin.iminapi.audience;

import com.imin.iminapi.audience.service.ConsentConfirmationTokenSigner;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentConfirmationTokenSignerTest {

    private static final String KEY = "own-consent-confirmation-key-32-bytes-zz";
    private static final String FALLBACK = "ticket-signing-secret-32-bytes-zzzzzzzz";
    private static final UUID ID = UUID.fromString("0f0f0f0f-1111-2222-3333-444455556666");

    private final ConsentConfirmationTokenSigner signer = new ConsentConfirmationTokenSigner(KEY, FALLBACK, false);

    @Test
    void signedToken_verifiesToItsRowId() {
        assertThat(signer.verify(signer.sign(ID))).contains(ID);
    }

    @Test
    void tokenIsUrlSafe() {
        assertThat(signer.sign(ID)).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    }

    @Test
    void tamperedPayload_isRefused() {
        String token = signer.sign(ID);
        String other = signer.sign(UUID.randomUUID());
        String swapped = other.substring(0, other.indexOf('.')) + token.substring(token.indexOf('.'));
        assertThat(signer.verify(swapped)).isEmpty();
    }

    @Test
    void tamperedSignature_isRefused() {
        String token = signer.sign(ID);
        char last = token.charAt(token.length() - 1);
        String flipped = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThat(signer.verify(flipped)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherKey_isRefused() {
        ConsentConfirmationTokenSigner other = new ConsentConfirmationTokenSigner("some-other-key-entirely-32-bytes-zzzz", FALLBACK, false);
        assertThat(signer.verify(other.sign(ID))).isEmpty();
    }

    @Test
    void blankOwnKey_signsWithTheFallback() {
        ConsentConfirmationTokenSigner fallbackOnly = new ConsentConfirmationTokenSigner("  ", FALLBACK, false);
        ConsentConfirmationTokenSigner sameFallback = new ConsentConfirmationTokenSigner("", FALLBACK, false);
        assertThat(sameFallback.verify(fallbackOnly.sign(ID))).contains(ID);
        assertThat(signer.verify(fallbackOnly.sign(ID))).isEmpty();
    }

    @Test
    void anotherKindOfTokenSignedWithTheSameKey_isRefused() {
        // Same key, payload without this signer's prefix: the notify opt-out token.
        UnsubscribeTokenService unsubscribe = new UnsubscribeTokenService(KEY, KEY);
        assertThat(signer.verify(unsubscribe.signNotify(ID))).isEmpty();
    }

    @Test
    void wellSignedPayloadThatIsNotAUuid_isRefused() throws Exception {
        String p = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("consent-confirm:v1:not-a-uuid".getBytes(StandardCharsets.UTF_8));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(p.getBytes(StandardCharsets.UTF_8)));
        assertThat(signer.verify(p + "." + sig)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "nodot", ".sig", "payload.", "a.b.c", "!!!.???"})
    void malformed_isRefused(String token) {
        assertThat(signer.verify(token)).isEmpty();
    }

    @Test
    void overlongToken_isRefused() {
        assertThat(signer.verify("a".repeat(600) + ".b")).isEmpty();
    }

    // ── emails on require our own key ─────────────────────────────────────

    @Test
    void emailsOn_withoutOwnKey_refusesToStart() {
        assertThatThrownBy(() -> new ConsentConfirmationTokenSigner(" ", FALLBACK, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IMIN_CONSENT_CONFIRMATION_SECRET");
        assertThatThrownBy(() -> new ConsentConfirmationTokenSigner(null, FALLBACK, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emailsOn_withOwnKey_signsWithIt_notTheFallback() {
        ConsentConfirmationTokenSigner on = new ConsentConfirmationTokenSigner(KEY, FALLBACK, true);
        assertThat(signer.verify(on.sign(ID))).contains(ID);
        assertThat(new ConsentConfirmationTokenSigner("", FALLBACK, false).verify(on.sign(ID))).isEmpty();
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ConsentConfirmationTokenSigner.class);

    @Test
    void binding_emailsOnWithoutSecret_failsTheContext() {
        runner.withPropertyValues("imin.audience-plan.consent-confirmation-emails-enabled=true",
                        "imin.ticket.signing-secret=" + FALLBACK)
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("IMIN_CONSENT_CONFIRMATION_SECRET"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @ValueSource(strings = {"1", "yes", "on", "TRUE"})
    void binding_anyValueSpringReadsAsTrue_withoutSecret_failsTheContext(String on) {
        runner.withPropertyValues("imin.audience-plan.consent-confirmation-emails-enabled=" + on,
                        "imin.consent-confirmation.secret=",
                        "imin.ticket.signing-secret=" + FALLBACK)
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("IMIN_CONSENT_CONFIRMATION_SECRET"));
    }

    @Test
    void binding_emailsOnWithSecret_starts() {
        runner.withPropertyValues("imin.audience-plan.consent-confirmation-emails-enabled=true",
                        "imin.consent-confirmation.secret=" + KEY)
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ConsentConfirmationTokenSigner.class));
    }

    @Test
    void binding_emailsOffByDefault_startsOnTheFallback() {
        runner.withPropertyValues("imin.ticket.signing-secret=" + FALLBACK)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    String token = ctx.getBean(ConsentConfirmationTokenSigner.class).sign(ID);
                    assertThat(new ConsentConfirmationTokenSigner("", FALLBACK, false).verify(token)).contains(ID);
                });
    }
}
