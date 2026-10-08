package com.imin.iminapi.audienceplan.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** What each audience-plan pool does when it is full: the callers rely on it to throw or to drop quietly. */
class ExecutorRejectionTest {

    static java.util.stream.Stream<Arguments> pools() {
        // pool, throws when full, the exception type its caller relies on
        return java.util.stream.Stream.of(
                arguments("fanFeatureExecutor",
                        (Supplier<Executor>) () -> new FanFeatureExecutors().fanFeatureExecutor(),
                        TaskRejectedException.class),
                arguments("fanFeatureRecomputeExecutor",
                        (Supplier<Executor>) () -> new FanFeatureExecutors().fanFeatureRecomputeExecutor(), null),
                arguments("audiencePlanRefreshExecutor",
                        (Supplier<Executor>) () -> new PlanRefreshExecutor().audiencePlanRefreshExecutor(), null),
                arguments("audiencePlanSummaryExecutor",
                        (Supplier<Executor>) () -> new SummaryExecutor().audiencePlanSummaryExecutor(),
                        RejectedExecutionException.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pools")
    void aFullPool_throwsOrDropsAsItsCallerExpects(String name, Supplier<Executor> pool,
                                                   Class<? extends Throwable> expected) {
        ThreadPoolTaskExecutor exec = (ThreadPoolTaskExecutor) pool.get();
        CountDownLatch release = new CountDownLatch(1);
        Runnable block = () -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            int capacity = exec.getMaxPoolSize() + exec.getQueueCapacity();
            for (int i = 0; i < capacity; i++) {
                exec.execute(block);
            }
            if (expected == null) {
                // A dropped task must not run on the caller (CallerRuns) and the queue stays full.
                AtomicReference<Thread> ranOn = new AtomicReference<>();
                assertThatCode(() -> exec.execute(() -> ranOn.set(Thread.currentThread())))
                        .doesNotThrowAnyException();
                assertThat(ranOn.get()).isNotSameAs(Thread.currentThread());
                assertThat(exec.getThreadPoolExecutor().getQueue()).hasSize(exec.getQueueCapacity());
            } else {
                assertThatThrownBy(() -> exec.execute(block)).isInstanceOf(expected);
            }
        } finally {
            release.countDown();
            exec.shutdown();
        }
    }
}
