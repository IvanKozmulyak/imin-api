package com.imin.iminapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated executor for post-issuance async work so a burst of Stripe
 * deliveries can't starve other {@code @Async} callers. Small pool because
 * the work is short and Resend tolerates parallelism.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "ticketEmailExecutor")
    public Executor ticketEmailExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(64);
        exec.setThreadNamePrefix("ticket-email-");
        exec.initialize();
        return exec;
    }
}
