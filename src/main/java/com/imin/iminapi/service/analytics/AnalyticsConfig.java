package com.imin.iminapi.service.analytics;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * This application does not use {@code @ConfigurationPropertiesScan}, so a
 * {@code @ConfigurationProperties} class without an explicit registration is
 * silently never bound.
 */
@Configuration
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsConfig {
}
