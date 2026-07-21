package com.imin.iminapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated executor for post-issuance async work so a burst of Stripe
 * deliveries can't starve other {@code @Async} callers. Small pool because
 * the work is short and Resend tolerates parallelism.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "ticketEmailExecutor")
    public Executor ticketEmailExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("ticket-email-");
        exec.initialize();
        return exec;
    }

    /**
     * Dedicated pool for marketing campaign batch sends (spec §2.5). Kept SEPARATE
     * from ticketEmailExecutor — that pool is corePool 2 / maxPool 4, purpose-built
     * for transactional ticket bursts; sharing it would starve ticket delivery and
     * risk deadlock under campaign batches.
     */
    @Bean(name = "campaignSendExecutor")
    public Executor campaignSendExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(32);
        exec.setThreadNamePrefix("campaign-send-");
        exec.initialize();
        return exec;
    }

    /**
     * Predictor Stage-0 scoring runs (spec §4.1: async, non-blocking; §7.3: existing job
     * pattern, no new infra). Small on purpose — one LLM call per run, per-user throttled
     * and quota-capped upstream, so depth beyond 2 threads would only mask an abuse pattern.
     */
    @Bean(name = "predictorScoreExecutor")
    public Executor predictorScoreExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(16);
        exec.setThreadNamePrefix("predictor-score-");
        exec.initialize();
        return exec;
    }
}
