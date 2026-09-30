package com.imin.iminapi.predictor.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wires the predictor module's properties; the app has no {@code @ConfigurationPropertiesScan}. */
@Configuration
@EnableConfigurationProperties({PredictorProperties.class, DateCheckProperties.class})
public class PredictorConfig {
}
