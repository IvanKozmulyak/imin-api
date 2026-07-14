package com.imin.iminapi.marketing.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ResendWebhookProperties.class)
public class ResendWebhookConfig {}
