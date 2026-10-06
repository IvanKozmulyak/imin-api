package com.imin.iminapi.config;

import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    // OS env and JVM properties are dropped so a developer's IMIN_SCHEDULING_ENABLED cannot decide a branch.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ctx -> {
                ctx.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                ctx.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:h2:mem:sched-cfg;MODE=PostgreSQL"))
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void dispatchIsOnWhenThePropertyIsAbsent() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue();
        });
    }

    @Test
    void dispatchIsOnWhenEnabledIsTrue() {
        runner.withPropertyValues("imin.scheduling.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue();
        });
    }

    @Test
    void dispatchIsOffUnderTheTestProfileAndShedLockStays() {
        runner.withPropertyValues("imin.scheduling.enabled=false", "spring.profiles.active=test").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse();
            assertThat(ctx).hasSingleBean(LockProvider.class);
        });
    }

    @Test
    void turningDispatchOffOutsideTheTestProfileFailsStartup() {
        runner.withPropertyValues("imin.scheduling.enabled=false").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("imin.scheduling.enabled=false");
        });
    }
}
