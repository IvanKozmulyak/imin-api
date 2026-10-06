package com.imin.iminapi.config;

import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.service.event.ReservationSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** What the test yaml switches on the shared context: no dispatch, ShedLock proxies kept, cheap BCrypt. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class TestProfileInfraTest {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    PasswordHasher passwordHasher;

    @Test
    void scheduledDispatchIsOff() {
        assertThat(ctx.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse();
        assertThat(ctx.getBeanNamesForType(TaskScheduler.class)).isEmpty();
    }

    @Test
    void shedLockStillProxiesScheduledJobs() {
        assertThat(AopUtils.isAopProxy(ctx.getBean(ReservationSweeper.class))).isTrue();
    }

    @Test
    void passwordsHashAtTestCost() {
        assertThat(passwordHasher.hash("x")).startsWith("$2a$04$");
    }
}
