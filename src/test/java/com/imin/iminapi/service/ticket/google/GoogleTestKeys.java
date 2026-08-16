package com.imin.iminapi.service.ticket.google;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.UUID;

/**
 * Mints a real 2048-bit RSA keypair at test time and wraps it in a JSON document
 * shaped exactly like a Google Cloud service-account key file, so the Google
 * Wallet save-link signer can be exercised end-to-end without a Google account
 * and without a private key anywhere in the repository.
 *
 * <p>Same idea and same justification as {@code WalletTestCerts} on the Apple
 * side: the signature is produced by real cryptography over synthetic key
 * material, and the test verifies it with the <b>matching public key</b>. A
 * stubbed signer would stay green through the wrong algorithm, the wrong key and
 * a claim set Google rejects — which is to say it would prove nothing at all.
 *
 * <p>What this <em>cannot</em> prove, stated so nobody assumes otherwise: that
 * {@code pay.google.com} accepts the token. Google verifies the signature
 * against the public half of a key it issued, and this key is not one of those.
 * Only a real issuer account closes that gap (gate <b>G-ISSUER</b>).
 */
public final class GoogleTestKeys {

    /**
     * @param serviceAccountJson the full service-account key document, as the
     *                           Google Cloud console would download it
     * @param clientEmail        the {@code client_email} inside it — the {@code iss}
     *                           the signer must put on the JWT
     * @param publicKey          the matching public key, for verifying the signature
     * @param privateKeyPemBody  the bare base64 body of the PKCS#8 private key, with
     *                           no PEM armour and no line breaks. Tests use it as the
     *                           needle when asserting that key material never reaches
     *                           a log line or an exception message.
     */
    public record Bundle(String serviceAccountJson,
                         String clientEmail,
                         RSAPublicKey publicKey,
                         String privateKeyPemBody) {

        /** The same document, base64-encoded — the shape the env var carries. */
        public String serviceAccountJsonBase64() {
            return Base64.getEncoder()
                    .encodeToString(serviceAccountJson.getBytes(StandardCharsets.UTF_8));
        }
    }

    private GoogleTestKeys() {}

    public static Bundle generate() {
        return generate("imin-wallet@imin-test.iam.gserviceaccount.com");
    }

    public static Bundle generate(String clientEmail) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            // PKCS#8 DER, which is what getEncoded() returns for a JCA private
            // key and what Google's own key files carry under a
            // "-----BEGIN PRIVATE KEY-----" header.
            String body = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
            String pem = "-----BEGIN PRIVATE KEY-----\n" + wrap(body) + "-----END PRIVATE KEY-----\n";

            String json = serviceAccountJson(clientEmail, pem);
            return new Bundle(json, clientEmail, (RSAPublicKey) kp.getPublic(), body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a test service-account key", e);
        }
    }

    /**
     * Build the key document with an arbitrary {@code private_key} value, so a
     * test can feed a corrupt or wrong-format key and assert on the failure.
     */
    public static String serviceAccountJson(String clientEmail, String privateKeyPem) {
        return "{\n"
                + "  \"type\": \"service_account\",\n"
                + "  \"project_id\": \"imin-test\",\n"
                + "  \"private_key_id\": \"" + UUID.randomUUID().toString().replace("-", "") + "\",\n"
                + "  \"private_key\": \"" + jsonEscape(privateKeyPem) + "\",\n"
                + "  \"client_email\": \"" + clientEmail + "\",\n"
                + "  \"client_id\": \"100000000000000000001\",\n"
                + "  \"token_uri\": \"https://oauth2.googleapis.com/token\"\n"
                + "}\n";
    }

    private static String wrap(String base64) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        return sb.toString();
    }

    /**
     * The real files carry the PEM as a single JSON string with literal
     * {@code \n} escapes; reproduce that faithfully rather than emitting raw
     * newlines, because "does the parser cope with the escapes" is part of what
     * is under test.
     */
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
