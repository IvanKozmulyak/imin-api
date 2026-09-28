package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.util.LogSafe;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Backstop for invite-on-publish: every 15 minutes, re-runs intents whose publish run crashed or never started. */
@Component
public class InviteOnPublishSweeper {

    private static final Logger log = LoggerFactory.getLogger(InviteOnPublishSweeper.class);

    private final InviteOnPublishService invites;

    public InviteOnPublishSweeper(InviteOnPublishService invites) {
        this.invites = invites;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 300_000)
    @SchedulerLock(name = "audience_plan_invite_on_publish_sweep", lockAtMostFor = "PT14M", lockAtLeastFor = "PT1M")
    public void sweep() {
        try {
            int n = invites.sweepStale();
            if (n > 0) log.info("InviteOnPublishSweeper: re-ran {} invite-on-publish intent(s)", n);
        } catch (Exception e) {
            log.error("InviteOnPublishSweeper: pass failed (the next one retries): {} {}",
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }
}
