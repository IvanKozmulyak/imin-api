package com.imin.iminapi.service.ticket.google;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing a Google service-account key, and — the part that matters more — the
 * proof that the private key inside it cannot reach a log line, an exception
 * message or a {@code toString()}.
 *
 * <p><b>Why this is a test and not a code review note.</b> The leak paths here
 * are all "someone appends the wrong variable to a string", and they are
 * invisible in review because the variable is usually a field on an object
 * rather than the key itself. {@code sun.security.rsa.RSAPrivateCrtKeyImpl} on
 * some JDKs printed the modulus and the private exponent from
 * {@code toString()}; on this one (17.0.20) it does not, but "the JDK currently
 * happens not to" is not a control.
 *
 * <p><b>This paragraph used to over-claim, and an audit caught it.</b> It said
 * the assertions "hold regardless of which JDK is underneath, because they hunt
 * for the key's own bytes" — but they hunted only for the <em>base64</em> of
 * those bytes, which is one encoding among several. Two consequences, both
 * measured by mutation rather than argued: writing
 * {@code "privateKey=" + privateKey} into {@link
 * GoogleServiceAccountKey#toString()} left the whole suite <b>green</b> on this
 * JDK, and a provider that printed the modulus would have gone unnoticed too.
 * Closed from both ends — {@link #toStringRedactsThePrivateKey} now asserts the
 * redaction marker itself, so any delegation to the key fails on every JDK, and
 * {@link #assertNoKeyMaterial} now hunts the key's arithmetic components in
 * decimal and hex as well as its base64.
 */
class GoogleServiceAccountKeyTest {

    // ── the happy path ───────────────────────────────────────────────────────

    @Test
    void parsesTheClientEmailAndTheRsaPrivateKey() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        GoogleServiceAccountKey key = GoogleServiceAccountKey.parse(keys.serviceAccountJson());

        assertThat(key.clientEmail()).isEqualTo(keys.clientEmail());
        assertThat(key.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(key.privateKey().getModulus()).isEqualTo(keys.publicKey().getModulus());
    }

    @Test
    void parsesTheSameKeyFromTheBase64FormTheEnvVarCarries() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        assertThat(GoogleServiceAccountKey.parseBase64(keys.serviceAccountJsonBase64()).clientEmail())
                .isEqualTo(keys.clientEmail());
    }

    /**
     * Dashboard fields wrap long values. A base64 blob split across lines is the
     * overwhelmingly likely paste, and rejecting it would look exactly like a
     * bad key.
     */
    @Test
    void toleratesLineBreaksInsideTheBase64Blob() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        String wrapped = keys.serviceAccountJsonBase64().replaceAll("(.{64})", "$1\n");
        assertThat(GoogleServiceAccountKey.parseBase64(wrapped).clientEmail())
                .isEqualTo(keys.clientEmail());
    }

    /**
     * The operator who pastes the raw JSON instead of base64 has done something
     * reasonable. Accept it rather than reporting a corrupt credential — a
     * MIME decoder would otherwise silently eat the braces and quotes and
     * produce a garbage blob with a misleading error.
     */
    @Test
    void acceptsRawJsonPastedWhereBase64WasExpected() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        assertThat(GoogleServiceAccountKey.parseBase64("  " + keys.serviceAccountJson())
                .clientEmail()).isEqualTo(keys.clientEmail());
    }

    // ── failing loudly, and by name ──────────────────────────────────────────

    @Test
    void aMissingClientEmailNamesTheMissingField() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        String json = keys.serviceAccountJson().replace("client_email", "client_e_mail");
        assertThatThrownBy(() -> GoogleServiceAccountKey.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client_email");
    }

    @Test
    void aMissingPrivateKeyNamesTheMissingField() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        String json = keys.serviceAccountJson().replace("\"private_key\"", "\"privateKey\"");
        assertThatThrownBy(() -> GoogleServiceAccountKey.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private_key");
    }

    @Test
    void somethingThatIsNotJsonAtAllFails() {
        assertThatThrownBy(() -> GoogleServiceAccountKey.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A PKCS#1 key ("BEGIN RSA PRIVATE KEY") is not what Google issues, and
     * {@code KeyFactory} cannot read one — it fails with an opaque
     * {@code InvalidKeySpecException}. Name the actual problem instead.
     */
    @Test
    void aPkcs1KeySaysSoRatherThanFailingOpaquely() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\n"
                + keys.privateKeyPemBody() + "\n-----END RSA PRIVATE KEY-----\n";
        assertThatThrownBy(() -> GoogleServiceAccountKey.parse(
                GoogleTestKeys.serviceAccountJson(keys.clientEmail(), pkcs1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PKCS#8");
    }

    // ── the private key does not escape ──────────────────────────────────────

    /**
     * {@code toString()} is the single most likely accidental leak: the object
     * ends up in a log format argument, an exception message, or a debugger
     * dump of a bean, and nobody notices because the field is called "key".
     *
     * <p><b>Two assertions, and the first one is the one that bites on this
     * JDK.</b> The redaction marker is asserted literally, because the control
     * being tested is "the key object is never rendered at all" — and that is
     * not observable from the output on a JDK where {@code RSAPrivateKey}'s own
     * {@code toString()} prints an identity hash. Written the obvious way, i.e.
     * hunting only for the key's bytes, this test stays <b>green</b> against
     * {@code "privateKey=" + privateKey} on JDK 17 — verified by mutation, and
     * it is precisely the false confidence the class javadoc warns about ("the
     * JDK currently happens not to" is not a control). {@link
     * #assertNoKeyMaterial} then covers the other direction: a provider that
     * <i>does</i> print the secret, in whatever base it chooses.
     */
    @Test
    void toStringRedactsThePrivateKey() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        String rendered = GoogleServiceAccountKey.parse(keys.serviceAccountJson()).toString();

        assertThat(rendered).contains(keys.clientEmail());
        assertThat(rendered)
                .as("the key must be replaced, not merely rendered by a JDK that "
                        + "happens to be discreet — this is the assertion that fails "
                        + "when someone writes \"privateKey=\" + privateKey")
                .contains("privateKey=<redacted>");
        assertNoKeyMaterial(rendered, keys);
    }

    /**
     * Every layer that can choke on a credential, walked end to end, asserting
     * that neither the thrown message chain nor a successfully parsed object
     * carries key material. Each input below embeds a real 2048-bit private key,
     * so any layer that echoed what it could not parse would be caught here.
     *
     * <p>Deliberately does <b>not</b> require every input to throw. Two of them
     * legitimately succeed — the MIME decoder ignores stray characters, which is
     * the same leniency that makes a line-wrapped paste work — and pinning
     * "this must fail" would be pinning an accident rather than the property
     * under test.
     */
    @Test
    void noParsePathEchoesKeyMaterialWhetherItFailsOrSucceeds() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + keys.privateKeyPemBody()
                + "\n-----END PRIVATE KEY-----\n";
        String json = keys.serviceAccountJson();

        List<java.util.function.Supplier<GoogleServiceAccountKey>> paths = List.of(
                // A key with a corrupt DER body, still wearing correct armour.
                () -> GoogleServiceAccountKey.parse(GoogleTestKeys.serviceAccountJson(
                        keys.clientEmail(),
                        "-----BEGIN PRIVATE KEY-----\n"
                                + keys.privateKeyPemBody().substring(40)
                                + "\n-----END PRIVATE KEY-----\n")),
                // Correct key, wrong format (PKCS#1).
                () -> GoogleServiceAccountKey.parse(GoogleTestKeys.serviceAccountJson(
                        keys.clientEmail(), "-----BEGIN RSA PRIVATE KEY-----\n"
                                + keys.privateKeyPemBody() + "\n-----END RSA PRIVATE KEY-----\n")),
                // Correct key, junk in front of the armour.
                () -> GoogleServiceAccountKey.parse(
                        GoogleTestKeys.serviceAccountJson(keys.clientEmail(), "@@@" + pem)),
                // Truncated JSON — the parser sees the key and then runs out.
                () -> GoogleServiceAccountKey.parse(json.substring(0, json.length() - 20)),
                // Base64 whose length cannot be a whole number of 4-char groups:
                // the wrapper itself fails, with the secret as its input.
                () -> GoogleServiceAccountKey.parseBase64(keys.serviceAccountJsonBase64() + "A"),
                // Stray characters around a good blob — succeeds, by design.
                () -> GoogleServiceAccountKey.parseBase64("!!" + keys.serviceAccountJsonBase64() + "!!"),
                // Base64 of something that is not a key document at all.
                () -> GoogleServiceAccountKey.parseBase64(Base64.getEncoder()
                        .encodeToString(pem.getBytes(StandardCharsets.UTF_8))));

        for (java.util.function.Supplier<GoogleServiceAccountKey> path : paths) {
            GoogleServiceAccountKey[] out = new GoogleServiceAccountKey[1];
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> out[0] = path.get());
            if (thrown != null) {
                assertThat(thrown)
                        .as("a bad credential must fail as a plain, reportable argument error")
                        .isInstanceOf(IllegalArgumentException.class);
                assertNoKeyMaterial(chainText(thrown), keys);
            } else {
                assertNoKeyMaterial(String.valueOf(out[0]), keys);
            }
        }
    }

    /**
     * The same guarantee at the boot seam: the reason string the signer logs
     * when the credential will not load is built from these messages, so it
     * inherits the property above — but the wiring is what actually ships, so
     * assert on the wiring.
     */
    @Test
    void theBootTimeFailureReasonCarriesNoKeyMaterial() {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setEnabled(true);
        p.setIssuerId("3388000000000000000");
        // A real key document with a broken PEM body: the failure path that
        // most tempts an implementation to print what it could not parse.
        p.setServiceAccountJsonBase64(Base64.getEncoder().encodeToString(
                GoogleTestKeys.serviceAccountJson(keys.clientEmail(),
                                "-----BEGIN PRIVATE KEY-----\n"
                                        + keys.privateKeyPemBody().substring(40)
                                        + "\n-----END PRIVATE KEY-----\n")
                        .getBytes(StandardCharsets.UTF_8)));

        GoogleWalletJwtSigner signer = new GoogleWalletJwtSigner(p);
        assertThat(signer.isUsable()).isFalse();
        assertNoKeyMaterial(signer.unusableReason().orElseThrow(), keys);
    }

    /**
     * The signed token is a bearer artifact that mints a pass, so it must not be
     * in an exception message either. Sign, then force a failure and check.
     */
    @Test
    void refusingToSignWithoutACredentialDoesNotLeakTheCredentialOrAToken() {
        GoogleWalletProperties p = new GoogleWalletProperties();
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> new GoogleWalletJwtSigner(p).sign(Map.of("eventTicketObjects", List.of())));
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(chainText(thrown)).doesNotContain("eyJ"); // no JWT segment
    }

    /**
     * <b>The secret, in every rendering — not just the one this repo happens to
     * carry it in.</b>
     *
     * <p>The base64 needles below only find a leak that reproduces the PEM
     * encoding. The stated threat is broader and is written on the class under
     * test: a JDK or JCA provider whose {@code RSAPrivateKey.toString()} prints
     * the modulus and the private exponent — which is what earlier JDKs'
     * {@code sun.security.rsa.RSAPrivateCrtKeyImpl} did, and which nothing
     * outside this repo's control promises not to do again. Such a leak carries
     * the same secret in <em>decimal</em>, and a test that knows only the base64
     * form reads it as clean. So the key's own arithmetic components are needles
     * too, in decimal and in both hex cases, along with the DER as hex.
     *
     * <p>The modulus is not itself secret — it is in the public key — and it is
     * here anyway, deliberately: it is the tripwire. Nothing legitimate renders
     * it, so its appearance means a key object was printed, and whatever printed
     * the modulus printed the private exponent on the next line.
     *
     * <p>No false positives to worry about: the shortest needle here is the hex
     * of a 1024-bit prime — 256 characters of an integer minted seconds ago.
     */
    private static void assertNoKeyMaterial(String text, GoogleTestKeys.Bundle keys) {
        String body = keys.privateKeyPemBody();
        assertThat(text)
                .as("the whole private key must not appear")
                .doesNotContain(body)
                .as("nor any recognisable run of it")
                .doesNotContain(body.substring(0, 32))
                .doesNotContain(body.substring(body.length() - 32))
                .as("nor the PEM armour, which is only ever adjacent to key bytes")
                .doesNotContain("PRIVATE KEY-----");

        RSAPrivateKey k = keys.privateKey();
        List<BigInteger> components = new ArrayList<>(
                List.of(k.getModulus(), k.getPrivateExponent()));
        if (k instanceof RSAPrivateCrtKey crt) {
            // The CRT factors reconstruct the key on their own, so a rendering
            // that omitted the private exponent would still be a total loss.
            components.addAll(List.of(crt.getPrimeP(), crt.getPrimeQ(),
                    crt.getPrimeExponentP(), crt.getPrimeExponentQ(), crt.getCrtCoefficient()));
        }
        for (BigInteger c : components) {
            assertThat(text)
                    .as("a key component must not appear in any base a provider might "
                            + "print it in — this is the assertion that survives a JDK "
                            + "whose RSAPrivateKey.toString() is not discreet")
                    .doesNotContain(c.toString())
                    .doesNotContain(c.toString(16))
                    .doesNotContain(c.toString(16).toUpperCase(Locale.ROOT));
        }
        assertThat(text)
                .as("nor the encoded form rendered as hex rather than base64")
                .doesNotContain(HexFormat.of().formatHex(k.getEncoded()));
    }

    /** Message text of a throwable and every cause under it. */
    private static String chainText(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
