package com.imin.iminapi.audienceplan.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FanFeatureExecutorsTest {

    private final FanFeatureExecutors config = new FanFeatureExecutors();

    @Test
    void live_isTwoThreadsWithABoundedQueue_andRejectsWhenFull() throws Exception {
        ThreadPoolTaskExecutor exec = (ThreadPoolTaskExecutor) config.fanFeatureExecutor();
        try {
            assertThat(exec.getCorePoolSize()).isEqualTo(2);
            assertThat(exec.getMaxPoolSize()).isEqualTo(2);
            assertThat(exec.getQueueCapacity()).isEqualTo(1000);
            assertThat(exec.getThreadNamePrefix()).isEqualTo("fan-feature-");
            CountDownLatch release = new CountDownLatch(1);
            Runnable block = () -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            for (int i = 0; i < 2 + 1000; i++) exec.execute(block);

            assertThatThrownBy(() -> exec.execute(block)).isInstanceOf(TaskRejectedException.class);
            release.countDown();
        } finally {
            exec.shutdown();
        }
    }

    @Test
    void recompute_isOneThread_andDropsASecondQueuedTriggerWithoutThrowing() {
        ThreadPoolTaskExecutor exec = (ThreadPoolTaskExecutor) config.fanFeatureRecomputeExecutor();
        try {
            assertThat(exec.getCorePoolSize()).isEqualTo(1);
            assertThat(exec.getMaxPoolSize()).isEqualTo(1);
            assertThat(exec.getQueueCapacity()).isEqualTo(1);
            CountDownLatch release = new CountDownLatch(1);
            Runnable block = () -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            exec.execute(block);
            exec.execute(block);

            assertThatCode(() -> exec.execute(block)).doesNotThrowAnyException();
            assertThat(exec.getThreadPoolExecutor().getQueue()).hasSize(1);
            release.countDown();
        } finally {
            exec.shutdown();
        }
    }
}
