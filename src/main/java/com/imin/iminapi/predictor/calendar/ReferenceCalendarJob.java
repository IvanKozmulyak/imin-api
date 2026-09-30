package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Weekly sync of every calendar source into reference_calendar; also at boot while the table is empty. */
@Component
public class ReferenceCalendarJob {

    private static final Logger log = LoggerFactory.getLogger(ReferenceCalendarJob.class);
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final List<CalendarSource> sources;
    private final ReferenceCalendarWriter writer;
    private final ReferenceCalendarEntryRepository repository;
    private final CalendarSyncProperties props;
    private final Clock clock;
    /** This bean through its proxy, so the startup run takes the ShedLock too. */
    private final ObjectProvider<ReferenceCalendarJob> self;
    private final Executor executor;

    public ReferenceCalendarJob(List<CalendarSource> sources, ReferenceCalendarWriter writer,
                                ReferenceCalendarEntryRepository repository, CalendarSyncProperties props,
                                Clock clock, ObjectProvider<ReferenceCalendarJob> self,
                                @Qualifier("referenceCalendarSyncExecutor") Executor executor) {
        this.sources = sources;
        this.writer = writer;
        this.repository = repository;
        this.props = props;
        this.clock = clock;
        this.self = self;
        this.executor = executor;
    }

    /** Off the boot thread: a slow or failing source never holds up startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            if (!props.getSyncEnabled() || repository.count() > 0) return;
            executor.execute(() -> {
                try {
                    self.getObject().run();
                } catch (Exception e) {
                    log.warn("ReferenceCalendarJob startup run failed (weekly cron will retry): {}", e.toString());
                }
            });
        } catch (Exception e) {
            log.warn("ReferenceCalendarJob startup check failed (weekly cron will retry): {}", e.toString());
        }
    }

    @Scheduled(cron = "0 30 4 * * SUN", zone = "Europe/Paris")
    @SchedulerLock(name = "reference_calendar_sync", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        if (!props.getSyncEnabled()) return;
        LocalDate today = LocalDate.now(clock.withZone(PARIS));
        int rows = 0;
        List<String> failed = new ArrayList<>();
        for (CalendarSource source : sources) {
            try {
                for (CalendarSource.Batch batch : source.fetch(today)) rows += writer.replace(batch);
            } catch (Exception e) {
                failed.add(source.key());
                log.warn("ReferenceCalendarJob: source {} failed: {}", source.key(), e.toString());
            }
        }
        log.info("ReferenceCalendarJob: done, {} rows, failed sources {}", rows, failed);
    }
}
