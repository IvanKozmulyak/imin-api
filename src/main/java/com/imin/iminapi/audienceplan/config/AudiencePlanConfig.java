package com.imin.iminapi.audienceplan.config;

import com.imin.iminapi.audienceplan.engine.CalibrationSource;
import com.imin.iminapi.audienceplan.engine.ResponseModel;
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

    /** No stored outcomes yet, so every band is the YAML prior; replaced once outcomes are collected. */
    @Bean
    public CalibrationSource audiencePlanCalibrationSource() {
        return CalibrationSource.NONE;
    }

    @Bean
    public ResponseModel audiencePlanResponseModel(AudiencePlanLogic logic, CalibrationSource calibration) {
        return new ResponseModel(logic, calibration);
    }
}
