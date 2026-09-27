package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.PlanRefreshExecutor;
import com.imin.iminapi.marketing.service.MomentumTriggered;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Refreshes an event's audience plan when Momentum fires for it, off the evaluator's thread. */
@Component
public class MomentumPlanRefresh {

    private static final Logger log = LoggerFactory.getLogger(MomentumPlanRefresh.class);

    private final PlanService plans;

    public MomentumPlanRefresh(PlanService plans) {
        this.plans = plans;
    }

    /** The evaluator is not transactional, so this listens directly; a failure is logged, never rethrown. */
    @EventListener
    @Async(PlanRefreshExecutor.NAME)
    public void onMomentumTriggered(MomentumTriggered triggered) {
        try {
            PlanService.Refresh r = plans.refresh(triggered.eventId());
            log.info("MomentumPlanRefresh: {} on event {} -> plan {}", triggered.trigger(), triggered.eventId(), r);
        } catch (Exception e) {
            log.warn("MomentumPlanRefresh: plan refresh failed for event {}: {} {}", triggered.eventId(),
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }
}
