package com.imin.iminapi.service.event;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the properties explicitly: the app has no {@code @ConfigurationPropertiesScan}. */
@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyConfig {
}
