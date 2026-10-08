package com.imin.iminapi.support;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncDrainTest {

    /** Holds the worker between dequeuing a task and running it, the window no executor counter sees. */
    private static final class HoldingQueue extends LinkedBlockingQueue<Runnable> {
        volatile boolean armed;
        final CountDownLatch dequeued = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Runnable take() throws InterruptedException {
            Runnable r = super.take();
            if (armed) {
                dequeued.countDown();
                release.await();
            }
            return r;
        }
    }

    @Test
    void drain_waitsForATaskADequeuedWorkerHasNotStarted() throws Exception {
        HoldingQueue queue = new HoldingQueue();
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor() {
            @Override
            protected BlockingQueue<Runnable> createQueue(int queueCapacity) {
                return queue;
            }
        };
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(10);
        pool.initialize();
        try {
            // First task creates the worker; the second goes through the queue and is held after dequeue.
            pool.execute(() -> {});
            AsyncDrain.drain(pool);
            queue.armed = true;
            AtomicBoolean ran = new AtomicBoolean();
            CountDownLatch ranLatch = new CountDownLatch(1);
            pool.execute(() -> {
                ran.set(true);
                ranLatch.countDown();
            });
            assertThat(queue.dequeued.await(5, TimeUnit.SECONDS)).isTrue();

            // The pause is when the stalled worker resumes; a drain that returned before it would see ran == false.
            AsyncDrain.drain(pool, () -> {
                queue.release.countDown();
                try {
                    ranLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(ran).isTrue();
        } finally {
            queue.release.countDown();
            pool.shutdown();
        }
    }
}
