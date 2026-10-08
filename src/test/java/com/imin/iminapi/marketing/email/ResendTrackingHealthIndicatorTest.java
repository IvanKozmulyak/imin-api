package com.imin.iminapi.marketing.email;

import com.imin.iminapi.email.EmailProperties;
import com.resend.Resend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Tracking must be off on the marketing domain (Resend always stubbed); tracking left on is loud but must not
 * fail the deploy healthcheck, while a real DOWN still must.
 */
class ResendTrackingHealthIndicatorTest {

    /** Direct executor by default, so a probe's refresh lands before it returns. */
    private static class Stub extends ResendTrackingHealthIndicator {
        final AtomicInteger calls = new AtomicInteger();
        volatile Object answer;

        Stub(String apiKey, String fromAddress, Object answer) {
            this(apiKey, fromAddress, answer, Runnable::run, Clock.systemUTC());
        }

        Stub(String apiKey, String fromAddress, Object answer, Executor refresher, Clock clock) {
            super(new Resend("unused"), email(apiKey), marketing(fromAddress), refresher, clock);
            this.answer = answer;
        }

        @Override
        protected TrackingState fetchTracking(String domain) throws Exception {
            calls.incrementAndGet();
            if (answer instanceof Exception e) throw e;
            return (TrackingState) answer;
        }
    }

    /** Holds submitted refreshes until the test runs them. */
    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> queued = new ArrayDeque<>();
        @Override public void execute(Runnable r) { queued.add(r); }
        void runAll() { while (!queued.isEmpty()) queued.poll().run(); }
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static EmailProperties email(String key) {
        EmailProperties p = new EmailProperties();
        p.setApiKey(key);
        return p;
    }

    private static MarketingEmailProperties marketing(String from) {
        MarketingEmailProperties p = new MarketingEmailProperties();
        p.setFromAddress(from);
        return p;
    }

    private static Stub stub(Object answer) {
        return new Stub("re_key", "contact@imin.support", answer);
    }

