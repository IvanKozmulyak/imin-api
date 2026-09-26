package com.imin.iminapi.audienceplan.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/** Registers the properties explicitly: the app has no {@code @ConfigurationPropertiesScan}. */
@Configuration
@EnableConfigurationProperties(AudiencePlanProperties.class)
public class AudiencePlanConfig {

    /** Loaded at startup even while the feature is off, so a broken file fails the deploy, not a request. */
    @Bean
    public AudiencePlanLogic audiencePlanLogic(ResourceLoader resources, AudiencePlanProperties props) {
        return LogicLoader.load(resources, props);
    }
}
