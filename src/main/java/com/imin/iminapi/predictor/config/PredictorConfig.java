package com.imin.iminapi.predictor.config;

import com.imin.iminapi.predictor.rules.QuestionBank;
import com.imin.iminapi.predictor.rules.QuestionBankLoader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/** Wires the predictor module's properties; the app has no {@code @ConfigurationPropertiesScan}. */
@Configuration
@EnableConfigurationProperties({PredictorProperties.class, DateCheckProperties.class})
public class PredictorConfig {

    /** Loaded whatever the date-check flag, so a bad bank file fails every boot. */
    @Bean
    QuestionBank predictorQuestionBank(ResourceLoader resources) {
        return QuestionBankLoader.load(resources);
    }
}
