package com.imin.iminapi.config;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated executor for post-issuance async work so a burst of Stripe
 * deliveries can't starve other {@code @Async} callers. Small pool because
 * the work is short and Resend tolerates parallelism.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Overflow runs on the CALLER, deliberately.
     *
     * <p>{@code TicketIssuanceEmailer.onTicketsIssued} is
     * {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async} on this pool,
     * so the submit happens inside the afterCommit synchronization — on the Stripe
     * webhook's thread, after the Order and the {@code processed_webhook_events} dedup
     * row have already committed. Under the default {@code AbortPolicy} a full queue
     * threw {@code TaskRejectedException} out of {@code commit()} into the webhook
     * response, and Stripe's retry then short-circuited at the dedup marker: the
     * buyer's ticket email was lost, permanently. The pool is shared with
     * {@code SalesMilestoneNotifier} and {@code RefundConfirmationEmailer}, so a
     * refund or milestone burst is enough to fill it.
     *
     * <p>Note the asymmetry with {@code venueGeocodingExecutor} below, which discards:
     * a dropped map pin leaves a NULL every consumer handles, a dropped ticket email
     * leaves a paying buyer with nothing. Back-pressuring the commit thread is the
     * cheaper failure. (The durable fix is an outbox row written inside the issuance
     * transaction and drained by a job; this closes the loss until then.)
     */
    @Bean(name = "ticketEmailExecutor")
    public Executor ticketEmailExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("ticket-email-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
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
