package com.imin.iminapi.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * Enables Spring's {@code @Scheduled} dispatcher and wires ShedLock so the
 * dispatcher coordinates with itself across multiple replicas.
 *
 * <p>{@code defaultLockAtMostFor = "PT5M"} is the safety release for crashed
 * holders — a replica that grabs the lock and dies will have it auto-released
 * after 5 minutes so the schedule isn't stuck forever. The
 * {@link com.imin.iminapi.service.event.ReservationSweeper} job runs every
 * minute and finishes in milliseconds, so 5 minutes is generous.
 *
 * <p>The backing table is provisioned in Flyway migration V27. We point
 * ShedLock at the {@code shedlock} table explicitly so a typo in a future
 * config can't silently fall back to the library default.
 *
 * <p>{@code imin.scheduling.enabled} (default on) switches dispatch only; turning it off is refused outside the {@code test} profile.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        .usingDbTime()
                        .build());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "imin.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    static class Dispatch {
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "imin.scheduling", name = "enabled", havingValue = "false")
    static class DispatchOff {

        DispatchOff(Environment env) {
            // A deploy with jobs off would silently stop the sweeper, payout tick and reconcilers.
            if (!env.matchesProfiles("test")) {
                throw new IllegalStateException(
                        "imin.scheduling.enabled=false is only allowed under the 'test' profile");
            }
        }
    }
}
