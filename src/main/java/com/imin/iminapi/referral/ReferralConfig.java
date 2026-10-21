package com.imin.iminapi.referral;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the properties explicitly: the app has no {@code @ConfigurationPropertiesScan}. */
@Configuration
@EnableConfigurationProperties(ReferralProperties.class)
public class ReferralConfig {
}
