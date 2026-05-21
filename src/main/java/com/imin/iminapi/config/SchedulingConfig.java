package com.imin.iminapi.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 */
@Configuration
@EnableScheduling
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
}
