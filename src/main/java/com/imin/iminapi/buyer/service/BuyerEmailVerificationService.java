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
import java.util.List;
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
 * <p>The counter is keyed on <b>the address AND the caller's IP</b> (V121), not
 * on the address alone. Keyed on the address, the budget was the victim's:
 * anyone could post ten wrong codes at {@code victim@x.com} and hold its owner
 * out of verifying for the window, hourly, for free — which is the exact
 * reasoning {@code BuyerAuthController.signup} refuses address keying for. The
 * per-IP {@code buyer-verify-email} bucket bounds an attacker who rotates
 * addresses; the per-code cap of five bounds guessing at any one code.
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
     * Retires <b>this account's</b> outstanding codes for the address and issues
     * a fresh one. Returns the raw six digits — the only moment they exist
     * outside the buyer's inbox; the row stores {@code HMAC-SHA256(pepper, code)}.
     *
     * <p>Another account's live code on the same address is left alone: it was
     * mailed to whoever asked for it, and retiring it here would let any later
     * signup silently burn an earlier buyer's code.
     */
    @Transactional
    public String issue(UUID accountId, String emailNormalized) {
        Instant now = Instant.now();
        codes.invalidateActiveForAccount(emailNormalized, accountId, now);

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
     * Consumes the code that <b>matches the submission</b>, or throws.
     *
     * <p>The submission is checked against every live code on the address, not
     * against the newest one. Several accounts may hold an unverified claim on
     * one address (§2.3 rule 1), so "the live code for this address" is not a
     * single row — and resolving it by recency is what let a later signup answer
     * the code an earlier buyer was still holding, then verify her address onto
     * the later account. Matching by HMAC binds the code to the account that
     * asked for it, which is the account the returned row names.
     *
     * <p>A failure against a live code records an attempt row, including when
     * every live code is already burnt — otherwise an attacker could probe for
     * free by guessing against exhausted codes. An address holding <b>no</b>
     * live code records nothing: there was nothing to guess at, and counting it
     * was what let a stranger lock an address out pre-emptively. A wrong guess burns one
     * attempt on the newest code that still has budget: the per-code counter has
     * to cost something, and the caller cannot be told which of several codes
     * they were guessing at without the response becoming an oracle.
     *
     * @throws ApiException 429 when the address is locked out, 400
     *                      {@code INVALID_CODE} for every other failure — one
     *                      indistinguishable response, on purpose.
     */
    @Transactional
    public BuyerEmailVerificationCode consume(String emailNormalized, String submittedCode, String clientIp) {
        Instant now = Instant.now();
        requireNotLockedOut(emailNormalized, clientIp, now);

        List<BuyerEmailVerificationCode> live = codes
                .findByEmailNormalizedAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        emailNormalized, now);

        // Nothing outstanding is not a guess, so it does not go on the counter.
        // Recording it let an attacker lock an address out BEFORE its owner had
        // ever asked for a code — the one case where the lockout hurt only the
        // person it exists to protect. A code that exists and is burnt still
        // counts: that is somebody guessing at a real code.
        if (live.isEmpty()) throw invalidCode();

        BuyerEmailVerificationCode matched = null;
        BuyerEmailVerificationCode chargeable = null;
        for (BuyerEmailVerificationCode candidate : live) {
            if (candidate.getAttempts() >= maxAttempts()) continue;
            if (chargeable == null) chargeable = candidate;
            if (hasher.matches(submittedCode, candidate.getCodeHash())) {
                matched = candidate;
                break;
            }
        }

        if (matched == null) {
            // DB_ATTEMPT_CEILING, not maxAttempts(): the predicate exists to stop
            // the CHECK constraint being violated by a concurrent burst, and the
            // configured cap is already enforced by the loop above.
            if (chargeable != null) codes.incrementAttempts(chargeable.getId(), DB_ATTEMPT_CEILING);
            attempts.record(emailNormalized, clientIp, false);
            throw invalidCode();
        }

        matched.setConsumedAt(now);
        codes.save(matched);
        attempts.record(emailNormalized, clientIp, true);
        return matched;
    }

    /**
     * 429 once the address has burnt through its hourly failure budget.
     *
     * <p>Not a neutral {@code INVALID_CODE}: the person here has already proved
     * they are typing into the right box, and "too many attempts, try later" is
     * information they need. It leaks nothing an attacker does not already know
     * — they made the failures, from this address, which is why the count is
     * keyed on the caller as well as on the address.
     */
    private void requireNotLockedOut(String emailNormalized, String clientIp, Instant now) {
        Instant since = now.minus(Duration.ofMinutes(props.getLockoutWindowMinutes()));
        if (attempts.countFailuresSince(emailNormalized, clientIp, since)
                >= props.getLockoutFailureThreshold()) {
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
