package com.imin.iminapi.service.ticket.google;

import com.nimbusds.jose.util.JSONObjectUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * The Google Cloud service-account key that signs save-to-wallet links: the
 * {@code client_email} that becomes the JWT's {@code iss}, and the RSA private
 * key that signs it.
 *
 * <p><b>Nothing here reads a file.</b> A service-account key is a private key.
 * It arrives base64-encoded in {@code GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64}
 * and lives only in memory; there is no path in this class that touches the
 * filesystem or the classpath, so there is no way for one to end up committed.
 *
 * <p><b>Nothing here echoes its input.</b> Every failure message names the field
 * or the format that was wrong and stops there — it never quotes the document,
 * the PEM, or the decoded bytes, and it never attaches a cause whose own message
 * might. {@link #toString()} redacts the key for the same reason: the object is
 * far more likely to reach a log format argument than the key itself is.
 * {@code GoogleServiceAccountKeyTest} asserts both properties against a real
 * generated key rather than trusting the reading.
 *
 * <p>JSON is parsed with Nimbus's {@link JSONObjectUtils}, not Jackson,
 * deliberately: Spring Boot 4 wires HTTP message conversion to Jackson 3
 * ({@code tools.jackson}) while Jackson 2 ({@code com.fasterxml}) is also on the
 * classpath transitively, and picking the wrong one is a runtime failure that a
 * {@code catch} would swallow. Nimbus is already a hard dependency of the signer
 * and its parser is shaded, so there is no version to get wrong.
 */
public final class GoogleServiceAccountKey {

    private final String clientEmail;
    private final RSAPrivateKey privateKey;

    private GoogleServiceAccountKey(String clientEmail, RSAPrivateKey privateKey) {
        this.clientEmail = clientEmail;
        this.privateKey = privateKey;
    }

    public String clientEmail() {
        return clientEmail;
    }

    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    /**
     * Parse the value as configured: base64 of the key document, or — because
     * pasting the file straight into the env var is a reasonable mistake and
     * should not look like a corrupt credential — the raw JSON itself.
     *
     * <p>The base64 is MIME-decoded so a value that got line-wrapped by a
     * dashboard field still works. That decoder ignores illegal characters
     * rather than rejecting them, which is exactly why the raw-JSON case is
     * detected <em>first</em>: otherwise the braces and quotes would be quietly
     * eaten and the failure would be reported as a bad key.
     */
    public static GoogleServiceAccountKey parseBase64(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64 is blank");
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) {
            return parse(trimmed);
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            // Deliberately no cause and no echo: the offending value IS the
            // secret, and the decoder's message quotes the input position.
            throw new IllegalArgumentException(
                    "GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64 is not valid base64");
        }
        if (decoded.length == 0) {
            throw new IllegalArgumentException(
                    "GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64 decoded to nothing");
        }
        return parse(new String(decoded, StandardCharsets.UTF_8));
    }

    /** Parse the service-account key document itself. */
    public static GoogleServiceAccountKey parse(String json) {
        Map<String, Object> doc;
        try {
            doc = JSONObjectUtils.parse(json);
        } catch (Exception e) {
            // No cause attached. The parser's message can carry the offending
            // fragment, and the offending fragment can be the private key.
            throw new IllegalArgumentException(
                    "The Google service-account key is not a JSON object");
        }
        String email = string(doc, "client_email");
        if (email == null) {
            throw new IllegalArgumentException(
                    "The Google service-account key has no \"client_email\" field");
        }
        String pem = string(doc, "private_key");
        if (pem == null) {
            throw new IllegalArgumentException(
                    "The Google service-account key has no \"private_key\" field");
        }
        return new GoogleServiceAccountKey(email, rsaPrivateKey(pem));
    }

    private static String string(Map<String, Object> doc, String field) {
        Object v = doc.get(field);
        if (v instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    /**
     * PEM ⇒ {@link RSAPrivateKey}. Google issues PKCS#8
     * ({@code -----BEGIN PRIVATE KEY-----}), which is the only thing
     * {@code KeyFactory} can read directly; a PKCS#1 key gets its own message
     * because {@code InvalidKeySpecException} on that input says nothing an
     * operator can act on.
     */
    private static RSAPrivateKey rsaPrivateKey(String pem) {
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalArgumentException(
                    "The service-account \"private_key\" is a PKCS#1 key; Google issues "
                            + "PKCS#8 (\"BEGIN PRIVATE KEY\"). Re-download the key from the "
                            + "Google Cloud console rather than converting one by hand.");
        }
        String body = pem
                .replaceAll("-----BEGIN[^-]+-----", "")
                .replaceAll("-----END[^-]+-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "The service-account \"private_key\" body is not valid base64");
        }
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            // e.getClass() only. JCA messages on a malformed key describe DER
            // structure, but they are produced by parsing the key bytes and
            // there is no contract that they will not quote them.
            throw new IllegalArgumentException(
                    "The service-account \"private_key\" is not a readable PKCS#8 RSA key ("
                            + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Redacted on purpose. The private key must never reach a log line, and the
     * likeliest way it would is this object being handed to a format argument.
     * On JDK 17 {@code RSAPrivateCrtKeyImpl.toString()} happens to print only an
     * identity hash, but earlier JDKs printed the modulus and the private
     * exponent — "the JDK currently happens not to" is not a control.
     */
    @Override
    public String toString() {
        return "GoogleServiceAccountKey[clientEmail=" + clientEmail + ", privateKey=<redacted>]";
    }
}
