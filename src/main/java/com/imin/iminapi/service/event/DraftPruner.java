package com.imin.iminapi.service.event;

import com.imin.iminapi.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class DraftPruner {

    private static final Logger log = LoggerFactory.getLogger(DraftPruner.class);
    private final EventRepository events;

    public DraftPruner(EventRepository events) { this.events = events; }

    @Scheduled(cron = "0 30 * * * *") // 30 minutes past every hour
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        int n = events.deleteEmptyDraftsOlderThan(cutoff);
        if (n > 0) log.info("Pruned {} empty drafts older than 24h", n);
    }
}
