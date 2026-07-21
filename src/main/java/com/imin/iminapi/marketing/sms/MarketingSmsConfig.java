package com.imin.iminapi.marketing.sms;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the two SMS config sources: {@link MarketingSmsProperties} (the
 * DTO-surfaced display policy) and {@link SmsProperties} (provider connection
 * creds; drives dry-run vs live).
 */
@Configuration
@EnableConfigurationProperties({MarketingSmsProperties.class, SmsProperties.class})
public class MarketingSmsConfig {
}
