package com.imin.iminapi.service.ticket.google;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mints the signed JWT behind {@code https://pay.google.com/gp/v/save/{jwt}}.
 *
 * <p>The claim set is Google's, not ours: {@code iss} = the service-account
 * email, {@code aud} = {@code "google"}, {@code typ} = {@code "savetowallet"},
 * {@code iat} = unix seconds, optional {@code origins}, and {@code payload} =
 * the resource envelope. Signing is <b>RS256</b>. The JOSE header additionally
 * declares {@code typ: JWT}, matching Google's own reference sample — note that
 * this is a different {@code typ} from the claim of the same name, and both are
 * required to be what they are.
 *
 * <p><b>No Google client library.</b> {@code com.nimbusds:nimbus-jose-jwt} is
 * already on the classpath via {@code spring-security-oauth2-jose} and is
 * already used by {@code oauth/AppleClientSecretFactory} for the same job with a
 * different curve. Pulling {@code google-auth-library} to do RS256 over a JSON
 * body would add a large transitive tree for nothing.
 *
 * <p><b>The credential is checked at construction, not at the door.</b> Same
 * lesson as {@code WalletCredentialCheck} on the Apple side: the difference
 * between "the env vars are set" and "the key actually loads" used to surface as
 * a 500 on the first buyer who tapped the button, hours after the bad deploy. It
 * is a log line and <b>not</b> a throw — an unusable wallet credential must not
 * stop the application from booting, because checkout, issuance, email and the
 * door are all unaffected by a missing pass.
 *
 * <p><b>The serialised JWT is never logged.</b> It is a bearer artifact: anyone
 * holding it can add the pass. No method here writes it to a logger, and no
 * exception message carries it.
 */
@Component
public class GoogleWalletJwtSigner {

    private static final Logger log = LoggerFactory.getLogger(GoogleWalletJwtSigner.class);

    private static final String AUDIENCE = "google";
    private static final String SAVE_TO_WALLET = "savetowallet";

    /** Null when the wallet is off, or when the configured credential will not load. */
    private final GoogleServiceAccountKey key;
    private final List<String> origins;
    /** Null unless the credential was configured AND failed to load. */
    private final String unusableReason;

    /**
     * The production path. Parses the configured credential once and records the
     * outcome; never throws.
     */
    @Autowired
    public GoogleWalletJwtSigner(GoogleWalletProperties props) {
        this.origins = props.getOrigins() == null ? List.of() : List.copyOf(props.getOrigins());

        GoogleServiceAccountKey parsed = null;
        String reason = null;
        if (props.fullyConfigured()) {
            try {
                parsed = GoogleServiceAccountKey.parseBase64(props.getServiceAccountJsonBase64());
            } catch (RuntimeException e) {
                // Message only, no cause: GoogleServiceAccountKey guarantees its
                // own messages carry no key material, but a cause chain from
                // somewhere else offers no such guarantee.
                reason = e.getMessage();
            }
        }
        this.key = parsed;
        this.unusableReason = reason;

        if (reason != null) {
            log.error("[wallet] Google Wallet is configured but UNUSABLE: {} — the save link "
                    + "will 503. Fix GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64 or set "
                    + "GOOGLE_WALLET_ENABLED=false.", reason);
        } else if (parsed != null) {
            log.info("[wallet] Google Wallet configured and the service-account key loads OK "
                            + "(issuer {}, service account {}, origins {})",
                    props.getIssuerId(), parsed.clientEmail(),
                    origins.isEmpty() ? "unrestricted" : origins);
        } else {
            log.info("[wallet] Google Wallet is off — {}",
                    props.gateReason().orElse("no reason recorded"));
        }
    }

    /** Direct construction from an already-parsed key, with no approved origins. */
    public GoogleWalletJwtSigner(GoogleServiceAccountKey key) {
        this(key, List.of());
    }

    public GoogleWalletJwtSigner(GoogleServiceAccountKey key, List<String> origins) {
        this.key = key;
        this.origins = origins == null ? List.of() : List.copyOf(origins);
        this.unusableReason = null;
    }

    /** True when {@link #sign(Map)} will produce a token. */
    public boolean isUsable() {
        return key != null;
    }

    /**
     * Why a configured credential will not load, if that is the situation.
     * Empty both when everything is fine and when the wallet is simply off —
     * unset is not a fault, and an unset wallet must not look like an incident.
     */
    public Optional<String> unusableReason() {
        return Optional.ofNullable(unusableReason);
    }

    /**
     * Sign the save-to-wallet claim set around {@code payload}, which is the
     * resource envelope — for this plan, {@code {"eventTicketObjects": [{"id": …}]}}.
     *
     * @return the compact serialisation, to be appended to
     *         {@code https://pay.google.com/gp/v/save/}. <b>Do not log it.</b>
     */
    public String sign(Map<String, Object> payload) {
        GoogleServiceAccountKey k = key;
        if (k == null) {
            throw new IllegalStateException("Google Wallet is not configured"
                    + (unusableReason == null ? "" : ": " + unusableReason));
        }
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(k.clientEmail())
                .audience(AUDIENCE)
                .claim("typ", SAVE_TO_WALLET)
                .issueTime(Date.from(Instant.now()))
                .claim("payload", payload);
        if (!origins.isEmpty()) {
            // Omitted rather than sent empty when unconfigured: an empty array is
            // a defined restriction to nothing, which is a different and worse
            // thing than an absent field.
            claims.claim("origins", origins);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
                claims.build());
        try {
            jwt.sign(new RSASSASigner(k.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException(
                    "Could not sign the Google Wallet save link (" + e.getClass().getSimpleName() + ")");
        }
        return jwt.serialize();
    }
}
