package com.imin.iminapi.service.ticket;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TicketProperties.class, AppleWalletProperties.class})
public class TicketConfig {
}
