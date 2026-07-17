package com.imin.iminapi.marketing.sms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link MarketingSmsProperties} (intended SMS channel policy). */
@Configuration
@EnableConfigurationProperties(MarketingSmsProperties.class)
public class MarketingSmsConfig {
}
