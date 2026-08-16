package com.imin.iminapi.service.ticket.google;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google save-link signing, driven with a REAL RSA keypair generated at test
 * time and verified with its matching public key.
 *
 * <p><b>Why not a stub signer.</b> A double that returns a canned string would
 * keep this file green through every mistake that actually matters: the wrong
 * algorithm, the wrong key, a claim set Google rejects, a payload serialised in
 * a way that breaks the signature. Google verifies the signature; so does this
 * test. That is the only version of this test worth having.
 */
class GoogleWalletJwtSignerTest {

    // ── signature ────────────────────────────────────────────────────────────

    @Test
    void theSignatureVerifiesWithTheServiceAccountsPublicKey() throws Exception {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        GoogleWalletJwtSigner signer = new GoogleWalletJwtSigner(
                GoogleServiceAccountKey.parse(keys.serviceAccountJson()));

        SignedJWT jwt = SignedJWT.parse(signer.sign(Map.of("eventTicketObjects", List.of())));

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.verify(new RSASSAVerifier(keys.publicKey())))
                .as("Google verifies this signature; if it fails here it fails there")
                .isTrue();
    }

    /**
     * A signature that does not actually cover the payload would verify above
     * and still let a tampered token through. Flip one byte of the claims
     * segment and the same verifier must reject it.
     */
    @Test
    void aTamperedPayloadNoLongerVerifies() throws Exception {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        GoogleWalletJwtSigner signer = new GoogleWalletJwtSigner(
                GoogleServiceAccountKey.parse(keys.serviceAccountJson()));

        String[] parts = signer.sign(
                Map.of("eventTicketObjects", List.of(Map.of("id", "3388.tkt_AAA")))).split("\\.");
        String tampered = parts[0] + "."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                                java.nio.charset.StandardCharsets.UTF_8)
                                .replace("tkt_AAA", "tkt_BBB")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThat(SignedJWT.parse(tampered).verify(new RSASSAVerifier(keys.publicKey())))
                .as("swapping the ticket id must invalidate the signature")
                .isFalse();
    }

    /**
     * A wrong key must not silently produce a JWT that "looks fine". This is the
     * negative that proves the positive above is not vacuous.
     */
    @Test
    void aSignatureFromADifferentKeyDoesNotVerify() throws Exception {
        GoogleTestKeys.Bundle a = GoogleTestKeys.generate();
        GoogleTestKeys.Bundle b = GoogleTestKeys.generate();
        var signer = new GoogleWalletJwtSigner(GoogleServiceAccountKey.parse(a.serviceAccountJson()));

        SignedJWT jwt = SignedJWT.parse(signer.sign(Map.of("eventTicketObjects", List.of())));
        assertThat(jwt.verify(new RSASSAVerifier(b.publicKey()))).isFalse();
    }

    // ── claim set ────────────────────────────────────────────────────────────

    @Test
    void theClaimSetIsWhatGoogleExpects() throws Exception {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        var signer = new GoogleWalletJwtSigner(GoogleServiceAccountKey.parse(keys.serviceAccountJson()));

        SignedJWT jwt = SignedJWT.parse(signer.sign(Map.of("eventTicketObjects", List.of())));
        var claims = jwt.getJWTClaimsSet();

        assertThat(claims.getIssuer()).isEqualTo(keys.clientEmail());
        assertThat(claims.getAudience()).containsExactly("google");
        assertThat(claims.getStringClaim("typ")).isEqualTo("savetowallet");
        assertThat(claims.getIssueTime()).isNotNull();
    }

    /**
     * {@code typ: JWT} in the JOSE HEADER — distinct from the
     * {@code typ: savetowallet} CLAIM above, which is the one Google's
     * save-to-wallet handler keys off. Nimbus leaves the header type null unless
     * asked; Google's own reference sample sets it, so we set it.
     */
    @Test
    void theJoseHeaderDeclaresTypeJwt() throws Exception {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        var signer = new GoogleWalletJwtSigner(GoogleServiceAccountKey.parse(keys.serviceAccountJson()));

        SignedJWT jwt = SignedJWT.parse(signer.sign(Map.of("eventTicketObjects", List.of())));
        assertThat(jwt.getHeader().getType()).isEqualTo(JOSEObjectType.JWT);
    }

    /**
     * Google's docs show {@code "aud": "google"} as a bare string, and Nimbus
     * only collapses a single-element audience to a string — a two-element list
     * would serialise as an array. Assert on the decoded JSON rather than on
     * {@code getAudience()}, which normalises both forms back to a list and so
     * cannot tell them apart.
     */
    @Test
    void theAudienceIsABareStringNotAnArray() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        var signer = new GoogleWalletJwtSigner(GoogleServiceAccountKey.parse(keys.serviceAccountJson()));

        String claimsJson = new String(java.util.Base64.getUrlDecoder().decode(
                signer.sign(Map.of("eventTicketObjects", List.of())).split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(claimsJson).contains("\"aud\":\"google\"");
        assertThat(claimsJson).doesNotContain("\"aud\":[");
    }

    /** The resource envelope has to survive the round trip intact — it is the pass. */
    @Test
    void thePayloadClaimCarriesTheResourceEnvelope() throws Exception {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        var signer = new GoogleWalletJwtSigner(GoogleServiceAccountKey.parse(keys.serviceAccountJson()));

        SignedJWT jwt = SignedJWT.parse(signer.sign(
                Map.of("eventTicketObjects", List.of(Map.of("id", "3388000000000000000.tkt_abc")))));

        assertThat(jwt.getJWTClaimsSet().getJSONObjectClaim("payload"))
                .extracting(p -> p.get("eventTicketObjects"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .singleElement()
                .isEqualTo(Map.of("id", "3388000000000000000.tkt_abc"));
    }

    /**
     * {@code origins} is a per-deployment value, so it comes from properties and
     * not from a constant. Configured ⇒ it is on the token.
     */
    @Test
    void configuredOriginsReachTheToken() throws Exception {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        var signer = new GoogleWalletJwtSigner(
                GoogleServiceAccountKey.parse(keys.serviceAccountJson()),
                List.of("https://app.imin.wtf"));

        SignedJWT jwt = SignedJWT.parse(signer.sign(Map.of("eventTicketObjects", List.of())));
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("origins"))
                .containsExactly("https://app.imin.wtf");
    }

    /**
     * Blank origins must OMIT the claim, not emit {@code "origins": []}. An
     * empty array is a defined restriction to nothing, which is a different and
     * worse thing than an absent field.
     */
    @Test
    void blankOriginsOmitTheClaimRatherThanSendingAnEmptyArray() throws Exception {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        var signer = new GoogleWalletJwtSigner(GoogleServiceAccountKey.parse(keys.serviceAccountJson()));

        SignedJWT jwt = SignedJWT.parse(signer.sign(Map.of("eventTicketObjects", List.of())));
        assertThat(jwt.getJWTClaimsSet().getClaims()).doesNotContainKey("origins");
    }

    // ── the gate ─────────────────────────────────────────────────────────────

    @Test
    void aMalformedServiceAccountJsonFailsLoudlyAtParseTime() {
        assertThatThrownBy(() -> GoogleServiceAccountKey.parse("{\"not\":\"a key\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Unset config must be "off", never "on with an empty key". */
    @Test
    void unconfiguredPropertiesAreNotFullyConfigured() {
        assertThat(new GoogleWalletProperties().fullyConfigured()).isFalse();
    }

    @Test
    void anIssuerIdWithoutAServiceAccountIsNotFullyConfigured() {
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setEnabled(true);
        p.setIssuerId("3388000000000000000");
        assertThat(p.fullyConfigured()).isFalse();
    }

    /**
     * The demo-mode guard, pinned. Credentials alone must NOT turn Google on:
     * there is a window where they are correct, the endpoint works, and the pass
     * reaches nobody but issuer test accounts. Flipping `enabled` is the
     * deliberate last step after publishing access is granted.
     */
    @Test
    void completeCredentialsAreStillOffUntilEnabledIsSetExplicitly() {
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setIssuerId("3388000000000000000");
        p.setServiceAccountJsonBase64("eyJ9");
        assertThat(p.fullyConfigured())
                .as("enabled defaults false for Google — see GoogleWalletProperties#enabled")
                .isFalse();
        p.setEnabled(true);
        assertThat(p.fullyConfigured()).isTrue();
    }

    /**
     * The Apple lesson, applied. {@code certPassword} sat in Apple's gate for
     * months, so a passwordless .p12 disabled the whole feature while the system
     * reported "not configured" — a true statement about a false cause. Every
     * closed state here has to name the value that closed it.
     */
    @Test
    void theClosedGateNamesTheReasonItIsClosed() {
        assertThat(new GoogleWalletProperties().gateReason())
                .get().asString()
                .containsIgnoringCase("GOOGLE_WALLET_ENABLED")
                .containsIgnoringCase("GOOGLE_WALLET_ISSUER_ID")
                .containsIgnoringCase("GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64");

        GoogleWalletProperties creds = new GoogleWalletProperties();
        creds.setIssuerId("3388000000000000000");
        creds.setServiceAccountJsonBase64("eyJ9");
        assertThat(creds.gateReason())
                .as("the demo-mode window is the interesting closed state — say so")
                .get().asString()
                .containsIgnoringCase("GOOGLE_WALLET_ENABLED")
                .doesNotContainIgnoringCase("GOOGLE_WALLET_ISSUER_ID");

        GoogleWalletProperties noKey = new GoogleWalletProperties();
        noKey.setEnabled(true);
        noKey.setIssuerId("3388000000000000000");
        assertThat(noKey.gateReason())
                .get().asString()
                .containsIgnoringCase("GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64")
                .doesNotContainIgnoringCase("GOOGLE_WALLET_ENABLED");

        GoogleWalletProperties open = new GoogleWalletProperties();
        open.setEnabled(true);
        open.setIssuerId("3388000000000000000");
        open.setServiceAccountJsonBase64("eyJ9");
        assertThat(open.gateReason()).isEmpty();
    }

    /**
     * Blank must mean "no origins", not "one origin that is the empty string".
     * Spring binds {@code origins: ${GOOGLE_WALLET_ORIGINS:}} through this
     * setter, and an empty-string element would ship an origin Google can never
     * match.
     */
    @Test
    void blankOriginsBindToAnEmptyList() {
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setOrigins(List.of(""));
        assertThat(p.getOrigins()).isEmpty();
        p.setOrigins(List.of(" https://app.imin.wtf ", "  "));
        assertThat(p.getOrigins()).containsExactly("https://app.imin.wtf");
        p.setOrigins(null);
        assertThat(p.getOrigins()).isEmpty();
    }

    // ── boot-time validation ─────────────────────────────────────────────────

    /**
     * The credential is checked when the bean is built, not when a buyer taps a
     * button — the same fix {@code WalletCredentialCheck} made on the Apple side
     * after a bad certificate was found to be indistinguishable from an
     * unconfigured one until the first 500.
     */
    @Test
    void aBrokenCredentialIsCaughtAtConstructionNotAtSignTime() {
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setEnabled(true);
        p.setIssuerId("3388000000000000000");
        p.setServiceAccountJsonBase64("bm90LWEta2V5"); // "not-a-key"

        GoogleWalletJwtSigner signer = new GoogleWalletJwtSigner(p);

        assertThat(signer.isUsable()).isFalse();
        assertThat(signer.unusableReason()).isPresent();
        assertThatThrownBy(() -> signer.sign(Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    /** A bad credential must not stop the app booting — passes are not checkout. */
    @Test
    void aBrokenCredentialDoesNotThrowOutOfTheConstructor() {
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setEnabled(true);
        p.setIssuerId("3388000000000000000");
        p.setServiceAccountJsonBase64("!!!not base64 at all!!!");
        assertThat(new GoogleWalletJwtSigner(p).isUsable()).isFalse();
    }

    @Test
    void anUnconfiguredSignerIsNotUsableAndReportsNoFault() {
        GoogleWalletJwtSigner signer = new GoogleWalletJwtSigner(new GoogleWalletProperties());
        assertThat(signer.isUsable()).isFalse();
        assertThat(signer.unusableReason())
                .as("off is not broken — an unset wallet must not look like an incident")
                .isEmpty();
    }

    @Test
    void aFullyConfiguredSignerBuiltFromPropertiesSignsVerifiably() throws Exception {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setEnabled(true);
        p.setIssuerId("3388000000000000000");
        p.setServiceAccountJsonBase64(keys.serviceAccountJsonBase64());
        p.setOrigins(List.of("https://app.imin.wtf"));

        GoogleWalletJwtSigner signer = new GoogleWalletJwtSigner(p);
        assertThat(signer.isUsable()).isTrue();

        SignedJWT jwt = SignedJWT.parse(signer.sign(Map.of("eventTicketObjects", List.of())));
        assertThat(jwt.verify(new RSASSAVerifier(keys.publicKey()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(keys.clientEmail());
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("origins"))
                .containsExactly("https://app.imin.wtf");
    }
}
