package com.imin.iminapi.support;

import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.storage.InMemoryMediaStorage;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.util.ArrayList;
import java.util.List;

/** Puts every flippable fake back to its default after each test, even when one reset fails. */
public class IminIntegrationResetListener extends AbstractTestExecutionListener {

    @Override
    public void afterTestMethod(TestContext testContext) {
        ApplicationContext ctx = testContext.getApplicationContext();
        List<Throwable> failures = new ArrayList<>();
        run(failures, () -> ctx.getBean(PropertyFlips.class).restoreAll());
        run(failures, () -> ctx.getBean(MutableClock.class).reset());
        run(failures, () -> ctx.getBean(RecordingEmailService.class).clear());
        run(failures, () -> ctx.getBean(RecordingRateLimiter.class).reset());
        run(failures, () -> ctx.getBean(InMemoryMediaStorage.class).blobs().clear());
        if (failures.isEmpty()) return;
        Throwable first = failures.get(0);
        failures.subList(1, failures.size()).forEach(first::addSuppressed);
        if (first instanceof RuntimeException re) throw re;
        if (first instanceof Error err) throw err;
        throw new IllegalStateException(first);
    }

    private static void run(List<Throwable> failures, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            failures.add(t);
        }
    }
}
