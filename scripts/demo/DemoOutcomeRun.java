package com.imin.iminapi.demolocal;

import com.imin.iminapi.audienceplan.service.OutcomeCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * LOCAL DEMO ONLY, never under src/main: seed.sh compiles this onto the demo api's loader path so the 08:00 outcome
 * collection runs once at boot instead of waiting for the cron. It adds no endpoint.
 */
@Component
public class DemoOutcomeRun {

    private static final Logger log = LoggerFactory.getLogger(DemoOutcomeRun.class);

    private final OutcomeCollector collector;

    public DemoOutcomeRun(OutcomeCollector collector) {
        this.collector = collector;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void collect() {
        try {
            log.info("DemoOutcomeRun: done {}", collector.run());
        } catch (Exception e) {
            log.error("DemoOutcomeRun: failed {} {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
