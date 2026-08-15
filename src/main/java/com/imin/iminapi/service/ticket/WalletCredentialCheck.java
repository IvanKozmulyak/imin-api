package com.imin.iminapi.service.ticket;

import de.brendamour.jpasskit.signing.PKSigningInformationUtil;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Optional;

/**
 * Loads the configured Apple credentials once and reports why they are
 * unusable, if they are.
 *
 * <p><b>Why this is separate from {@link AppleWalletProperties#fullyConfigured()}.</b>
 * That method answers "did someone set the env vars", which is a string check.
 * This one answers "will jpasskit be able to sign", which requires actually
 * decoding the base64, opening the keystore with the password, and parsing the
 * WWDR certificate. Before this existed, the difference between those two
 * questions surfaced as a 500 on the first buyer who tapped the button, hours
 * or days after the bad deploy.
 *
 * <p>Returns the failure reason rather than throwing: an unusable certificate
 * must not stop the application from booting. Ticket issuance, email, checkout
 * and the door all work fine without wallet passes, and taking the whole API
 * down over a decoration would be a far worse outage than the one being
 * diagnosed.
 */
public final class WalletCredentialCheck {

    private WalletCredentialCheck() {}

    /** Empty when there is nothing wrong — including when nothing is configured at all. */
    public static Optional<String> validate(AppleWalletProperties props) {
        if (!props.fullyConfigured()) {
            // Not configured is not a fault. It is the default state.
            return Optional.empty();
        }
        byte[] p12;
        byte[] wwdr;
        try {
            p12 = Base64.getDecoder().decode(props.getCertP12Base64());
        } catch (IllegalArgumentException e) {
            return Optional.of("APPLE_WALLET_CERT_P12_BASE64 is not valid base64");
        }
        try {
            wwdr = Base64.getDecoder().decode(props.getWwdrPemBase64());
        } catch (IllegalArgumentException e) {
            return Optional.of("APPLE_WALLET_WWDR_PEM_BASE64 is not valid base64");
        }
        try {
            new PKSigningInformationUtil()
                    .loadSigningInformationFromPKCS12AndIntermediateCertificate(
                            new ByteArrayInputStream(p12),
                            props.certPasswordOrEmpty(),
                            new ByteArrayInputStream(wwdr));
            return Optional.empty();
        } catch (Exception e) {
            // The message deliberately names the p12 first: a wrong password, a
            // corrupt archive and an expired certificate all surface here, and
            // "p12" is the substring an operator greps for. jpasskit throws a
            // mix of checked IOException/CertificateException and unchecked
            // IllegalStateException from CertUtils, which is why this catches
            // Exception rather than an enumerated set.
            return Optional.of("Apple Wallet p12/WWDR could not be loaded — "
                    + "wrong APPLE_WALLET_CERT_PASSWORD, corrupt archive, an expired "
                    + "certificate, or a mismatched WWDR intermediate: " + e.getMessage());
        }
    }
}
