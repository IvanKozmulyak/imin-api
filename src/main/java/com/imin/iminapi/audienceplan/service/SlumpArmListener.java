package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.marketing.model.MomentumTriggerType;
import com.imin.iminapi.marketing.service.MomentumTriggered;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Schedules an event's armed slump invitation drafts when Momentum fires SLUMP for it. */
@Component
public class SlumpArmListener {

    private static final Logger log = LoggerFactory.getLogger(SlumpArmListener.class);

    private final TimingArmScheduler scheduler;

    public SlumpArmListener(TimingArmScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Synchronous so the trigger is never dropped; a failure is logged, never rethrown into the evaluator. */
    @EventListener
    public void onMomentumTriggered(MomentumTriggered triggered) {
        if (!MomentumTriggerType.SLUMP.wireValue().equals(triggered.trigger())) return;
        try {
            int n = scheduler.fireSlump(triggered.orgId(), triggered.eventId());
            if (n > 0) log.info("SlumpArmListener: scheduled {} slump arm(s) of event {}", n, triggered.eventId());
        } catch (Exception e) {
            log.warn("SlumpArmListener: slump arms of event {} not scheduled: {} {}", triggered.eventId(),
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }
}
