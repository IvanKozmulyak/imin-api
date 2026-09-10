package com.imin.iminapi.service.auth.verification;

import com.imin.iminapi.buyer.service.BuyerCodeHasher;
import com.imin.iminapi.model.EmailVerificationCode;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EmailVerificationCodeRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Issues and consumes the organizer email-verification code.
 *
 * <h2>Six digits, peppered</h2>
 *
 * <p>This used to be four digits stored in plaintext with five attempts per
 * code. {@code POST /auth/verify-email} was unmetered and
 * {@code /auth/resend-verification} allows three fresh codes per fifteen
 * minutes, so the reachable budget was roughly 1,440 guesses a day against a
 * 10,000-value space — and a hit returns a live session for somebody else's
 * organization. The code is now six digits and the row stores
 * {@code HMAC-SHA256(pepper, code)}.
 *
 * <p>The hasher is {@link BuyerCodeHasher}, reused rather than cloned: it is the
 * same act with the same threat model, its pepper ({@code IMIN_BUYER_CODE_SECRET})
 * is already set in production, and a second secret would be one more thing to
 * get wrong on a deploy. The two code namespaces are separate rows keyed by
 * different owners, so sharing a pepper lets nothing cross over.
 *
 * <h2>Two counters, on purpose</h2>
 *
 * <ul>
 *   <li>{@code email_verification_codes.attempts} — five wrong guesses burn
 *       <i>this</i> code.</li>
 *   <li>The sum of that column across a window burns <i>the account</i>, which
 *       is what a per-code cap alone cannot do: an attacker just asks for a new
 *       code every five guesses. Mirrors the buyer side's §2.2 lockout.</li>
 * </ul>
 */
@Service
public class EmailVerificationService {

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    public static final int EXPIRES_IN_MINUTES = (int) CODE_TTL.toMinutes();

    /** Failed guesses across all of one account's codes before the account is locked out. */
    public static final int LOCKOUT_FAILURE_THRESHOLD = 10;
    /** The window the threshold is counted over, and the length of the lockout. */
    public static final Duration LOCKOUT_WINDOW = Duration.ofMinutes(60);

    private final EmailVerificationCodeRepository codes;
    private final UserRepository users;
    private final BuyerCodeHasher hasher;
    private final Clock clock;
    private final Duration ttl;
    private final int maxAttempts;

    @Autowired
    public EmailVerificationService(EmailVerificationCodeRepository codes,
                                     UserRepository users,
                                     BuyerCodeHasher hasher) {
        this(codes, users, hasher, Clock.systemUTC(), CODE_TTL, MAX_ATTEMPTS);
    }

    /** Constructor used by tests for clock + parameter overrides. */
    public EmailVerificationService(EmailVerificationCodeRepository codes,
                                     UserRepository users,
                                     BuyerCodeHasher hasher,
                                     Clock clock,
                                     Duration ttl,
                                     int maxAttempts) {
        this.codes = codes;
        this.users = users;
        this.hasher = hasher;
        this.clock = clock;
        this.ttl = ttl;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public String issueCode(User user) {
        Instant now = clock.instant();
        codes.invalidateActiveForUser(user.getId(), now);
        String code = hasher.generateCode();
        EmailVerificationCode entity = new EmailVerificationCode();
        entity.setUserId(user.getId());
        entity.setCodeHash(hasher.hash(code));
        entity.setExpiresAt(now.plus(ttl));
        codes.save(entity);
        return code;
    }

    /**
     * Verifies a submitted code against the user's latest active verification code.
     * <p>
     * The wrong-code attempts++ uses {@link EmailVerificationCodeRepository#incrementAttempts}
     * which runs in a {@code REQUIRES_NEW} transaction so it commits independently of
     * this method's transaction (and any outer transaction the caller is in). When the
     * INVALID_CODE throw rolls the outer transaction back, the brute-force counter
     * has already been persisted — which is also what keeps the lockout below honest.
     *
     * @throws ApiException 429 {@code RATE_LIMITED} once the account has burnt
     *         its hourly guess budget, 400 {@code INVALID_CODE} for every other
     *         failure — one indistinguishable response, on purpose.
     */
    @Transactional
    public User verify(String email, String code) {
        Instant now = clock.instant();
        Optional<User> maybeUser = users.findByEmailLower(email.toLowerCase());
        if (maybeUser.isEmpty()) {
            throw invalidCode();
        }
        User user = maybeUser.get();
        requireNotLockedOut(user, now);
        Optional<EmailVerificationCode> maybeActive =
                codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId());
        if (maybeActive.isEmpty()) {
            throw invalidCode();
        }
        EmailVerificationCode active = maybeActive.get();

        if (active.getAttempts() >= maxAttempts) throw invalidCode();
        if (active.getExpiresAt().isBefore(now)) throw invalidCode();

        if (!matches(active, code)) {
            codes.incrementAttempts(active.getId());
            throw invalidCode();
        }

        active.setConsumedAt(now);
        codes.save(active);
        user.setVerifiedAt(now);
        users.save(user);
        return user;
    }

    /**
     * Constant-time against the stored digest. Falls back to the legacy plaintext
     * column for codes issued before V96, so a rolling deploy does not invalidate
     * codes already sitting in inboxes; those rows expire within ten minutes and
     * nothing writes the column any more.
     */
    private boolean matches(EmailVerificationCode active, String submitted) {
        if (active.getCodeHash() != null) return hasher.matches(submitted, active.getCodeHash());
        return active.getCode() != null && active.getCode().equals(submitted);
    }

    /**
     * 429 once the account has burnt its hourly guess budget across every code
     * it has been issued.
     *
     * <p>Not a neutral {@code INVALID_CODE}: whoever is here has already proved
     * they are typing into the right box, and "too many attempts" is information
     * they need. It leaks nothing an attacker does not already know — they made
     * the failures. Enumeration is unaffected because the unknown-address branch
     * above returns before this check.
     */
    private void requireNotLockedOut(User user, Instant now) {
        long failures = codes.sumAttemptsSince(user.getId(), now.minus(LOCKOUT_WINDOW));
        if (failures >= LOCKOUT_FAILURE_THRESHOLD) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                    "Too many verification attempts for this address. Try again later.");
        }
    }

    private ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CODE,
                "Invalid or expired verification code");
    }
}
