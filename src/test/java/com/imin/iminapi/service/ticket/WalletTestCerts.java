package com.imin.iminapi.service.ticket;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Generates a self-signed PKCS#12 keystore + WWDR-shaped PEM at runtime so the
 * Apple Wallet pass generator can be exercised end-to-end in tests without
 * checking real Apple certs into the repo. The signed output is a valid
 * {@code .pkpass} ZIP — Apple Wallet itself would reject the chain (it
 * trusts only Apple's real WWDR), but the test asserts the archive
 * structure and signature shape, which is what we control.
 */
final class WalletTestCerts {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    record Bundle(String p12Base64, String password, String wwdrPemBase64) {}

    private WalletTestCerts() {}

    static Bundle generate() {
        try {
            // Root / WWDR-shaped self-signed cert.
            X500Name wwdrSubject = new X500Name("CN=Test Apple WWDR CA, O=imin-test, C=US");
            KeyPair wwdrKp = newKeyPair();
            X509Certificate wwdrCert = selfSign(wwdrKp, wwdrSubject);

            // Pass Type ID leaf cert, signed by the WWDR cert. Reuse the WWDR
            // X500Name verbatim so the leaf.issuer == wwdr.subject bit-for-bit;
            // Java's chain validation in PKCS12KeyStore is strict about that.
            KeyPair leafKp = newKeyPair();
            X500Name leafSubject = new X500Name(
                    "CN=Pass Type ID: pass.test.imin, OU=TESTTEAMID, O=imin-test, C=US");
            X509Certificate leafCert = signedByIssuer(
                    leafKp, leafSubject, wwdrKp, wwdrSubject);

            // PKCS#12 with the leaf private key + leaf cert (chain not included;
            // the WWDR cert is supplied separately as an intermediate to jpasskit
            // via wwdrPemBase64).
            String password = "test";
            KeyStore p12 = KeyStore.getInstance("PKCS12");
            p12.load(null, null);
            p12.setKeyEntry("imin-test", leafKp.getPrivate(),
                    password.toCharArray(),
                    new Certificate[]{leafCert});
            ByteArrayOutputStream p12Bytes = new ByteArrayOutputStream();
            p12.store(p12Bytes, password.toCharArray());

            // WWDR PEM. jpasskit reads either PEM or DER from the stream.
            String wwdrPem = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(wwdrCert.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";

            return new Bundle(
                    Base64.getEncoder().encodeToString(p12Bytes.toByteArray()),
                    password,
                    Base64.getEncoder().encodeToString(wwdrPem.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate test wallet certs", e);
        }
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static X509Certificate selfSign(KeyPair kp, X500Name subject) throws Exception {
        Instant now = Instant.now();
        X509v3CertificateBuilder cb = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(System.currentTimeMillis()),
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(365))),
                subject,
                kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                .build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(cb.build(signer));
    }

    private static X509Certificate signedByIssuer(KeyPair leaf, X500Name subject,
                                                   KeyPair issuer, X500Name issuerSubject)
            throws Exception {
        Instant now = Instant.now();
        X509v3CertificateBuilder cb = new JcaX509v3CertificateBuilder(
                issuerSubject,
                BigInteger.valueOf(System.currentTimeMillis() + 1),
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(365))),
                subject,
                leaf.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                .build(issuer.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(cb.build(signer));
    }
}
