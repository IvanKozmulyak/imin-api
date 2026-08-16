package com.imin.iminapi.service.ticket;

import com.imin.iminapi.service.ticket.google.GoogleWalletProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TicketProperties.class, AppleWalletProperties.class,
        GoogleWalletProperties.class})
public class TicketConfig {
}
