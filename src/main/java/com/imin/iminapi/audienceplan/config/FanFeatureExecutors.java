package com.imin.iminapi.audienceplan.config;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Fan-feature work stays off the shared default pool, which carries the audience registry projections.
 * Both pools drop on overflow: every row is rewritten by the nightly recompute.
 */
@Configuration
public class FanFeatureExecutors {

    public static final String LIVE = "fanFeatureExecutor";
    public static final String RECOMPUTE = "fanFeatureRecomputeExecutor";

    /** Live per-membership recomputes. Rejections surface to the projector, which releases its coalescing key. */
    @Bean(name = LIVE)
    public Executor fanFeatureExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(2);
        // ponytail: 1000 queued memberships per replica; beyond that live updates wait for the nightly pass.
        exec.setQueueCapacity(1000);
        exec.setThreadNamePrefix("fan-feature-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.initialize();
        return exec;
    }

    /** One full pass at a time; a second trigger while one is queued is dropped, never thrown at the publisher. */
    @Bean(name = RECOMPUTE)
    public Executor fanFeatureRecomputeExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(1);
        exec.setThreadNamePrefix("fan-feature-recompute-");
        exec.setRejectedExecutionHandler((task, executor) -> LoggerFactory.getLogger(FanFeatureExecutors.class)
                .info("FanFeatureExecutors: a full recompute is already queued, dropping this trigger"));
        exec.initialize();
        return exec;
    }
}
