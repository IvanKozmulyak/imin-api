package com.imin.iminapi.predictor.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wires the predictor module's {@link PredictorProperties}. */
@Configuration
@EnableConfigurationProperties(PredictorProperties.class)
public class PredictorConfig {
}
