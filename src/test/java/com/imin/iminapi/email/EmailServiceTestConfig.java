package com.imin.iminapi.email;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class EmailServiceTestConfig {

    @Bean
    @Primary
    public EmailService recordingEmailService() {
        return new RecordingEmailService();
    }
}