    /** Resend's answer → the indicator's verdict: never DOWN, so a tracking or lookup problem cannot fail the deploy. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("verdicts")
    void verdict(String name, Object answer, Status expected, Map<String, Object> details, List<String> absentKeys) {
        Health h = stub(answer).health();

        assertThat(h.getStatus()).isEqualTo(expected).isNotEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsAllEntriesOf(details).doesNotContainKeys(absentKeys.toArray(String[]::new));
    }

    static Stream<Arguments> verdicts() {
        Status on = ResendTrackingHealthIndicator.TRACKING_ON;
        return Stream.of(
                Arguments.of("open tracking on", new ResendTrackingHealthIndicator.TrackingState(true, false), on,
                        Map.of("domain", "imin.support", "openTracking", true, "clickTracking", false), List.of()),
                Arguments.of("click tracking on", new ResendTrackingHealthIndicator.TrackingState(false, true), on,
                        Map.of("clickTracking", true), List.of()),
                Arguments.of("one flag on, the other unreported", new ResendTrackingHealthIndicator.TrackingState(null, true), on,
                        Map.of("clickTracking", true), List.of("openTracking")),
                Arguments.of("both off", new ResendTrackingHealthIndicator.TrackingState(false, false), Status.UP,
                        Map.of("openTracking", false, "clickTracking", false), List.of()),
                Arguments.of("tracking not reported", new ResendTrackingHealthIndicator.TrackingState(false, null), Status.UNKNOWN,
                        Map.of("reason", "tracking_not_reported"), List.of()),
                Arguments.of("domain not in the account", null, Status.UNKNOWN,
                        Map.of("reason", "domain_not_found"), List.of()),
                Arguments.of("Resend failure", new IllegalStateException("401 missing domains scope"), Status.UNKNOWN,
                        Map.of("reason", "unavailable"), List.of()));
    }

    @ParameterizedTest(name = "api key \"{0}\", from \"{1}\"")
    @CsvSource({"'', contact@imin.support", "re_key, ''"})
    void missingConfig_isUnknown_withoutCallingResend(String apiKey, String fromAddress) {
        Stub s = new Stub(apiKey, fromAddress, new ResendTrackingHealthIndicator.TrackingState(true, true));
        Health h = s.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
        if (apiKey.isEmpty()) assertThat(h.getDetails()).containsEntry("reason", "not_configured");
        assertThat(s.calls).hasValue(0);
    }

    @Test
    void resultIsCachedWithinTtl() {
        Stub s = stub(new ResendTrackingHealthIndicator.TrackingState(false, false));
        s.health();
        s.health();
        assertThat(s.calls).hasValue(1);
    }

    @Test
    void interruptedLookup_isUnknown_andKeepsTheInterruptFlag() {
        Health h = stub(new InterruptedException()).health();
        try {
            assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(h.getDetails()).containsEntry("reason", "unavailable");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void firstProbe_beforeAnyResult_isPendingUnknown_andRefreshIsSingleFlight() {
        ManualExecutor exec = new ManualExecutor();
        Stub s = new Stub("re_key", "contact@imin.support",
                new ResendTrackingHealthIndicator.TrackingState(false, false), exec, Clock.systemUTC());

        Health first = s.health();
        s.health();
        assertThat(first.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(first.getDetails()).containsEntry("reason", "pending");
        assertThat(exec.queued).hasSize(1);
        assertThat(s.calls).hasValue(0);

        exec.runAll();
        assertThat(s.health().getStatus()).isEqualTo(Status.UP);
        assertThat(s.calls).hasValue(1);
    }

    @Test
    void staleVerdict_isServedWhileOneBackgroundRefreshRuns() {
        ManualExecutor exec = new ManualExecutor();
        MutableClock clock = new MutableClock();
        Stub s = new Stub("re_key", "contact@imin.support",
                new ResendTrackingHealthIndicator.TrackingState(false, false), exec, clock);
        s.health();
        exec.runAll();

        clock.now = clock.now.plus(ResendTrackingHealthIndicator.CACHE_TTL).plusSeconds(1);
        s.answer = new ResendTrackingHealthIndicator.TrackingState(true, false);
        assertThat(s.health().getStatus()).isEqualTo(Status.UP);
        assertThat(s.health().getStatus()).isEqualTo(Status.UP);
        assertThat(exec.queued).hasSize(1);

        exec.runAll();
        assertThat(s.health().getStatus()).isEqualTo(ResendTrackingHealthIndicator.TRACKING_ON);
        assertThat(s.calls).hasValue(2);
    }

    @Test
    void defaultRefresher_neverBlocksTheProbeOnASlowResend() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Stub s = new Stub("re_key", "contact@imin.support", null, null, Clock.systemUTC()) {
            @Override
            protected TrackingState fetchTracking(String domain) throws Exception {
                calls.incrementAndGet();
                started.countDown();
                release.await();
                return new TrackingState(true, true);
            }
        };
        try {
            Health h = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> s.health());
            assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(assertTimeoutPreemptively(Duration.ofSeconds(1), () -> s.health()).getStatus())
                    .isEqualTo(Status.UNKNOWN);

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (s.health().getStatus().equals(Status.UNKNOWN) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(s.health().getStatus()).isEqualTo(ResendTrackingHealthIndicator.TRACKING_ON);
            assertThat(s.calls).hasValue(1);
        } finally {
            release.countDown();
            s.shutdown();
        }
    }

    @Test
    void parser_readsSnakeCaseBooleans_andLeavesMissingOrNonBooleanNull() throws Exception {
        assertThat(ResendTrackingHealthIndicator.parseTracking(
                "{\"id\":\"d1\",\"open_tracking\":true,\"click_tracking\":false}"))
                .isEqualTo(new ResendTrackingHealthIndicator.TrackingState(true, false));
        assertThat(ResendTrackingHealthIndicator.parseTracking("{\"id\":\"d1\",\"click_tracking\":\"yes\"}"))
                .isEqualTo(new ResendTrackingHealthIndicator.TrackingState(null, null));
        assertThat(ResendTrackingHealthIndicator.parseTracking(null))
                .isEqualTo(new ResendTrackingHealthIndicator.TrackingState(null, null));
    }

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(Resend.class, () -> new Resend(""))
            .withBean(EmailProperties.class, () -> email(""))
            .withBean(MarketingEmailProperties.class, () -> marketing("contact@imin.support"))
            .withUserConfiguration(ResendTrackingHealthIndicator.class);

    @Test
    void registeredByDefault_andAMissingApiKeyBootsAndReportsUnknown() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(ResendTrackingHealthIndicator.class);
            // Blank key resolves to not_configured off-thread; the probe itself answers UNKNOWN either way.
            assertThat(ctx.getBean(ResendTrackingHealthIndicator.class).health().getStatus()).isEqualTo(Status.UNKNOWN);
        });
    }

    @Test
    void theTestProfileSwitchRemovesIt() {
        context.withPropertyValues("management.health.resend-tracking.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ResendTrackingHealthIndicator.class));
    }

    // The production status order and mapping, read from application.yaml rather than restated here.
    private static final WebApplicationContextRunner HEALTH = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HealthContributorRegistryAutoConfiguration.class,
                    HealthEndpointAutoConfiguration.class))
            .withPropertyValues(productionHealthStatus())
            .withPropertyValues("management.endpoints.web.exposure.include=health");

    private static String[] productionHealthStatus() {
        try {
            List<String> pairs = new ArrayList<>();
            for (PropertySource<?> ps : new YamlPropertySourceLoader()
                    .load("main", new FileSystemResource("src/main/resources/application.yaml"))) {
                for (String name : ((EnumerablePropertySource<?>) ps).getPropertyNames()) {
                    if (name.startsWith("management.endpoint.health.status.")) {
                        pairs.add(name + "=" + ps.getProperty(name));
                    }
                }
            }
            assertThat(pairs).as("health status order and mapping in application.yaml").isNotEmpty();
            return pairs.toArray(String[]::new);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void trackingOn_keepsRootHealthUpAnd200() {
        HEALTH.run(ctx -> {
            StatusAggregator aggregator = ctx.getBean(StatusAggregator.class);
            HttpCodeStatusMapper codes = ctx.getBean(HttpCodeStatusMapper.class);
            Status on = ResendTrackingHealthIndicator.TRACKING_ON;
            assertThat(aggregator.getAggregateStatus(Set.of(Status.UP, on))).isEqualTo(Status.UP);
            assertThat(codes.getStatusCode(Status.UP)).isEqualTo(200);
            assertThat(codes.getStatusCode(on)).isEqualTo(200);
        });
    }

    @Test
    void aRealDownBesideTrackingOn_stillTurnsRootHealth503() {
        HEALTH.run(ctx -> {
            StatusAggregator aggregator = ctx.getBean(StatusAggregator.class);
            HttpCodeStatusMapper codes = ctx.getBean(HttpCodeStatusMapper.class);
            assertThat(aggregator.getAggregateStatus(Set.of(Status.DOWN, ResendTrackingHealthIndicator.TRACKING_ON)))
                    .isEqualTo(Status.DOWN);
            assertThat(codes.getStatusCode(Status.DOWN)).isEqualTo(503);
        });
    }
}
