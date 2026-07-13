package com.imin.iminapi.marketing.email;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MarketingEmailProperties.class)
public class MarketingEmailConfig {
}
