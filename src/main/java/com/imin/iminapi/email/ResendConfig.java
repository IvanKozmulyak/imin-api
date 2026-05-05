package com.imin.iminapi.email;

import com.resend.Resend;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class ResendConfig {

    @Bean
    public Resend resendClient(EmailProperties props) {
        // SDK accepts an empty key without throwing at construction; calls fail at send time
        // if the key is not set. Test profile overrides EmailService with a recording bean,
        // so this client is never actually invoked in tests.
        return new Resend(props.getApiKey());
    }
}
