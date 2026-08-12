package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.BuyerProperties;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerEmailVerificationCodeRepository;
import com.imin.iminapi.buyer.repository.BuyerPasswordResetTokenRepository;
import com.imin.iminapi.buyer.repository.BuyerSessionRepository;
import com.imin.iminapi.buyer.repository.BuyerVerificationAttemptRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Hourly GC for the buyer tables (epic §4.5). Every one of them grows a row per
 * attempt, including rate-limited ones, so the sweeper ships with the feature
 * rather than after it.
 *
 * <p>Five passes, in a deliberate order:
 *
 * <ol>
 *   <li>sessions past {@code expires_at}, and sessions revoked more than
 *       {@code revoked-session-retention-days} ago (kept that long so "was I
 *       logged out or did it expire?" is answerable);</li>
 *   <li>verification codes past {@code expires_at};</li>
 *   <li>password-reset tokens past {@code expires_at};</li>
 *   <li>verification-attempt rows older than the lockout window needs;</li>
 *   <li>unverified {@code buyer_account_emails} older than 72 hours — <b>then</b>
 *       any account left with zero email rows that never had a verified address.
 *       That is the squatter's account (§2.3 rule 2) and nothing of value is in
 *       it. The order matters: the account pass must run after the email pass
 *       inside the same transaction, or the orphan is only collected an hour
 *       later.</li>
 * </ol>
 *
 * <p>{@code @SchedulerLock} serializes the run across replicas, as every other
 * sweeper in this tree does.
 */
@Component
public class BuyerAccountSweeper {

    private static final Logger log = LoggerFactory.getLogger(BuyerAccountSweeper.class);

    private final BuyerSessionRepository sessions;
    private final BuyerEmailVerificationCodeRepository codes;
    private final BuyerPasswordResetTokenRepository resetTokens;
    private final BuyerVerificationAttemptRepository attempts;
    private final BuyerAccountEmailRepository emails;
    private final BuyerAccountRepository accounts;
    private final BuyerProperties props;

    public BuyerAccountSweeper(BuyerSessionRepository sessions,
                               BuyerEmailVerificationCodeRepository codes,
                               BuyerPasswordResetTokenRepository resetTokens,
                               BuyerVerificationAttemptRepository attempts,
                               BuyerAccountEmailRepository emails,
                               BuyerAccountRepository accounts,
                               BuyerProperties props) {
        this.sessions = sessions;
        this.codes = codes;
        this.resetTokens = resetTokens;
        this.attempts = attempts;
        this.emails = emails;
        this.accounts = accounts;
        this.props = props;
    }

    /** Hourly at :23 — offset from the round-hour jobs so DB load spreads. */
    @Scheduled(cron = "0 23 * * * *")
    @SchedulerLock(name = "BuyerAccountSweeper.sweep", lockAtLeastFor = "PT1M", lockAtMostFor = "PT15M")
    @Transactional
    public void sweep() {
        Instant now = Instant.now();

        int expiredSessions = sessions.deleteExpiredBefore(now);
        int revokedSessions = sessions.deleteRevokedBefore(
                now.minus(Duration.ofDays(props.getRevokedSessionRetentionDays())));
        int expiredCodes = codes.deleteExpiredBefore(now);
        int expiredResets = resetTokens.deleteExpiredBefore(now);
        int oldAttempts = attempts.deleteOlderThan(
                now.minus(Duration.ofHours(props.getVerificationAttemptRetentionHours())));

        Instant unverifiedCutoff = now.minus(Duration.ofHours(props.getUnverifiedEmailTtlHours()));
        int staleEmails = emails.deleteUnverifiedOlderThan(unverifiedCutoff);
        int orphanAccounts = accounts.deleteNeverActivatedWithoutEmails(unverifiedCutoff);

        if (expiredSessions + revokedSessions + expiredCodes + expiredResets
                + oldAttempts + staleEmails + orphanAccounts > 0) {
            log.info("[buyer] sweep removed sessions={}(+{} revoked) codes={} resets={} attempts={} "
                            + "unverified-emails={} orphan-accounts={}",
                    expiredSessions, revokedSessions, expiredCodes, expiredResets,
                    oldAttempts, staleEmails, orphanAccounts);
        }
    }
}
