package com.imin.iminapi.audienceplan.config;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * Publish-time plan refreshes, one at a time. Overflow is logged and dropped, never thrown: the submit runs inside
 * {@code afterCommit}, where a throw would fail a publish that already succeeded. The daily job catches up.
 */
@Configuration
public class PlanRefreshExecutor {

    public static final String NAME = "audiencePlanRefreshExecutor";

    static final RejectedExecutionHandler DROP_WITH_LOG = (task, executor) -> LoggerFactory
            .getLogger(PlanRefreshExecutor.class)
            .warn("PlanRefreshExecutor: queue full ({} deep), dropping a publish-time plan refresh",
                    executor.getQueue().size());

    @Bean(name = NAME)
    public Executor audiencePlanRefreshExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("plan-refresh-");
        exec.setRejectedExecutionHandler(DROP_WITH_LOG);
        exec.initialize();
        return exec;
    }
}
