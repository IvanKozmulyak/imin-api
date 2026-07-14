package com.imin.iminapi.marketing.webhook;

import com.imin.iminapi.marketing.service.MarketingGuardProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ResendWebhookProperties.class, MarketingGuardProperties.class})
public class ResendWebhookConfig {}
