package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.BuyerProperties;
import com.imin.iminapi.buyer.model.BuyerEmailVerificationCode;
import com.imin.iminapi.buyer.repository.BuyerEmailVerificationCodeRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and consumes the buyer address-verification code, and enforces the
 * <b>DB-counted per-address lockout</b> of epic §2.2.
 *
 * <h2>Why the lockout is a table and not a bucket</h2>
 *
 * <p>The bucket4j limits live in {@code RateLimitConfig}, which is
 * {@code @Profile("!test")} — so the test suite cannot assert on them, and a
 * security control the test suite cannot assert on is a security control that
 * will regress. {@code buyer_verification_attempts} is the test-visible counter,
 * modelled on {@code OrderRecoveryService}'s attempt table, which solves exactly
 * the same problem. Ten failures for one address inside sixty minutes lock that
 * address for the rest of the window <b>regardless of how many fresh codes were
 * issued</b>, which is the property a per-code attempt cap alone does not give
 * you: without it an attacker just asks for another code every five guesses.
 *
 * <h2>Two counters, on purpose</h2>
 *
 * <ul>
 *   <li>{@code buyer_email_verification_codes.attempts} — five wrong guesses
 *       burn <i>this</i> code (mirrors {@code chk_bevc_attempts_range}).</li>
 *   <li>{@code buyer_verification_attempts} — ten failures burn <i>the
 *       address</i> for an hour, across codes.</li>
 * </ul>
 *
 * <p>Both write outside the caller's transaction, so a rolled-back failure still
 * counts. See {@link BuyerVerificationAttemptRecorder}.
 */
@Service
public class BuyerEmailVerificationService {

    /**
     * Hard ceiling from {@code chk_bevc_attempts_range} (V84). Config may lower
     * the per-code attempt cap, never raise it: {@code incrementAttempts} would
     * blow the CHECK constraint on the sixth write and turn a wrong code into a
     * 500 instead of the neutral {@code INVALID_CODE} the flow promises.
     */
    private static final int DB_ATTEMPT_CEILING = 5;

    private final BuyerEmailVerificationCodeRepository codes;
    private final BuyerVerificationAttemptRecorder attempts;
    private final BuyerCodeHasher hasher;
    private final BuyerProperties props;

    public BuyerEmailVerificationService(BuyerEmailVerificationCodeRepository codes,
                                         BuyerVerificationAttemptRecorder attempts,
                                         BuyerCodeHasher hasher,
                                         BuyerProperties props) {
        this.codes = codes;
        this.attempts = attempts;
        this.hasher = hasher;
        this.props = props;
    }

    public int codeTtlMinutes() {
        return props.getVerificationCodeTtlMinutes();
    }

    /**
     * Retires every outstanding code for the address and issues a fresh one.
     * Returns the raw six digits — the only moment they exist outside the
     * buyer's inbox; the row stores {@code HMAC-SHA256(pepper, code)}.
     */
    @Transactional
    public String issue(UUID accountId, String emailNormalized) {
        Instant now = Instant.now();
        codes.invalidateActiveForEmail(emailNormalized, now);

        String code = hasher.generateCode();
        BuyerEmailVerificationCode row = new BuyerEmailVerificationCode();
        row.setBuyerAccountId(accountId);
        row.setEmailNormalized(emailNormalized);
        row.setCodeHash(hasher.hash(code));
        row.setExpiresAt(now.plus(Duration.ofMinutes(props.getVerificationCodeTtlMinutes())));
        codes.save(row);
        return code;
    }

    /**
     * Consumes the live code for an address, or throws.
     *
     * <p>Every failure path records an attempt row first, including "no code
     * outstanding" and "code already burnt" — otherwise an attacker could probe
     * for free by guessing against expired codes. The returned row carries the
     * {@code buyer_account_id} that asked for the code, which is what tells the
     * caller <i>whose</i> address row to mark verified.
     *
     * @throws ApiException 429 when the address is locked out, 400
     *                      {@code INVALID_CODE} for every other failure — one
     *                      indistinguishable response, on purpose.
     */
    @Transactional
    public BuyerEmailVerificationCode consume(String emailNormalized, String submittedCode) {
        Instant now = Instant.now();
        requireNotLockedOut(emailNormalized, now);

        Optional<BuyerEmailVerificationCode> maybe =
                codes.findFirstByEmailNormalizedAndConsumedAtIsNullOrderByCreatedAtDesc(emailNormalized);
        if (maybe.isEmpty()) {
            attempts.record(emailNormalized, false);
            throw invalidCode();
        }
        BuyerEmailVerificationCode active = maybe.get();

        if (active.getAttempts() >= maxAttempts()
                || active.getExpiresAt().isBefore(now)) {
            attempts.record(emailNormalized, false);
            throw invalidCode();
        }

        if (!hasher.matches(submittedCode, active.getCodeHash())) {
            codes.incrementAttempts(active.getId());
            attempts.record(emailNormalized, false);
            throw invalidCode();
        }

        active.setConsumedAt(now);
        codes.save(active);
        attempts.record(emailNormalized, true);
        return active;
    }

    /**
     * 429 once the address has burnt through its hourly failure budget.
     *
     * <p>Not a neutral {@code INVALID_CODE}: the person here has already proved
     * they are typing into the right box, and "too many attempts, try later" is
     * information they need. It leaks nothing an attacker does not already know
     * — they made the failures.
     */
    private void requireNotLockedOut(String emailNormalized, Instant now) {
        Instant since = now.minus(Duration.ofMinutes(props.getLockoutWindowMinutes()));
        if (attempts.countFailuresSince(emailNormalized, since) >= props.getLockoutFailureThreshold()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                    "Too many verification attempts for this address. Try again later.");
        }
    }

    /** The configured cap, clamped to what the CHECK constraint will actually accept. */
    private int maxAttempts() {
        return Math.min(props.getVerificationMaxAttempts(), DB_ATTEMPT_CEILING);
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CODE,
                "Invalid or expired verification code");
    }
}
