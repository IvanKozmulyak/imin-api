package com.imin.iminapi.audienceplan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Portrait research, off the request thread, one pair at a time. A full queue throws to the submitter, which forgets
 * the request so the next GET can ask again.
 */
@Configuration
public class PortraitExecutor {

    public static final String NAME = "audiencePortraitExecutor";

    @Bean(name = NAME)
    public Executor audiencePortraitExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(20);
        exec.setThreadNamePrefix("portrait-research-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.initialize();
        return exec;
    }
}
