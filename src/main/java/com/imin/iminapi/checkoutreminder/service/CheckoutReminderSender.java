package com.imin.iminapi.checkoutreminder.service;

import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminderReason;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminderStatus;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.service.QuietHours;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.security.ApiException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sends due reminders, at most once each. A row is claimed (committed as SENDING) before the
 * provider call and marked after it; a claim left SENDING past {@link #STALE_CLAIM} may already have
 * reached the buyer, so it ends as SEND_UNKNOWN and is never sent again. A failed provider call is
 * retried only when it proves the email never reached the provider (connection refused, unknown host,
 * HTTP 429); a timeout, a 5xx or anything unrecognised may follow an accepted send, so it ends as
 * SEND_UNKNOWN too. Every check is re-run at send time through {@link CheckoutReminderGate}.
 */
@Component
public class CheckoutReminderSender {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReminderSender.class);
    static final Duration STALE_CLAIM = Duration.ofMinutes(10);
    static final int BATCH = 50;
    /** Provider calls that never reached Resend, tolerated before the reminder is dropped as SEND_FAILED. */
    static final int MAX_ATTEMPTS = 3;

    private final CheckoutReminderProperties props;
    private final CheckoutReminderRepository reminders;
    private final CheckoutReminderGate gate;
    private final CheckoutReminderEmail email;
    private final CampaignEmailProvider provider;
    private final UnsubscribeTokenService tokens;
    private final MarketingEmailProperties emailProps;
    private final QuietHours quietHours;
    private final Clock clock;

    public CheckoutReminderSender(CheckoutReminderProperties props, CheckoutReminderRepository reminders,
                                  CheckoutReminderGate gate, CheckoutReminderEmail email,
                                  CampaignEmailProvider provider, UnsubscribeTokenService tokens,
                                  MarketingEmailProperties emailProps, QuietHours quietHours, Clock clock) {
        this.props = props;
        this.reminders = reminders;
        this.gate = gate;
        this.email = email;
        this.provider = provider;
        this.tokens = tokens;
        this.emailProps = emailProps;
        this.quietHours = quietHours;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    @SchedulerLock(name = "CheckoutReminderSender.tick", lockAtLeastFor = "PT10S", lockAtMostFor = "PT5M")
    public void tick() {
        if (!props.isEnabled()) return;
        Instant now = clock.instant();
        int unknown = reminders.markStaleSendingUnknown(now.minus(STALE_CLAIM));
        if (unknown > 0) log.warn("[checkout-reminder] {} claims went stale mid-send — SEND_UNKNOWN, not retried", unknown);
        // ponytail: one provider call per reminder; ceiling is about BATCH sends a minute per replica.
        List<UUID> claimed = reminders.claimDue(now, BATCH);
        for (UUID id : claimed) {
            try {
                process(id, now);
            } catch (Exception e) {
                // Left SENDING: the stale sweep turns it into SEND_UNKNOWN, never a second send.
                log.error("[checkout-reminder] reminder {} failed mid-send: {}", id, e.getClass().getSimpleName(), e);
            }
        }
    }

    private void process(UUID id, Instant now) {
        CheckoutReminder r = reminders.findById(id).orElse(null);
        if (r == null || r.getStatus() != CheckoutReminderStatus.SENDING) return;
        CheckoutReminderGate.Result result = gate.check(r, now);
        if (!result.passed()) {
            reminders.markNotSent(id, result.reason().name());
            log.info("[checkout-reminder] reminder {} not sent: {}", id, result.reason());
            return;
        }
        CheckoutReminderGate.Pass pass = result.pass();
        if (quietHours.isEmailQuiet(pass.org().getTimezone(), now)) {
            reminders.reschedule(id, CheckoutReminderSchedule.outOfQuietHours(now, pass.org().getTimezone()));
            return;
        }
        String unsubscribeUrl = emailProps.unsubscribeUrl(tokens.signReminder(id));
        CheckoutReminderEmail.Message m = email.build(new CheckoutReminderEmail.Facts(pass.org(), pass.event(),
                pass.tier(), r.getQty(), r.getBuyerLocale(), r.isPromoUsed(), r.getConsentProof()),
                unsubscribeUrl, false);
        CampaignEmailProvider.BatchResult sent;
        try {
            sent = provider.sendBatch(null, List.of(new CampaignEmailProvider.OutgoingEmail(
                    m.from(), r.getEmailNormalized(), m.subject(), m.html(), m.text(), unsubscribeUrl)));
        } catch (CampaignEmailProvider.CampaignRejected e) {
            reminders.markNotSent(id, CheckoutReminderReason.SEND_FAILED.name());
            log.error("[checkout-reminder] provider refused reminder {} with HTTP {}", id, e.upstreamStatus());
            return;
        } catch (ApiException e) {
            if (!neverReachedProvider(e)) {
                reminders.markNotSent(id, CheckoutReminderReason.SEND_UNKNOWN.name());
                log.error("[checkout-reminder] reminder {} may have been sent; not retried: {}", id, e.getMessage());
                return;
            }
            if (r.getAttempts() + 1 >= MAX_ATTEMPTS) {
                reminders.markNotSent(id, CheckoutReminderReason.SEND_FAILED.name());
                log.error("[checkout-reminder] reminder {} dropped after {} failed attempts: {}", id,
                        MAX_ATTEMPTS, e.getMessage());
            } else {
                reminders.releaseForRetry(id);
                log.warn("[checkout-reminder] reminder {} send failed, retrying next tick: {}", id, e.getMessage());
            }
            return;
        }
        if (sent != null && sent.rejected(0)) {
            // The provider refused this address on its own; nothing left.
            reminders.markNotSent(id, CheckoutReminderReason.SEND_FAILED.name());
            log.error("[checkout-reminder] provider refused reminder {}: {}", id, sent.rowErrors().get(0));
            return;
        }
        if (sent == null || sent.unconfirmed(0)) {
            // The answer does not say whether it went out: never retried, like any other unknown outcome.
            reminders.markNotSent(id, CheckoutReminderReason.SEND_UNKNOWN.name());
            log.error("[checkout-reminder] provider's answer does not account for reminder {}; not retried", id);
            return;
        }
        reminders.markSent(id, sent.idAt(0), clock.instant());
    }

    /**
     * True only when the failure proves Resend never took the email: no connection was made, or it
     * answered 429. A read timeout can come after Resend accepted the batch, so it is not proof.
     */
    static boolean neverReachedProvider(Throwable failure) {
        for (Throwable c = failure; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof ConnectException || c instanceof UnknownHostException
                    || c instanceof NoRouteToHostException) {
                return true;
            }
            if (c instanceof CampaignEmailProvider.RetryableBatchFailure rf && rf.upstreamStatus() == 429) return true;
        }
        return false;
    }
}
