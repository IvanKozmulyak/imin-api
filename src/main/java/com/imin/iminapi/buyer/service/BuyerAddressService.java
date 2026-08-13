package com.imin.iminapi.buyer.service;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.buyer.email.BuyerAccountEmailer;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerEmailVerificationCode;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The address set on a signed-in buyer account (epic §2.3) — the feature that
 * makes "my tickets" true for a buyer who checked out as {@code work@co.com}
 * and later signed up as {@code me@gmail.com}.
 *
 * <h2>Verification is the security boundary, and it has exactly one door</h2>
 *
 * <p>Adding an address grants <b>nothing</b>. The row lands with
 * {@code verified_at IS NULL}, and {@code OrderRepository.findForBuyerAccount}
 * reads only verified rows, so at no instant between the POST and the redeemed
 * code does this endpoint expose somebody else's tickets. Crossing that
 * boundary happens in {@link BuyerAddressClaims#verifyAndClaim} and nowhere
 * else — the same door {@code POST /buyer/auth/verify-email} and the Google
 * create branch already use. There is no second path, and adding one would put
 * the re-claim rule, the primary-address rule and the {@code activated_at}
 * stamp back out of step with each other.
 *
 * <h2>Branch on VERIFIED, never on EXISTS (§15 C-2)</h2>
 *
 * <p>{@link #addAddress} sends the code when the address is unknown <i>or</i>
 * merely claimed-but-unverified somewhere, and sends nothing only when it is
 * <b>verified</b> on an account. Branching on "exists" would re-open the
 * squatting lockout that verified-scoped uniqueness closes: an attacker adds
 * your address to their account, never verifies it, and you can never add it to
 * yours — while the UI tells you to check an inbox that gets nothing.
 *
 * <h2>Neutrality has to survive the follow-up GET, not just the POST</h2>
 *
 * <p>Returning 204 on both branches is not enough on an <i>authenticated</i>
 * endpoint. If the taken branch skipped the row insert, the caller could POST
 * {@code victim@x.com}, immediately {@code GET /buyer/emails}, and read the
 * answer off their own account: row present ⇒ nobody has it, row absent ⇒
 * somebody does. So the pending row is written on <b>both</b> branches. What
 * differs is only what lands in a mailbox the caller does not control:
 *
 * <ul>
 *   <li>not verified anywhere ⇒ a six-digit code goes to the address;</li>
 *   <li>verified somewhere ⇒ no code, and there is no other way to obtain one
 *       ({@code resend-verification} also refuses once an address is verified),
 *       so the orphan row simply expires under the 72-hour sweep.</li>
 * </ul>
 *
 * <p>{@link BuyerTimingEqualizer} closes the remaining clock gap: the sending
 * branch pays for an outbound Resend call, the silent branch pays for one
 * bcrypt. They are the same order of magnitude, which is as close as a
 * network-bound branch can get; the property being defended is that the
 * difference is not a reliable oracle, not that the two are identical to the
 * microsecond.
 */
@Service
public class BuyerAddressService {

    private static final Logger log = LoggerFactory.getLogger(BuyerAddressService.class);

    private final BuyerAccountRepository accounts;
    private final BuyerAccountEmailRepository emails;
    private final BuyerEmailVerificationService verification;
    private final BuyerAddressClaims claims;
    private final BuyerSessionService sessions;
    private final BuyerAccountEmailer emailer;
    private final BuyerTimingEqualizer timing;

    public BuyerAddressService(BuyerAccountRepository accounts,
                               BuyerAccountEmailRepository emails,
                               BuyerEmailVerificationService verification,
                               BuyerAddressClaims claims,
                               BuyerSessionService sessions,
                               BuyerAccountEmailer emailer,
                               BuyerTimingEqualizer timing) {
        this.accounts = accounts;
        this.emails = emails;
        this.verification = verification;
        this.claims = claims;
        this.sessions = sessions;
        this.emailer = emailer;
        this.timing = timing;
    }

    // ── Read ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BuyerAccountEmail> list(UUID accountId) {
        return emails.findByBuyerAccountIdOrderByCreatedAtAsc(accountId);
    }

    // ── Add ────────────────────────────────────────────────────────────────

    /**
     * Always returns normally — the controller answers 204 unconditionally. See
     * the class Javadoc for why the row is written on both branches.
     */
    @Transactional
    public void addAddress(UUID accountId, String rawEmail) {
        BuyerAccount account = account(accountId);
        String normalized = EmailNormalizer.normalize(rawEmail);

        // Idempotent: re-adding an address already on this account reuses its
        // row rather than tripping uq_bae_account_email.
        BuyerAccountEmail row = findOrCreateRow(accountId, rawEmail, normalized);

        // §2.3 rule 7: the primary address is the only signal a buyer has that
        // their account is being extended without them. Sent on BOTH branches —
        // sending it only when a code went out would hand the caller the same
        // oracle in their own inbox that the neutral response refuses to give
        // them in the HTTP reply.
        notifyPrimaryOfAddition(account, row);

        if (emails.findByVerifiedKey(normalized).isPresent()) {
            timing.burnPasswordBudget(normalized);
            return;
        }

        String code = verification.issue(accountId, normalized);
        swallow("address verification code", () -> emailer.sendVerificationCode(
                row.getEmail().trim(), account.getLocale(), code, verification.codeTtlMinutes()));
    }

    // ── Verify ─────────────────────────────────────────────────────────────

    /**
     * Redeems a six-digit code for one of the caller's own pending addresses,
     * which is the moment that address's orders join the account.
     *
     * <p>The code row carries the {@code buyer_account_id} that asked for it,
     * and this method refuses a code minted for a different account. Several
     * accounts may hold unverified claims on one address at once (that is what
     * defuses squatting), so without that check a squatter who somehow saw a
     * victim's code could redeem it onto their own account.
     *
     * <p>The row lookup happens <b>before</b> {@code consume}, deliberately: an
     * address that is not on the caller's account fails without touching the
     * per-address failure counter. Otherwise any signed-in buyer could burn a
     * stranger's lockout budget — locking the real owner out of confirming
     * their own address — just by submitting nonsense at it.
     */
    @Transactional
    public BuyerAccount verifyAddress(UUID accountId, String rawEmail, String code) {
        BuyerAccount account = account(accountId);
        String normalized = EmailNormalizer.normalize(rawEmail);

        BuyerAccountEmail row = emails.findByBuyerAccountIdAndEmailNormalized(accountId, normalized)
                // Neutral INVALID_CODE rather than a 404: the address row may
                // legitimately have been swept (72h TTL) while its code lived on,
                // and the caller does not need to be told which of the two failed.
                .orElseThrow(BuyerAddressService::invalidCode);

        BuyerEmailVerificationCode consumed = verification.consume(normalized, code);
        if (!consumed.getBuyerAccountId().equals(accountId)) throw invalidCode();

        if (row.isVerified()) return account;   // idempotent replay of a redeemed code

        claims.verifyAndClaim(account, row, Instant.now());
        return account;
    }

    // ── Primary ────────────────────────────────────────────────────────────

    /**
     * Moves the primary marker to an address that is <b>already verified</b> on
     * this account, and revokes every session (§2.2's third mandatory
     * revocation trigger). The caller is signed out along with everybody else;
     * the controller clears the cookie.
     *
     * <p>Revoking is not paranoia. The primary address is where password-reset
     * links and every security notice go, so moving it is equivalent to moving
     * the account's recovery channel — precisely the action an attacker with a
     * stolen session would take, and precisely the one whose blast radius must
     * end at the next sign-in.
     */
    @Transactional
    public void setPrimary(UUID accountId, String rawEmail) {
        BuyerAccount account = account(accountId);
        String normalized = EmailNormalizer.normalize(rawEmail);

        BuyerAccountEmail target = emails.findByBuyerAccountIdAndEmailNormalized(accountId, normalized)
                .orElseThrow(() -> ApiException.notFound("Address"));
        if (!target.isVerified()) {
            throw ApiException.invalidState("Confirm this address before making it your primary one");
        }
        if (target.isPrimary()) return;   // idempotent

        Optional<BuyerAccountEmail> previous = emails.findByPrimaryMarker(accountId);
        // Flush the clear before setting the new marker: uq_bae_one_primary is a
        // real UNIQUE index, and both writes inside one flush would collide.
        previous.ifPresent(old -> {
            old.clearPrimary();
            emails.saveAndFlush(old);
        });
        target.makePrimary();
        emails.save(target);

        sessions.revokeAll(accountId);

        // §2.3 rule 7: both ends of the move are told, because the old primary
        // is the address a buyer would still be watching if this was not them.
        String locale = account.getLocale();
        previous.ifPresent(old -> swallow("primary-changed notice (old)",
                () -> emailer.sendPrimaryAddressChanged(old.getEmail(), locale, target.getEmail())));
        swallow("primary-changed notice (new)",
                () -> emailer.sendPrimaryAddressChanged(target.getEmail(), locale, target.getEmail()));
    }

    // ── Remove ─────────────────────────────────────────────────────────────

    /**
     * Removes an address from the account.
     *
     * <h2>The rule, and why it is this one</h2>
     *
     * <ol>
     *   <li><b>Unverified rows go freely.</b> They grant nothing and expire in
     *       72 hours anyway; refusing would only trap typos on the profile
     *       screen.</li>
     *   <li><b>The primary address cannot be removed.</b> 409
     *       {@code INVALID_STATE}, telling the buyer to promote another verified
     *       address first. It is where password resets and every security notice
     *       are delivered, and sign-in is gated on the account having one — an
     *       account with no primary is an account nobody can get back into.</li>
     *   <li><b>The last verified address cannot be removed</b>, checked
     *       independently of (2) rather than relied upon to follow from it. On a
     *       healthy row set the last verified address IS the primary, so (2)
     *       already covers it; the separate count is there so a row set that has
     *       somehow lost its primary marker still cannot be emptied.</li>
     *   <li><b>A verified, non-primary address goes.</b> This is destructive and
     *       deliberately allowed: it drops that address's orders out of
     *       {@code GET /buyer/orders}, and it releases the platform-wide verified
     *       claim so somebody else who controls that mailbox can take it. Both
     *       are correct. Control of the inbox is what proves the claim, so a
     *       buyer who has lost an old work address must be able to let go of it,
     *       and holding a claim on a mailbox you no longer own is the state
     *       worth preventing — not the state worth preserving.</li>
     * </ol>
     *
     * <p>What the buyer loses in case (4) is visibility, not data: the orders
     * still exist, {@code /recover} still emails their links to whoever controls
     * that address, and re-adding and re-verifying the address brings them back.
     * The frontend must say so at the confirm step — the removal is reversible
     * only for as long as the buyer still controls the mailbox.
     *
     * <p>Sessions are deliberately NOT revoked here. Removing a secondary
     * address narrows what the account can see; it does not change how the
     * account is authenticated or where its recovery mail goes.
     */
    @Transactional
    public void remove(UUID accountId, String rawEmail) {
        String normalized = EmailNormalizer.normalize(rawEmail);
        BuyerAccountEmail row = emails.findByBuyerAccountIdAndEmailNormalized(accountId, normalized)
                .orElseThrow(() -> ApiException.notFound("Address"));

        if (row.isPrimary()) {
            throw ApiException.invalidState(
                    "This is your primary address. Make another confirmed address primary first.");
        }
        if (row.isVerified() && emails.countByBuyerAccountIdAndVerifiedAtIsNotNull(accountId) <= 1) {
            throw ApiException.invalidState(
                    "This is the only confirmed address on your account. Add and confirm another first.");
        }

        emails.delete(row);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private BuyerAccount account(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Account"));
    }

    /**
     * Read, then insert if absent. Adding the same address twice is ordinary —
     * a buyer re-typing one they already have pending — and must not 500 on
     * {@code uq_bae_account_email}.
     *
     * <p><b>Not</b> wrapped in the repo's usual
     * {@code DataIntegrityViolationException} catch
     * ({@code AudienceOrderProjector:105-117}). That idiom cannot work here: this
     * method runs inside {@link #addAddress}'s transaction, and a JPA constraint
     * violation marks that transaction rollback-only, so catching it and carrying
     * on would still fail at commit — later, and after doing pointless work. The
     * residual window is two genuinely concurrent adds of the same address on one
     * account (a double-clicked button), which loses the insert and the client
     * resolves by retrying, since the row then exists. If that ever shows up in
     * the logs the fix is a {@code REQUIRES_NEW} find-or-create, in the shape of
     * {@code BuyerVerificationAttemptRecorder} — not a catch in this transaction.
     */
    private BuyerAccountEmail findOrCreateRow(UUID accountId, String rawEmail, String normalized) {
        return emails.findByBuyerAccountIdAndEmailNormalized(accountId, normalized)
                .orElseGet(() -> emails.save(
                        BuyerAccountEmail.of(accountId, rawEmail, BuyerAccountEmail.ADDED_VIA_MANUAL)));
    }

    /**
     * Tells the account's primary address that a new address was attached.
     *
     * <p>Skipped when the added address IS the primary (re-adding your own
     * primary), which would otherwise mail a buyer about themselves, and when
     * the account has no primary yet — an account in that state has no verified
     * address, so there is nowhere trustworthy to send it.
     *
     * <p><b>Known deviation from §2.3 rule 7</b>, which asks for a notice on add
     * <i>and</i> on verify. Only the add notice is sent. The add is the moment
     * the account is being extended by someone who may not be the owner, and it
     * is the moment the owner can still act; a second mail at confirmation
     * repeats the same fact to the same inbox. If the second notice is wanted
     * later it belongs in {@link #verifyAddress}, not in
     * {@link BuyerAddressClaims} — that class is also on the signup and Google
     * paths, where a "new address added" mail would be nonsense.
     */
    private void notifyPrimaryOfAddition(BuyerAccount account, BuyerAccountEmail added) {
        emails.findByPrimaryMarker(account.getId()).ifPresent(primary -> {
            if (primary.getId().equals(added.getId())) return;
            swallow("address-added notice", () -> emailer.sendAddressAdded(
                    primary.getEmail(), account.getLocale(), added.getEmail()));
        });
    }

    /**
     * Sends and swallows, the {@code AuthService.forgotPassword:218-223} pattern.
     * A Resend outage must not change the status, the body or the persisted state
     * of a flow whose entire contract is that every branch looks the same.
     */
    private void swallow(String what, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            log.error("[buyer] {} email send failed: {}", what, e.getMessage(), e);
        }
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CODE,
                "Invalid or expired verification code");
    }
}
