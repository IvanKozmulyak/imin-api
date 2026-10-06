package com.imin.iminapi.support;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Closes a {@code @Testcontainers} class's context once the class is done: its container-specific
 * properties make the cache key unique, so the cached context would only hold a pool to a stopped container.
 */
public class CloseTestcontainersContextListener extends AbstractTestExecutionListener {

    /** Highest precedence runs {@code afterTestClass} last, after every default listener. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        if (AnnotatedElementUtils.hasAnnotation(testContext.getTestClass(), Testcontainers.class)) {
            testContext.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        }
    }
}
