package com.imin.iminapi.buyer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BuyerProperties.class)
public class BuyerConfig {
}
