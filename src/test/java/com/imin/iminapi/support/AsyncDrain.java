package com.imin.iminapi.support;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** Waits until every task already handed to a pool has finished, so a test never races its own async work. */
public final class AsyncDrain {

    private static final long TIMEOUT_MILLIS = 10_000;

    private AsyncDrain() {}

    public static void drain(Executor executor) {
        ThreadPoolExecutor tpe = ((ThreadPoolTaskExecutor) executor).getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        // completed < submitted covers the gap where a worker has dequeued a task but is not yet active.
        while (tpe.getCompletedTaskCount() < tpe.getTaskCount()
                || !tpe.getQueue().isEmpty() || tpe.getActiveCount() > 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("executor still busy after " + TIMEOUT_MILLIS + "ms");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while draining an executor", e);
            }
        }
    }
}
