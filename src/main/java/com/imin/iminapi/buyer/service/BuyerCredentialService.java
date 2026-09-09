package com.imin.iminapi.buyer.service;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.buyer.BuyerProperties;
import com.imin.iminapi.buyer.email.BuyerMailEvents;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerEmailVerificationCode;
import com.imin.iminapi.buyer.model.BuyerPasswordResetToken;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerPasswordResetTokenRepository;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.security.TokenService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Email + password credentials for buyer accounts (epic §2.2). Google sign-in
 * lives in {@link BuyerOAuthService}; both end at
 * {@link BuyerSessionService#issue}.
 *
 * <h2>The branching rule, and why it is VERIFIED and not EXISTS</h2>
 *
 * <p>Signup branches on whether the address is <b>verified</b> somewhere, never
 * on whether a row for it exists (§15 C-2, overriding §2.2's own table):
 *
 * <ul>
 *   <li><b>Unknown, or claimed-but-unverified elsewhere</b> ⇒ create the account
 *       and send the code.</li>
 *   <li><b>Verified elsewhere</b> ⇒ create nothing, send the neutral
 *       "you already have an account" notice.</li>
 * </ul>
 *
 * <p>Branching on "exists" would re-open the squatting lockout that
 * verified-scoped uniqueness (§2.3 rule 1) exists to close: an attacker signs up
 * as {@code victim@x.com} and never verifies; the victim's real signup then
 * creates nothing and mails them "sign in or reset your password" for an account
 * that is not theirs — and because every response here is deliberately neutral,
 * they get no diagnosable error and no way out. The 72-hour TTL caps one squat,
 * not re-squatting.
 *
 * <h2>Anti-enumeration</h2>
 *
 * <p>Signup, resend and forgot-password are <b>always 204</b> with an identical
 * body on every branch, following {@code AuthService.forgotPassword:211-224}
 * rather than {@code signup:100-103}'s {@code 409 DUPLICATE}. Send failures are
 * swallowed and logged so a Resend outage cannot become an oracle either, and
 * {@link BuyerTimingEqualizer} keeps the bcrypt cost on both branches so the
 * clock is not an oracle. Login answers a single generic 401, and
 * {@code 403 EMAIL_NOT_VERIFIED} is reachable <b>only after a correct
 * password</b> (§15 D-2) — otherwise that response is itself the oracle.
 */
@Service
public class BuyerCredentialService {

    private final BuyerAccountRepository accounts;
    private final BuyerAccountEmailRepository emails;
    private final BuyerPasswordResetTokenRepository resetTokens;
    private final BuyerEmailVerificationService verification;
    private final BuyerAddressClaims claims;
    private final BuyerSessionService sessions;
    private final ApplicationEventPublisher events;
    private final BuyerTimingEqualizer timing;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final BuyerProperties props;

    public BuyerCredentialService(BuyerAccountRepository accounts,
                                  BuyerAccountEmailRepository emails,
                                  BuyerPasswordResetTokenRepository resetTokens,
                                  BuyerEmailVerificationService verification,
                                  BuyerAddressClaims claims,
                                  BuyerSessionService sessions,
                                  ApplicationEventPublisher events,
                                  BuyerTimingEqualizer timing,
                                  PasswordHasher hasher,
                                  TokenService tokens,
                                  BuyerProperties props) {
        this.accounts = accounts;
        this.emails = emails;
        this.resetTokens = resetTokens;
        this.verification = verification;
        this.claims = claims;
        this.sessions = sessions;
        this.events = events;
        this.timing = timing;
        this.hasher = hasher;
        this.tokens = tokens;
        this.props = props;
    }

    /** A signed-in buyer: the account plus the session cookie to attach. */
    public record SignedIn(BuyerAccount account, BuyerSessionService.IssuedSession session) {}

    // ── Signup ─────────────────────────────────────────────────────────────

    /**
     * Always returns normally — the caller answers 204 unconditionally. See the
     * class Javadoc for the branch rule.
     */
    @Transactional
    public void signup(String rawEmail, String password, String locale) {
        String normalized = EmailNormalizer.normalize(rawEmail);

        Optional<BuyerAccountEmail> verifiedElsewhere = emails.findByVerifiedKey(normalized);
        if (verifiedElsewhere.isPresent()) {
            // Negative branch: no account is created, so nothing hashes a
            // password. Burn the same budget or the response time says so.
            timing.burnPasswordBudget(password);
            BuyerAccountEmail owner = verifiedElsewhere.get();
            String ownerLocale = accounts.findById(owner.getBuyerAccountId())
                    .map(BuyerAccount::getLocale).orElse(null);
            events.publishEvent(new BuyerMailEvents.AccountExistsNotice(owner.getEmail(), ownerLocale));
            return;
        }

        BuyerAccount account = new BuyerAccount();
        account.setPasswordHash(hasher.hash(password));
        account.setLocale(EmailLocale.normalizeOrNull(locale));
        BuyerAccount saved = accounts.save(account);

        emails.save(BuyerAccountEmail.of(saved.getId(), rawEmail, BuyerAccountEmail.ADDED_VIA_SIGNUP));

        String code = verification.issue(saved.getId(), normalized);
        events.publishEvent(new BuyerMailEvents.VerificationCode(
                rawEmail.trim(), saved.getLocale(), code, verification.codeTtlMinutes()));
    }

    // ── Verify ─────────────────────────────────────────────────────────────

    /**
     * Redeems a six-digit code, verifies the address, and signs the buyer in.
     *
     * <p>The code row carries the account that asked for it, so a code issued to
     * account A can never verify an address row on account B — which matters
     * because several accounts may hold unverified claims on one address.
     */
    @Transactional
    public SignedIn verifyEmail(String rawEmail, String code, String userAgent) {
        String normalized = EmailNormalizer.normalize(rawEmail);
        BuyerEmailVerificationCode consumed = verification.consume(normalized, code);

        BuyerAccount account = accounts.findById(consumed.getBuyerAccountId())
                .orElseThrow(BuyerCredentialService::invalidCode);
        BuyerAccountEmail row = emails
                .findByBuyerAccountIdAndEmailNormalized(account.getId(), normalized)
                // The address row can be swept (72h TTL) while its code is still
                // live. Neutral INVALID_CODE rather than a confusing 404.
                .orElseThrow(BuyerCredentialService::invalidCode);

        Instant now = Instant.now();
        claims.verifyAndClaim(account, row, now);
        account.setLastLoginAt(now);
        accounts.save(account);

        return new SignedIn(account, sessions.issue(account.getId(), userAgent));
    }

    /**
     * Always returns normally — 204 on every branch.
     *
     * <p>This request is unauthenticated and carries nothing but an address, so
     * it can only be honoured when the address has exactly <b>one</b> claimant.
     * Several accounts may hold unverified claims on one address (§2.3 rule 1),
     * and there is then no fact in the request that says which of them the
     * caller is completing. "Newest wins" looked like the deterministic reading
     * of an ambiguous request and was in fact an account-takeover primitive: a
     * stranger signing up after the real owner became the newest claim, and the
     * owner's next resend mailed <i>his</i> code to <i>her</i> inbox — which,
     * redeemed, verified her address onto his account. Contested addresses
     * therefore get the same silent 204, and the owner's existing code, which is
     * still bound to her own account, keeps working.
     *
     * <p>An address that is already verified gets nothing either — there is
     * nothing to resend, and mailing its owner on a stranger's request would be
     * a nuisance amplifier.
     */
    @Transactional
    public void resendVerification(String rawEmail) {
        String normalized = EmailNormalizer.normalize(rawEmail);
        if (emails.findByVerifiedKey(normalized).isPresent()) return;

        List<BuyerAccountEmail> pending = emails.findByEmailNormalizedAndVerifiedAtIsNull(normalized);
        if (pending.stream().map(BuyerAccountEmail::getBuyerAccountId).distinct().count() != 1) return;

        BuyerAccountEmail claim = pending.get(0);
        Optional<BuyerAccount> account = accounts.findById(claim.getBuyerAccountId());
        if (account.isEmpty()) return;

        String code = verification.issue(account.get().getId(), normalized);
        events.publishEvent(new BuyerMailEvents.VerificationCode(
                claim.getEmail(), account.get().getLocale(), code, verification.codeTtlMinutes()));
    }

    // ── Login ──────────────────────────────────────────────────────────────

    /**
     * Password sign-in.
     *
     * <p>Order is load-bearing (§15 D-2): the password is checked <b>first</b>,
     * and only a correct password can produce {@code 403 EMAIL_NOT_VERIFIED}.
     * The other way round, that status tells an unauthenticated caller "this
     * address has an unverified imin account", which is precisely the fact the
     * neutral signup response refuses to disclose.
     */
    @Transactional
    public SignedIn login(String rawEmail, String password, String userAgent) {
        String normalized = EmailNormalizer.normalize(rawEmail);

        // A verified row is authoritative; otherwise fall back to the newest
        // unverified claim purely so a real owner gets EMAIL_NOT_VERIFIED
        // instead of a baffling 401 after a correct password.
        BuyerAccountEmail row = emails.findByVerifiedKey(normalized)
                .or(() -> emails.findFirstByEmailNormalizedAndVerifiedAtIsNullOrderByCreatedAtDesc(normalized))
                .orElse(null);
        BuyerAccount account = row == null ? null
                : accounts.findById(row.getBuyerAccountId()).orElse(null);

        boolean passwordOk;
        if (account == null || account.getPasswordHash() == null) {
            // Unknown address, or a Google-only account with no password. Both
            // must cost what a real check costs.
            timing.burnPasswordBudget(password);
            passwordOk = false;
        } else {
            passwordOk = hasher.verify(password, account.getPasswordHash());
        }
        if (!passwordOk) throw invalidCredentials();

        if (!row.isVerified() || emails.findByPrimaryMarker(account.getId()).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.EMAIL_NOT_VERIFIED,
                    "Confirm your email address before signing in");
        }

        Instant now = Instant.now();
        account.setLastLoginAt(now);
        accounts.save(account);
        return new SignedIn(account, sessions.issue(account.getId(), userAgent));
    }

    // ── Password reset ─────────────────────────────────────────────────────

    /**
     * Always returns normally — 204 on every branch, the
     * {@code AuthService.forgotPassword:211-224} pattern verbatim.
     *
     * <p>A reset link is sent only to a <b>verified</b> address, so it can never
     * hand control of an account to someone who merely typed its address.
     *
     * <p>Issuing retires the account's outstanding links first: only the newest
     * one may work, exactly as {@code BuyerEmailVerificationService.issue}
     * retires outstanding codes.
     *
     * <p>A Google-only account (NULL {@code password_hash}) can acquire a
     * password this way. That is correct and standard — the link goes to an
     * address the buyer has proved they control — and it is the escape hatch for
     * a buyer who loses access to their Google account. Do not "fix" it into a
     * 400.
     */
    @Transactional
    public void forgotPassword(String rawEmail) {
        String normalized = EmailNormalizer.normalize(rawEmail);
        Optional<BuyerAccountEmail> verified = emails.findByVerifiedKey(normalized);
        if (verified.isEmpty()) return;

        Optional<BuyerAccount> maybe = accounts.findById(verified.get().getBuyerAccountId());
        if (maybe.isEmpty()) return;
        BuyerAccount account = maybe.get();

        // Only the newest link may work. Anything still outstanding is retired
        // before the new one is minted — see consumeAllForAccount.
        Instant now = Instant.now();
        resetTokens.consumeAllForAccount(account.getId(), now);

        TokenService.IssuedToken issued = tokens.issue();
        BuyerPasswordResetToken token = new BuyerPasswordResetToken();
        token.setBuyerAccountId(account.getId());
        token.setTokenHash(issued.tokenHash());
        token.setExpiresAt(now.plus(Duration.ofMinutes(props.getPasswordResetTtlMinutes())));
        resetTokens.save(token);

        events.publishEvent(new BuyerMailEvents.PasswordReset(
                verified.get().getEmail(), account.getLocale(),
                issued.token(), props.getPasswordResetTtlMinutes()));
    }

    /**
     * Consumes a reset token, rotates the hash and <b>revokes every session</b>
     * — one of §2.2's five mandatory revocation triggers, and the one that
     * matters most: the reason someone resets a password is usually that they
     * think somebody else has it.
     *
     * <p>No session is reissued. The buyer signs in again with the new password;
     * that is why the notice uses the buyer {@code password-changed} template
     * and not the organizer one, which promises the acting device stays signed
     * in.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        Instant now = Instant.now();
        BuyerPasswordResetToken token = resetTokens.findByTokenHash(tokens.hashOf(rawToken))
                .orElseThrow(BuyerCredentialService::invalidToken);
        if (token.getConsumedAt() != null || token.getExpiresAt().isBefore(now)) throw invalidToken();

        BuyerAccount account = accounts.findById(token.getBuyerAccountId())
                .orElseThrow(BuyerCredentialService::invalidToken);

        account.setPasswordHash(hasher.hash(newPassword));
        accounts.save(account);
        token.setConsumedAt(now);
        resetTokens.save(token);
        // And any link minted in parallel with the one just used dies with it.
        resetTokens.consumeAllForAccount(account.getId(), now);

        sessions.revokeAll(account.getId());

        emails.findByPrimaryMarker(account.getId()).ifPresent(primary ->
                events.publishEvent(new BuyerMailEvents.PasswordChanged(
                        primary.getEmail(), account.getLocale())));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CODE,
                "Invalid or expired verification code");
    }

    private static ApiException invalidToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_TOKEN,
                "Invalid or expired reset token");
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                "Invalid credentials");
    }
}
