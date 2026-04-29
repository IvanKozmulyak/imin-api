package com.imin.iminapi.config;

import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@ConditionalOnProperty(name = "sentry.smoke-test", havingValue = "true")
public class SentrySmokeTest {

    private static final Logger log = LoggerFactory.getLogger(SentrySmokeTest.class);

    @EventListener(ApplicationReadyEvent.class)
    public void fire() {
        log.info("Sentry smoke test starting. Sentry.isEnabled()={}", Sentry.isEnabled());

        Sentry.captureException(new RuntimeException("Sentry smoke test (direct SDK call)"));
        log.error("Sentry smoke test (logback path)", new RuntimeException("Sentry smoke test"));

        Sentry.flush(5000);
        log.info("Sentry smoke test fired both events and flushed.");
    }
}
