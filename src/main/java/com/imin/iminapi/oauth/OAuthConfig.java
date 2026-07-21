package com.imin.iminapi.oauth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires up the OAuth social-sign-in properties and the {@link RestClient} used
 * for provider token exchange (Google + Apple {@code /token} endpoints). The
 * client carries no base URL — the services pass absolute provider URLs.
 */
@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class OAuthConfig {

    @Bean
    public RestClient oauthRestClient() {
        return RestClient.builder().build();
    }
}
