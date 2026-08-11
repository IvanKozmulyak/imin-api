package com.imin.iminapi.config;

import org.slf4j.LoggerFactory;
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
     * Venue geocoding (V80). <b>Exactly one thread, on purpose.</b>
     *
     * <p>Nominatim's usage policy is ~1 request/second and this pool is the only caller, so a
     * second thread could not do useful work — it would sit in the client's throttle sleep —
     * while doubling the chance of the burst that gets an IP blocked. One thread makes the
     * per-replica rate ceiling exactly {@code 1 / minIntervalMillis} by construction, with the
     * client-side throttle as the second line of defence.
     *
     * <p><b>This is also what stops the unbounded-thread failure mode.</b> An unqualified
     * {@code @Async} resolves to {@code SimpleAsyncTaskExecutor} — a brand-new platform thread
     * per task, no pool, no cap — because the three {@code Executor} beans above make Boot's
     * {@code TaskExecutorConfigurations} back off from auto-configuring one. A bulk venue edit
     * would then spawn a thread per event, each holding a ~9.4s HTTP call. The geocoding
     * listener names THIS executor for that reason; do not drop the qualifier.
     *
     * <p>Overflow DISCARDS with a log line rather than throwing: the caller is an
     * {@code AFTER_COMMIT} transaction listener, and a {@code TaskRejectedException} there
     * propagates out of the commit into the organizer's response — failing a write that already
     * succeeded, over a best-effort map pin. A dropped geocode leaves coordinates NULL, which
     * every consumer already handles.
     */
    @Bean(name = "venueGeocodingExecutor")
    public Executor venueGeocodingExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("venue-geocode-");
        exec.setRejectedExecutionHandler((task, executor) ->
                LoggerFactory.getLogger(AsyncConfig.class).warn(
                        "[geocode] queue full ({} deep) — dropping a lookup; coordinates stay NULL",
                        executor.getQueue().size()));
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
