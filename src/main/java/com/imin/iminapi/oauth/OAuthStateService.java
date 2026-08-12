package com.imin.iminapi.oauth;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints and verifies the OAuth {@code state} parameter — a stateless, HMAC-signed
 * token that binds the authorize request to its callback with no server-side
 * storage. Verification recomputes the MAC (constant-time compare), checks the
 * provider matches, and rejects anything past its 10-minute expiry. This is the
 * CSRF defence for the redirect flow.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 * base64url(provider ":" expEpochSec ":" nonce ":" audience ":" binding) "." base64url(HMAC-SHA256(payload))
 * </pre>
 *
 * <p>{@code audience} is {@code organizer} or {@code buyer} and it is the reason
 * this class changed for the buyer-accounts epic (§2.4). Two sign-in surfaces now
 * share one Google client, and a state minted for one <b>must not</b> be
 * accepted by the other. The dangerous direction is a buyer's {@code code} +
 * {@code state} posted to the existing organizer callback: that reaches
 * {@code OAuthAccountService.resolve} step 5, which auto-provisions an
 * {@code Organization} and an {@code OWNER} user from a buyer's Google account.
 * {@link #verify} therefore requires the expected audience in <b>both</b>
 * directions, and both directions are tested.
 *
 * <p>{@code binding} is {@code base64url(SHA-256(browser nonce))} or empty. The
 * buyer flow sets an {@code imin_oauth_nonce} cookie when it hands out the
 * authorize URL and requires the digest to match on callback; without it the
 * state's only claim is "this platform minted a Google state in the last ten
 * minutes", which is textbook login CSRF — an attacker completes a Google
 * authorization, hands the victim the callback URL, and the victim's browser
 * lands in the attacker's account. On the buyer side, where the next screen
 * invites you to add an address and see its orders, that is a mechanism for
 * harvesting order history. Binding is <b>mandatory</b> for the buyer audience
 * and optional for the organizer one, which has never had it.
 *
 * <h2>Backward compatibility, and when to delete it</h2>
 *
 * <p>{@link #verify} still accepts the old three-field payload and reads it as
 * {@code audience = organizer} with no binding. Without that branch, every
 * organizer who is mid-login while the deploy rolls fails their sign-in.
 * <b>TODO (remove after 2026-09-12, one release):</b> drop
 * {@code LEGACY_FIELD_COUNT} and its branch in {@link #parse}; states live ten
 * minutes, so a single release is generous.
 */
@Service
public class OAuthStateService {

    private static final Logger log = LoggerFactory.getLogger(OAuthStateService.class);
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String HMAC_ALG = "HmacSHA256";

    /** Organizer dashboard sign-in — {@code OAuthAccountService.resolve}. */
    public static final String AUDIENCE_ORGANIZER = "organizer";
    /** Buyer sign-in on {@code app.imin.wtf} — {@code BuyerOAuthService.resolve}. */
    public static final String AUDIENCE_BUYER = "buyer";

    private static final int FIELD_COUNT = 5;
    /** Pre-epic layout: {@code provider:exp:nonce}. See the class Javadoc's TODO. */
    private static final int LEGACY_FIELD_COUNT = 3;

    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;

    public OAuthStateService(OAuthProperties props) {
        String configured = props.getStateSecret();
        if (configured != null && !configured.isBlank()) {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        } else {
            byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            this.secret = ephemeral;
            log.warn("imin.oauth.state-secret is unset — using an ephemeral per-instance HMAC key. "
                    + "OAuth state will not verify across restarts or multiple instances. "
                    + "Set IMIN_OAUTH_STATE_SECRET in production.");
        }
    }

    /**
     * Sign a fresh state token for the organizer dashboard flow. Unbound, which
     * is what that flow has always been.
     */
    public String sign(String provider) {
        return sign(provider, AUDIENCE_ORGANIZER, null);
    }

    /**
     * Sign a fresh state token for a named audience, optionally bound to a
     * browser-held nonce.
     *
     * @param browserNonce the raw value of the {@code imin_oauth_nonce} cookie
     *                     set alongside the authorize URL, or {@code null} for
     *                     an unbound state. Only its SHA-256 goes into the
     *                     token, so the state itself never carries the secret.
     */
    public String sign(String provider, String audience, String browserNonce) {
        long exp = Instant.now().plus(TTL).getEpochSecond();
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String binding = browserNonce == null || browserNonce.isBlank() ? "" : digestOf(browserNonce);
        String payload = provider + ":" + exp + ":" + b64(nonce) + ":" + audience + ":" + binding;
        return b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + b64(hmac(payload));
    }

    /** Fresh, unguessable value for the {@code imin_oauth_nonce} cookie. */
    public String newBrowserNonce() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return b64(bytes);
    }

    /**
     * Verify a state token minted for the organizer dashboard, or throw a clean
     * 400 if it is missing/tampered/expired/for another provider/<b>for another
     * audience</b>.
     */
    public void verify(String state, String expectedProvider) {
        verify(state, expectedProvider, AUDIENCE_ORGANIZER, null);
    }

    /**
     * Verify a state token against an expected provider <i>and</i> audience.
     *
     * <p>The audience check runs in both directions on purpose: an organizer
     * state posted to the buyer callback is as invalid as the reverse. For
     * {@link #AUDIENCE_BUYER} the browser binding is also mandatory — a buyer
     * state with no binding, or with a cookie that does not hash to the bound
     * digest, is rejected.
     *
     * @param browserNonce the {@code imin_oauth_nonce} cookie value the callback
     *                     request arrived with, or {@code null} when absent.
     */
    public void verify(String state, String expectedProvider, String expectedAudience, String browserNonce) {
        Fields fields = parse(state);

        if (!expectedProvider.equals(fields.provider())) throw invalid();
        if (!expectedAudience.equals(fields.audience())) {
            log.debug("OAuth state audience mismatch: expected {}, got {}", expectedAudience, fields.audience());
            throw invalid();
        }
        if (Instant.now().getEpochSecond() > fields.exp()) throw invalid();

        boolean bindingRequired = AUDIENCE_BUYER.equals(expectedAudience);
        if (fields.binding().isEmpty()) {
            if (bindingRequired) throw invalid();
            return;
        }
        if (browserNonce == null || browserNonce.isBlank()) throw invalid();
        if (!MessageDigest.isEqual(
                digestOf(browserNonce).getBytes(StandardCharsets.UTF_8),
                fields.binding().getBytes(StandardCharsets.UTF_8))) {
            throw invalid();
        }
    }

    /** MAC-checked, structurally-valid state fields. Nothing here is trusted before the MAC passes. */
    private Fields parse(String state) {
        if (state == null || state.isBlank()) throw invalid();
        String[] parts = state.split("\\.", 2);
        if (parts.length != 2) throw invalid();

        String payload;
        try {
            payload = new String(b64d(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }

        byte[] expectedSig = hmac(payload);
        byte[] actualSig;
        try {
            actualSig = b64d(parts[1]);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(expectedSig, actualSig)) throw invalid();

        String[] f = payload.split(":", FIELD_COUNT);
        long exp;
        try {
            exp = Long.parseLong(f[1]);
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            throw invalid();
        }
        if (f.length == LEGACY_FIELD_COUNT) {
            // Pre-epic state, still in flight during the deploy that adds the
            // audience field. Reads as organizer, unbound — exactly what it was.
            return new Fields(f[0], exp, AUDIENCE_ORGANIZER, "");
        }
        if (f.length != FIELD_COUNT) throw invalid();
        return new Fields(f[0], exp, f[3], f[4]);
    }

    private record Fields(String provider, long exp, String audience, String binding) {}

    /** base64url(SHA-256(value)) — the only place the binding digest is computed. */
    private static String digestOf(String value) {
        try {
            return b64(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.OAUTH_INVALID_STATE,
                "Invalid or expired sign-in state");
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] b64d(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
