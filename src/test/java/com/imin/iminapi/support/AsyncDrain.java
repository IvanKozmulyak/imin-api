package com.imin.iminapi.support;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** Waits until every task already handed to a pool has finished, so a test never races its own async work. */
public final class AsyncDrain {

    private static final long TIMEOUT_MILLIS = 10_000;
    private static final long SETTLE_MILLIS = 20;

    private AsyncDrain() {}

    public static void drain(Executor executor) {
        drain(executor, () -> sleep(SETTLE_MILLIS));
    }

    // A worker that has dequeued a task but not yet locked it is in no count, so an idle-looking pool is
    // re-checked after a pause. ponytail: a pause is a heuristic, not a proof; ceiling is a stall longer than it.
    static void drain(Executor executor, Runnable settlePause) {
        ThreadPoolExecutor tpe = ((ThreadPoolTaskExecutor) executor).getThreadPoolExecutor();
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (true) {
            if (idle(tpe)) {
                settlePause.run();
                if (idle(tpe)) return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("executor still busy after " + TIMEOUT_MILLIS + "ms");
            }
            sleep(5);
        }
    }

    private static boolean idle(ThreadPoolExecutor tpe) {
        return tpe.getCompletedTaskCount() >= tpe.getTaskCount()
                && tpe.getQueue().isEmpty() && tpe.getActiveCount() == 0;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while draining an executor", e);
        }
    }
}
