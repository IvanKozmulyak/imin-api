package com.imin.iminapi.predictor.sources.wikimedia;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.imin.iminapi.predictor.repository.WikimediaPageviewMonthRepository;
import com.imin.iminapi.predictor.rules.QuestionBankLoader;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.MonthViews;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.Result;
import com.imin.iminapi.predictor.sources.wikimedia.WikimediaPageviewsClient.WikimediaRateLimitedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikimediaPageviewsJobTest {

    // 22:30Z on 30 Sep is already 1 Oct in Paris, so the last full month is September.
    private static final Instant NOW = Instant.parse("2026-09-30T22:30:00Z");
    private static final YearMonth FROM = YearMonth.of(2025, 9);
    private static final YearMonth TO = YearMonth.of(2026, 9);

    private static final WikimediaArticles ARTICLES = WikimediaArticles.parse(new ByteArrayInputStream("""
            version: 1
            verified_on: 2026-10-01
            languages: { FR: fr, DE: de }
            buckets:
              "house & techno":
                sub_genres:
                  techno: { fr: Techno, de: Techno }
            """.getBytes(StandardCharsets.UTF_8)), QuestionBankLoader.load(new DefaultResourceLoader()));

    private final WikimediaPageviewsClient client = mock(WikimediaPageviewsClient.class);
    private final WikimediaPageviewsWriter writer = mock(WikimediaPageviewsWriter.class);
    private final WikimediaPageviewMonthRepository repository = mock(WikimediaPageviewMonthRepository.class);
    private final SourceGates gates = mock(SourceGates.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<WikimediaPageviewsJob> self = mock(ObjectProvider.class);
    private final Logger jobLog = (Logger) LoggerFactory.getLogger(WikimediaPageviewsJob.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    {
        logs.start();
        jobLog.addAppender(logs);
        when(gates.isOn("wikimedia")).thenReturn(true);
    }

    @AfterEach
    void detach() {
        jobLog.detachAppender(logs);
    }

    private WikimediaPageviewsJob job(Instant now, Executor executor) {
        WikimediaPageviewsJob job = new WikimediaPageviewsJob(ARTICLES, client, writer, repository, gates,
                Clock.fixed(now, ZoneOffset.UTC), self, executor);
        when(self.getObject()).thenReturn(job);
        return job;
    }

    private WikimediaPageviewsJob job() {
        return job(NOW, Runnable::run);
    }

    private static Result months(long... views) {
        List<MonthViews> out = new java.util.ArrayList<>();
        for (int i = 0; i < views.length; i++) out.add(new MonthViews(FROM.plusMonths(i), views[i]));
        return new Result(false, out);
    }

    @Test
    void gateOffMakesNoCall() {
        when(gates.isOn("wikimedia")).thenReturn(false);

        job().run();
        job().onStartup();

        verify(gates, org.mockito.Mockito.atLeast(2)).isOn("wikimedia");
        verify(client, never()).fetch(anyString(), anyString(), any(), any());
        verify(repository, never()).count();
    }

    @Test
    void runFetchesEachArticleOverThirteenMonthsEndingLastMonth() {
        Result fr = months(1, 2, 3);
        Result de = months(4);
        when(client.fetch("fr.wikipedia", "Techno", FROM, TO)).thenReturn(fr);
        when(client.fetch("de.wikipedia", "Techno", FROM, TO)).thenReturn(de);

        job().run();

        verify(writer).upsert("fr.wikipedia", "Techno", fr.months(), NOW);
        verify(writer).upsert("de.wikipedia", "Techno", de.months(), NOW);
        assertThat(FROM.until(TO, java.time.temporal.ChronoUnit.MONTHS) + 1).isEqualTo(13);
    }

    @Test
    void rangeCrossesYearEnd() {
        Instant jan = Instant.parse("2026-01-15T10:00:00Z");
        when(client.fetch(anyString(), anyString(), any(), any())).thenReturn(months(1));

        job(jan, Runnable::run).run();

        verify(client).fetch("fr.wikipedia", "Techno", YearMonth.of(2024, 12), YearMonth.of(2025, 12));
        verify(client).fetch("de.wikipedia", "Techno", YearMonth.of(2024, 12), YearMonth.of(2025, 12));
    }

    @Test
    void oneArticleFailureDoesNotStopOthers() {
        Result de = months(4);
        when(client.fetch("fr.wikipedia", "Techno", FROM, TO)).thenThrow(new IllegalStateException("boom"));
        when(client.fetch("de.wikipedia", "Techno", FROM, TO)).thenReturn(de);

        job().run();

        verify(writer).upsert("de.wikipedia", "Techno", de.months(), NOW);
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    void tooManyRequestsStopsTheRun() {
        when(client.fetch("fr.wikipedia", "Techno", FROM, TO)).thenThrow(new WikimediaRateLimitedException("429"));

        job().run();

        verify(client).fetch("fr.wikipedia", "Techno", FROM, TO);
        verify(client, never()).fetch(eq("de.wikipedia"), anyString(), any(), any());
        verify(writer, never()).upsert(anyString(), anyString(), anyList(), any());
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("rate limited"));
    }

    @Test
    void rateLimitedOnFirstArticleLogsErrorWithThrowable() {
        WikimediaRateLimitedException limited = new WikimediaRateLimitedException("429");
        when(client.fetch("fr.wikipedia", "Techno", FROM, TO)).thenThrow(limited);

        job().run();

        verify(client).fetch("fr.wikipedia", "Techno", FROM, TO);
        ILoggingEvent error = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
        assertThat(((ThrowableProxy) error.getThrowableProxy()).getThrowable()).isSameAs(limited);
    }

    @Test
    void allArticlesFailedLogsErrorWithThrowable() {
        IllegalStateException last = new IllegalStateException("down");
        when(client.fetch("fr.wikipedia", "Techno", FROM, TO)).thenThrow(new IllegalStateException("first"));
        when(client.fetch("de.wikipedia", "Techno", FROM, TO)).thenThrow(last);

        job().run();

        verify(client).fetch("de.wikipedia", "Techno", FROM, TO);
        ILoggingEvent error = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
        assertThat(error.getThrowableProxy()).isNotNull();
        assertThat(((ThrowableProxy) error.getThrowableProxy()).getThrowable()).isSameAs(last);
    }

    @Test
    void missingArticleWritesNothing() {
        Result de = months(4);
        when(client.fetch("fr.wikipedia", "Techno", FROM, TO)).thenReturn(Result.notFound());
        when(client.fetch("de.wikipedia", "Techno", FROM, TO)).thenReturn(de);

        job().run();

        verify(writer, never()).upsert(eq("fr.wikipedia"), anyString(), anyList(), any());
        verify(writer).upsert("de.wikipedia", "Techno", de.months(), NOW);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("Techno"));
    }

    @Test
    void startupSeedsOnlyWhenEmpty() {
        when(client.fetch(anyString(), anyString(), any(), any())).thenReturn(months(1));
        when(repository.count()).thenReturn(0L);

        job().onStartup();

        verify(self).getObject();
        verify(client).fetch("fr.wikipedia", "Techno", FROM, TO);
    }

    @Test
    void startupSkipsWhenRowsExist() {
        when(repository.count()).thenReturn(26L);

        job().onStartup();

        verify(repository).count();
        verify(self, never()).getObject();
        verify(client, never()).fetch(anyString(), anyString(), any(), any());
    }

    @Test
    void startupExecutorRejectionIsSwallowed() {
        when(repository.count()).thenReturn(0L);

        job(NOW, r -> { throw new RejectedExecutionException("full"); }).onStartup();

        verify(repository).count();
        verify(self, never()).getObject();
    }

    @Test
    void startupRunFailureIsSwallowed() {
        when(repository.count()).thenReturn(0L);
        WikimediaPageviewsJob job = job();
        when(self.getObject()).thenThrow(new IllegalStateException("no proxy"));

        job.onStartup();

        verify(self).getObject();
        verify(client, never()).fetch(anyString(), anyString(), any(), any());
    }
}
