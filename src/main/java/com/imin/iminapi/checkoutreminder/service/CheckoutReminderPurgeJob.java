package com.imin.iminapi.checkoutreminder.service;

import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Nightly retention for reminder rows. A row that never reached the buyer loses its address and
 * ticked sentence the day after the night (at most {@code maxKeepUnsentDays} after capture); a row
 * that may have reached them keeps both {@code keepSentDays} as proof of the tick. Status counts
 * stay; a row is deleted 13 months after capture. Runs whatever the platform switch says.
 */
@Component
public class CheckoutReminderPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReminderPurgeJob.class);

    private final CheckoutReminderRepository reminders;
    private final CheckoutReminderProperties props;
    private final Clock clock;

    public CheckoutReminderPurgeJob(CheckoutReminderRepository reminders, CheckoutReminderProperties props,
                                    Clock clock) {
        this.reminders = reminders;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "Europe/Paris")
    @SchedulerLock(name = "checkout_reminder_purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        Instant now = clock.instant();
        int unsent = reminders.wipeUnsent(now.minus(Duration.ofDays(1)),
                now.minus(Duration.ofDays(props.getMaxKeepUnsentDays())), now);
        int sent = reminders.wipeSent(now.minus(Duration.ofDays(props.getKeepSentDays())), now);
        int deleted = reminders.deleteCapturedBefore(now.atOffset(ZoneOffset.UTC).minusMonths(13).toInstant());
        log.info("[checkout-reminder] purge wiped unsent={} sent={} deleted={}", unsent, sent, deleted);
    }
}
