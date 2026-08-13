package com.imin.iminapi.buyer.service;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerIdentity;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerIdentityRepository;
import com.imin.iminapi.buyer.security.BuyerOAuthNonceCookie;
import com.imin.iminapi.oauth.GoogleOAuthService;
import com.imin.iminapi.oauth.OAuthProperties;
import com.imin.iminapi.oauth.OAuthStateService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Google sign-in for <b>buyers</b>. Physically separate from the organizer flow
 * (epic §2.4), and the separation is the point.
 *
 * <h2>Why this is a second service and not a flag</h2>
 *
 * <p>{@code OAuthAccountService.resolve} step 5 auto-provisions an
 * {@code Organization} and an {@code OWNER} user for any unrecognised email. Run
 * a buyer through it and the buyer silently becomes an organizer — and the
 * sign-in <i>appears to succeed</i>, so nothing surfaces the mistake. A boolean
 * parameter switching between "create an org" and "create a buyer" is one wrong
 * default away from exactly that, and the failure would be invisible at the call
 * site.
 *
 * <p>So: <b>this class must never import {@code OAuthAccountService}</b>, and
 * {@code BuyerGoogleSignInTest} asserts that a full buyer Google sign-in creates
 * zero rows in {@code organizations} and {@code users}. What is shared with the
 * organizer path is exactly the HTTP token exchange and ID-token verification in
 * {@link GoogleOAuthService}, plus the state signer — nothing that resolves an
 * account.
 *
 * <h2>Resolution matrix</h2>
 *
 * <ol>
 *   <li><b>Known identity</b> — {@code (provider, sub)} already linked ⇒ sign in
 *       that account. Matched by subject, never by email: a buyer who changes
 *       the address on their Google account must not lose their tickets, and
 *       Apple's relay address matches nothing at all.</li>
 *   <li><b>No email</b> ⇒ 400 {@code OAUTH_EMAIL_REQUIRED}.</li>
 *   <li><b>Google has not verified the email</b> ⇒ 409
 *       {@code OAUTH_EMAIL_UNVERIFIED}. See below.</li>
 *   <li><b>Verified address on an existing buyer account</b> ⇒ link the identity
 *       and sign in.</li>
 *   <li><b>Otherwise</b> ⇒ new {@code buyer_accounts} row + a <b>verified</b>
 *       {@code buyer_account_emails} row + a {@code buyer_identities} row.</li>
 * </ol>
 *
 * <h2>The {@code email_verified} gate (§15 D-3)</h2>
 *
 * <p>Steps 4 and 5 both cross the verification boundary — 4 joins someone's
 * existing order history, 5 mints a <i>pre-verified</i> claim on an address
 * straight from the token. Without Google asserting {@code email_verified},
 * either one hands an address (and its orders) to whoever can get Google to
 * issue a token naming it. {@code OAuthAccountService:118-125} gets this right
 * for organizers; this mirrors it.
 *
 * <p>Step 1 is deliberately <b>not</b> gated: the subject link was established
 * under this gate, an identity match creates no new address claim, and locking a
 * returning buyer out because Google flipped a flag would be a false lockout for
 * no security gain.
 *
 * <p><b>Trap for whoever unblocks Apple:</b> {@code AppleOAuthService:131}
 * hardcodes {@code emailVerified = true}, so this gate is already a no-op on the
 * organizer side for Apple. Do not inherit that — Apple's Hide My Email relay
 * needs the add-an-address flow (R1.3), not a hardcoded true.
 */
@Service
public class BuyerOAuthService {

    private static final Logger log = LoggerFactory.getLogger(BuyerOAuthService.class);
    private static final String PROVIDER = BuyerIdentity.PROVIDER_GOOGLE;

    private final OAuthProperties props;
    private final GoogleOAuthService google;
    private final OAuthStateService states;
    private final BuyerAccountRepository accounts;
    private final BuyerAccountEmailRepository emails;
    private final BuyerIdentityRepository identities;
    private final BuyerAddressClaims claims;
    private final BuyerSessionService sessions;

    public BuyerOAuthService(OAuthProperties props,
                             GoogleOAuthService google,
                             OAuthStateService states,
                             BuyerAccountRepository accounts,
                             BuyerAccountEmailRepository emails,
                             BuyerIdentityRepository identities,
                             BuyerAddressClaims claims,
                             BuyerSessionService sessions) {
        this.props = props;
        this.google = google;
        this.states = states;
        this.accounts = accounts;
        this.emails = emails;
        this.identities = identities;
        this.claims = claims;
        this.sessions = sessions;
    }

    /** The authorize URL plus the {@code Set-Cookie} that binds it to this browser. */
    public record Authorize(String url, ResponseCookie nonceCookie) {}

