package com.imin.iminapi.audienceplan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Plan summaries, off the request thread, one LLM call at a time. A full queue throws to the submitter, which
 * forgets the request so the next GET can ask again.
 */
@Configuration
public class SummaryExecutor {

    public static final String NAME = "audiencePlanSummaryExecutor";

    @Bean(name = NAME)
    public Executor audiencePlanSummaryExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("plan-summary-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.initialize();
        return exec;
    }
}
