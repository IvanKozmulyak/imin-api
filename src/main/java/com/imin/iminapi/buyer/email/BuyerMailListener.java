package com.imin.iminapi.buyer.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the {@link BuyerMailEvents} after the transaction that raised them has
 * committed, on the shared mail pool — the shape
 * {@code TicketIssuanceEmailer} and {@code RefundRequestEmailer} already use.
 *
 * <p>AFTER_COMMIT is not a detail: it is what stops a signup that later rolls
 * back from having mailed a code for an account that does not exist, and it is
 * what gets the Resend round trip out of the credential transaction.
 * {@code @Async} then gets it off the request thread as well, so a slow Resend
 * costs a mail thread rather than a worker.
 *
 * <p>Failures are logged and swallowed, exactly as the services did inline: a
 * Resend outage must not change the status code, the body or (materially) the
 * timing of flows whose whole contract is that every branch looks the same.
 * Nothing here can fail a request anyway — the response has already been
 * written by the time this runs.
 */
@Component
public class BuyerMailListener {

    private static final Logger log = LoggerFactory.getLogger(BuyerMailListener.class);

    private final BuyerAccountEmailer emailer;

    public BuyerMailListener(BuyerAccountEmailer emailer) {
        this.emailer = emailer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onVerificationCode(BuyerMailEvents.VerificationCode ev) {
        swallow("verification code", () -> emailer.sendVerificationCode(
                ev.to(), ev.locale(), ev.code(), ev.expiresInMinutes()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onAccountExistsNotice(BuyerMailEvents.AccountExistsNotice ev) {
        swallow("account-exists notice", () -> emailer.sendAccountExistsNotice(ev.to(), ev.locale()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onPasswordReset(BuyerMailEvents.PasswordReset ev) {
        swallow("password reset", () -> emailer.sendPasswordReset(
                ev.to(), ev.locale(), emailer.resetUrl(ev.resetToken()), ev.expiresInMinutes()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onPasswordChanged(BuyerMailEvents.PasswordChanged ev) {
        swallow("password-changed notice", () -> emailer.sendPasswordChanged(ev.to(), ev.locale()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onDeletionScheduled(BuyerMailEvents.DeletionScheduled ev) {
        swallow("deletion notice", () -> emailer.sendDeletionScheduled(
                ev.to(), ev.locale(), ev.deleteAt()));
    }

    private void swallow(String what, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            log.error("[buyer] {} email send failed: {}", what, e.getMessage(), e);
        }
    }
}
