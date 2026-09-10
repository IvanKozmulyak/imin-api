package com.imin.iminapi.buyer.email;

import java.time.Instant;

/**
 * The account emails a buyer flow asks for, as events rather than calls.
 *
 * <p>Every one of these used to be a synchronous Resend round trip made from
 * inside the {@code @Transactional} service method that caused it, which is the
 * thing {@code BuyerOrderActionsController} states the rule against: holding a
 * connection open across an outbound HTTP request is how a slow third party
 * turns into an exhausted pool, and these are the highest-volume unauthenticated
 * buyer endpoints there are. Publishing instead lets
 * {@link BuyerMailListener} send after the commit, off the request thread.
 *
 * <p>Each record carries everything the send needs, because the listener runs
 * with no transaction and must not go back to the database for it.
 */
public final class BuyerMailEvents {

    private BuyerMailEvents() {}

    /** The six-digit code for a fresh signup or a resend. */
    public record VerificationCode(String to, String locale, String code, int expiresInMinutes) {}

    /** The neutral "you already have an account" notice on the signup negative branch. */
    public record AccountExistsNotice(String to, String locale) {}

    /**
     * The password-reset link, carried as the raw token: the URL around it is
     * {@link BuyerAccountEmailer#resetUrl} 's to build, and the service that
     * mints the token has no other use for the emailer.
     */
    public record PasswordReset(String to, String locale, String resetToken, int expiresInMinutes) {}

    /** Told after a reset consumed a token and revoked every session. */
    public record PasswordChanged(String to, String locale) {}

    /**
     * One per verified address on the account, not one per account: the person
     * scheduling the deletion may not be the owner.
     */
    public record DeletionScheduled(String to, String locale, Instant deleteAt) {}
}
