package com.imin.iminapi.predictor.sources.wikimedia;

import com.imin.iminapi.predictor.repository.WikimediaPageviewMonthRepository;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaArticles.Article;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.Result;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.WikimediaRateLimitedException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Weekly sync of the last 13 published months of every mapped genre article into wikimedia_pageviews_month;
 * also once at boot while the table is empty. Runs only while the {@code wikimedia} source gate is on.
 */
@Component
public class WikimediaPageviewsJob {

    private static final Logger log = LoggerFactory.getLogger(WikimediaPageviewsJob.class);
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final String GATE = "wikimedia";
    static final int MONTHS_BACK = 12;

    private final WikimediaArticles articles;
    private final WikimediaPageviewsClient client;
    private final WikimediaPageviewsWriter writer;
    private final WikimediaPageviewMonthRepository repository;
    private final SourceGates gates;
    private final Clock clock;
    /** This bean through its proxy, so the startup run takes the ShedLock too. */
    private final ObjectProvider<WikimediaPageviewsJob> self;
    private final Executor executor;

    public WikimediaPageviewsJob(WikimediaArticles articles, WikimediaPageviewsClient client,
                                 WikimediaPageviewsWriter writer, WikimediaPageviewMonthRepository repository,
                                 SourceGates gates, Clock clock, ObjectProvider<WikimediaPageviewsJob> self,
                                 @Qualifier("wikimediaSyncExecutor") Executor executor) {
        this.articles = articles;
        this.client = client;
        this.writer = writer;
        this.repository = repository;
        this.gates = gates;
        this.clock = clock;
        this.self = self;
        this.executor = executor;
    }

    /** Off the boot thread: a slow or failing upstream never holds up startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            if (!gates.isOn(GATE) || repository.count() != 0) return;
            executor.execute(() -> {
                try {
                    self.getObject().run();
                } catch (Exception e) {
                    log.warn("WikimediaPageviewsJob startup run failed (weekly cron will retry): {}", e.toString());
                }
            });
        } catch (Exception e) {
            log.warn("WikimediaPageviewsJob startup check failed (weekly cron will retry): {}", e.toString());
        }
    }

    @Scheduled(cron = "0 15 5 * * MON", zone = "Europe/Paris")
    @SchedulerLock(name = "wikimedia_pageviews_sync", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        if (!gates.isOn(GATE)) return;
        YearMonth to = YearMonth.from(LocalDate.now(clock.withZone(PARIS))).minusMonths(1);
        YearMonth from = to.minusMonths(MONTHS_BACK);
        List<Article> all = articles.distinctArticles();
        int written = 0;
        int stored = 0;
        int failed = 0;
        Exception last = null;
        for (Article a : all) {
            try {
                Result r = client.fetch(a.project(), a.title(), from, to);
                if (r.missing()) {
                    log.warn("WikimediaPageviewsJob: no pageviews for {} {} (check the title)", a.project(), a.title());
                    continue;
                }
                written += writer.upsert(a.project(), a.title(), r.months(), clock.instant());
                stored++;
            } catch (WikimediaRateLimitedException e) {
                log.warn("WikimediaPageviewsJob: rate limited at {} {}, stopping this run", a.project(), a.title());
                failed++;
                last = e;
                break;
            } catch (Exception e) {
                failed++;
                last = e;
                log.warn("WikimediaPageviewsJob: {} {} failed: {}", a.project(), a.title(), e.toString());
            }
        }
        if (stored == 0 && failed > 0) {
            log.error("WikimediaPageviewsJob: nothing stored, {} of {} articles failed", failed, all.size(), last);
            return;
        }
        log.info("WikimediaPageviewsJob: done {}..{}, {} rows, {} of {} articles failed", from, to, written, failed,
                all.size());
    }
}
