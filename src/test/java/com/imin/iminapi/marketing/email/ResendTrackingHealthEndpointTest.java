package com.imin.iminapi.marketing.email;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tracking left on must be loud but must not fail the deploy healthcheck; a real DOWN still must.
 * The status order and mapping are read from the production application.yaml, not restated here.
 */
@SpringBootTest(properties = {
        "management.health.resend-tracking.enabled=true",
        "management.endpoint.health.show-details=always",
        // No Redis in tests; its DOWN would mask what is under test.
        "management.health.redis.enabled=false",
        "imin.marketing.from-address=contact@imin.support"
})
@AutoConfigureMockMvc
@Import({TestRateLimitConfig.class, ResendTrackingHealthEndpointTest.DownSwitch.class})
class ResendTrackingHealthEndpointTest {

    static final AtomicBoolean FORCE_DOWN = new AtomicBoolean();

    @TestConfiguration
    static class DownSwitch {
        @Bean
        HealthIndicator testDownSwitchHealthIndicator() {
            return () -> FORCE_DOWN.get() ? Health.down().build() : Health.up().build();
        }
    }

    @DynamicPropertySource
    static void productionStatusMapping(DynamicPropertyRegistry registry) throws IOException {
        for (PropertySource<?> ps : new YamlPropertySourceLoader()
                .load("main", new FileSystemResource("src/main/resources/application.yaml"))) {
            for (String name : ((EnumerablePropertySource<?>) ps).getPropertyNames()) {
                if (name.startsWith("management.endpoint.health.status.")) {
                    String value = String.valueOf(ps.getProperty(name));
                    registry.add(name, () -> value);
                }
            }
        }
    }

    @Autowired MockMvc mvc;

    // NONE: a reset spy would fall back to the real Resend call.
    @MockitoSpyBean(reset = MockReset.NONE) ResendTrackingHealthIndicator indicator;

    @BeforeEach
    void trackingReportedOn() throws Exception {
        doReturn(new ResendTrackingHealthIndicator.TrackingState(true, true)).when(indicator).fetchTracking(anyString());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!indicator.health().getStatus().equals(ResendTrackingHealthIndicator.TRACKING_ON)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(indicator.health().getStatus()).isEqualTo(ResendTrackingHealthIndicator.TRACKING_ON);
    }

    @AfterEach
    void clearSwitch() {
        FORCE_DOWN.set(false);
    }

    @Test
    void trackingOn_keepsRootHealth200_andShowsTrackingOnOnTheComponent() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.resendTracking.status").value("TRACKING_ON"))
                .andExpect(jsonPath("$.components.resendTracking.details.openTracking").value(true));
    }

    @Test
    void aRealDownElsewhere_stillTurnsRootHealth503() throws Exception {
        FORCE_DOWN.set(true);
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.resendTracking.status").value("TRACKING_ON"));
    }
}
