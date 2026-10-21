package com.imin.iminapi.checkoutreminder.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the properties explicitly: the app has no {@code @ConfigurationPropertiesScan}. */
@Configuration
@EnableConfigurationProperties(CheckoutReminderProperties.class)
public class CheckoutReminderConfig {
}
