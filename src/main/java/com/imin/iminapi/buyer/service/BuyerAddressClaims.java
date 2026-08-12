package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The one place an address becomes <b>verified</b> on a buyer account.
 *
 * <p>Verification is the entire security boundary of buyer accounts (§2.3 rule
 * 4): an unverified row grants nothing, and the orders join reads only
 * {@code verified_at IS NOT NULL}. Both entry points that can cross that
 * boundary — {@code POST /buyer/auth/verify-email} and the Google create branch
 * — go through {@link #verifyAndClaim}, so the four things that must happen
 * together cannot come apart:
 *
 * <ol>
 *   <li><b>Nobody else already owns it.</b> Checked explicitly and backed by
 *       {@code uq_bae_verified_email}, so a race loses cleanly rather than
 *       500ing.</li>
 *   <li><b>Re-claim</b> (§2.3 rule 3): every <i>unverified</i> claim on this
 *       address, on any other account, is deleted in the same transaction.
 *       Whoever proves control wins; nobody is locked out by someone else's
 *       typing. The emptied squatter accounts are collected by the sweeper's
 *       {@code deleteNeverActivatedWithoutEmails} pass.</li>
 *   <li><b>First verified address becomes primary.</b> Sign-in is gated on the
 *       account having one, so an account that verified an address and got no
 *       primary would be permanently unable to log in.</li>
 *   <li><b>{@code activated_at}</b> is stamped once — an event timestamp for
 *       audit and for the squatter GC rule, never read as a permission.</li>
 * </ol>
 */
@Service
public class BuyerAddressClaims {

    private final BuyerAccountRepository accounts;
    private final BuyerAccountEmailRepository emails;

    public BuyerAddressClaims(BuyerAccountRepository accounts, BuyerAccountEmailRepository emails) {
        this.accounts = accounts;
        this.emails = emails;
    }

    @Transactional
    public void verifyAndClaim(BuyerAccount account, BuyerAccountEmail row, Instant now) {
        String normalized = row.getEmailNormalized();

        // Someone may have verified this address between the code being issued
        // and it being redeemed. Losing that race is a clean 409, not a 500 out
        // of the unique index.
        emails.findByVerifiedKey(normalized).ifPresent(existing -> {
            if (!existing.getBuyerAccountId().equals(account.getId())) throw alreadyClaimed();
        });

        row.markVerified(now);
        try {
            emails.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            // uq_bae_verified_email — the same race, lost to a concurrent commit.
            throw alreadyClaimed();
        }

        emails.deleteUnverifiedClaimsElsewhere(normalized, account.getId());

        if (emails.findByPrimaryMarker(account.getId()).isEmpty()) {
            row.makePrimary();
            emails.save(row);
        }

        if (account.getActivatedAt() == null) {
            account.setActivatedAt(now);
            accounts.save(account);
        }
    }

    private static ApiException alreadyClaimed() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE,
                "This address has just been verified on another account.");
    }
}
