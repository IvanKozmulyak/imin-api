package com.imin.iminapi.audienceplan.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryExecutorTest {

    @Test
    void oneThread_andAFullQueueThrowsToTheSubmitter() {
        ThreadPoolTaskExecutor exec = (ThreadPoolTaskExecutor) new SummaryExecutor().audiencePlanSummaryExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertThat(exec.getMaxPoolSize()).isEqualTo(1);
            assertThat(exec.getQueueCapacity()).isEqualTo(50);
            exec.execute(() -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            for (int i = 0; i < 50; i++) exec.execute(() -> { });
            assertThatThrownBy(() -> exec.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            exec.shutdown();
        }
    }
}
