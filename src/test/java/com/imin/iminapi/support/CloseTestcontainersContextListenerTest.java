package com.imin.iminapi.support;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CloseTestcontainersContextListenerTest {

    private final CloseTestcontainersContextListener listener = new CloseTestcontainersContextListener();

    @Testcontainers
    static class ContainerBacked {
    }

    static class PlainSpringTest {
    }

    @Test
    void closesTheContextOfATestcontainersClass() {
        TestContext ctx = contextFor(ContainerBacked.class);

        listener.afterTestClass(ctx);

        verify(ctx, times(1)).markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
    }

    @Test
    void leavesOtherContextsCached() {
        TestContext ctx = contextFor(PlainSpringTest.class);

        listener.afterTestClass(ctx);

        verify(ctx, never()).markApplicationContextDirty(any());
    }

    @Test
    void runsAfterEveryDefaultListener() {
        assertThat(listener.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void isRegisteredThroughSpringFactories() {
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(TestExecutionListener.class))
                .hasAtLeastOneElementOfType(CloseTestcontainersContextListener.class);
    }

    private static TestContext contextFor(Class<?> testClass) {
        TestContext ctx = mock(TestContext.class);
        doReturn(testClass).when(ctx).getTestClass();
        return ctx;
    }
}
