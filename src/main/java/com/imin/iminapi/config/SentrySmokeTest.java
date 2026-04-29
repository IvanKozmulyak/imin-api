package com.imin.iminapi.config;

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
        log.error("Sentry smoke test", new RuntimeException("Sentry smoke test"));
    }
}
