package com.imin.iminapi.app;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link AppReleaseProperties}. Nothing else lives here — the version
 * gate has no clients, no credentials and no state.
 */
@Configuration
@EnableConfigurationProperties(AppReleaseProperties.class)
public class AppConfig {
}
