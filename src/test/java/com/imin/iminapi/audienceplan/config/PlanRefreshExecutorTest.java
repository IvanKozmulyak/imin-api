package com.imin.iminapi.audienceplan.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PlanRefreshExecutorTest {

    @Test
    void oneThread_andAFullQueueDropsInsteadOfThrowing() throws Exception {
        ThreadPoolTaskExecutor exec = (ThreadPoolTaskExecutor) new PlanRefreshExecutor().audiencePlanRefreshExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertThat(exec.getMaxPoolSize()).isEqualTo(1);
            assertThat(exec.getQueueCapacity()).isEqualTo(200);
            exec.execute(() -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            for (int i = 0; i < 200; i++) exec.execute(() -> { });
            assertThatCode(() -> exec.execute(() -> { })).doesNotThrowAnyException();
            assertThat(exec.getThreadPoolExecutor().getQueue()).hasSize(200);
        } finally {
            release.countDown();
            exec.shutdown();
        }
    }
}