    /**
     * Gated on {@code GOOGLE_OAUTH_BUYER_REDIRECT_URI} specifically, not on the
     * organizer client being configured: the buyer URI is a <i>second</i>
     * Authorized redirect URI that has to be registered in the Google console
     * first, and offering the button before that lands sends buyers to a
     * {@code redirect_uri_mismatch} page.
     */
    public boolean enabled() {
        return props.getGoogle().isBuyerEnabled();
    }

    public Authorize authorizeUrl() {
        String nonce = states.newBrowserNonce();
        String state = states.sign(PROVIDER, OAuthStateService.AUDIENCE_BUYER, nonce);
        String url = google.buildAuthorizeUrl(props.getGoogle().getBuyerRedirectUri(), state);
        return new Authorize(url, BuyerOAuthNonceCookie.issue(nonce));
    }

    /**
     * Verifies the state (audience <b>and</b> browser binding), exchanges the
     * code against the <b>buyer</b> redirect URI, and resolves the identity.
     *
     * <p>An organizer-audience state is rejected here, and a buyer-audience
     * state is rejected by {@code GoogleOAuthService.exchange} on the organizer
     * callback. Both directions are tested; the second is the dangerous one.
     */
    @Transactional
    public SignedInBuyer callback(String code, String state, String browserNonce, String userAgent) {
        states.verify(state, PROVIDER, OAuthStateService.AUDIENCE_BUYER, browserNonce);
        OAuthUserInfo info = google.exchangeCode(code, props.getGoogle().getBuyerRedirectUri());
        return resolve(info, userAgent);
    }

    /** A signed-in buyer. Same shape as the password flow's result, on purpose. */
    public record SignedInBuyer(BuyerAccount account, BuyerSessionService.IssuedSession session) {}

    /**
     * Pure resolution over an already-verified {@link OAuthUserInfo} — no
     * Google round-trip, which is what makes the matrix unit-testable.
     */
    @Transactional
    public SignedInBuyer resolve(OAuthUserInfo info, String userAgent) {
        Instant now = Instant.now();

        // (1) Known identity — straight in.
        Optional<BuyerIdentity> known =
                identities.findByProviderAndProviderUserId(info.provider(), info.subject());
        if (known.isPresent()) {
            BuyerAccount account = accounts.findById(known.get().getBuyerAccountId())
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                            "Linked buyer identity points at a missing account"));
            return signIn(account, userAgent, now);
        }

        // (2) An address is mandatory — everything downstream keys on it.
        String email = info.email() == null ? null : info.email().trim();
        if (email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.OAUTH_EMAIL_REQUIRED,
                    "Email permission is required to sign in");
        }

        // (3) The gate. See the class Javadoc for why steps 4 and 5 need it and step 1 does not.
        if (!info.emailVerified()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.OAUTH_EMAIL_UNVERIFIED,
                    "Google has not verified this email address. Sign in with your password instead.");
        }

        String normalized = EmailNormalizer.normalize(email);

        // (4) The address is already verified on a buyer account — link, don't duplicate.
        Optional<BuyerAccountEmail> verified = emails.findByVerifiedKey(normalized);
        if (verified.isPresent()) {
            BuyerAccount account = accounts.findById(verified.get().getBuyerAccountId())
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                            "Verified address points at a missing account"));
            link(account.getId(), info);
            log.info("[buyer] google identity linked to existing account {}", account.getId());
            return signIn(account, userAgent, now);
        }

        // (5) Brand new. A buyer account and nothing else — no Organization, no User.
        BuyerAccount account = new BuyerAccount();
        account.setDisplayName(trimToNull(info.displayName()));
        BuyerAccount saved = accounts.save(account);

        BuyerAccountEmail row = BuyerAccountEmail.of(saved.getId(), email, BuyerAccountEmail.ADDED_VIA_GOOGLE);
        emails.save(row);
        // Goes through the same claim path as a redeemed code: re-claims other
        // accounts' unverified rows, sets the primary, stamps activated_at.
        claims.verifyAndClaim(saved, row, now);
        link(saved.getId(), info);
        log.info("[buyer] google sign-up created buyer account {}", saved.getId());
        return signIn(saved, userAgent, now);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private SignedInBuyer signIn(BuyerAccount account, String userAgent, Instant now) {
        account.setLastLoginAt(now);
        accounts.save(account);
        return new SignedInBuyer(account, sessions.issue(account.getId(), userAgent));
    }

    private void link(UUID accountId, OAuthUserInfo info) {
        BuyerIdentity identity = new BuyerIdentity();
        identity.setBuyerAccountId(accountId);
        identity.setProvider(info.provider());
        identity.setProviderUserId(info.subject());
        identity.setEmailAtLink(info.email());
        identity.setDisplayName(info.displayName());
        identities.save(identity);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
