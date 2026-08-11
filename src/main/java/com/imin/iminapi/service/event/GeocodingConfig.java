package com.imin.iminapi.service.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the single {@link Geocoder} bean.
 *
 * <p>One {@code @Bean} method with an explicit branch rather than two conditional
 * beans: the choice is a plain boolean, and this way there is exactly one place that
 * decides, with no {@code @ConditionalOnMissingBean} ordering to reason about. Tests
 * get {@link NoOpGeocoder} for free — the property defaults to false, so no test ever
 * makes an outbound geocoding call.
 */
@Configuration
@EnableConfigurationProperties(GeocodingProperties.class)
public class GeocodingConfig {

    private static final Logger log = LoggerFactory.getLogger(GeocodingConfig.class);

    @Bean
    public Geocoder geocoder(GeocodingProperties props) {
        if (!props.isEnabled()) {
            log.info("[geocode] disabled (imin.geocoding.enabled=false) — venue coordinates stay null");
            return new NoOpGeocoder();
        }
        log.info("[geocode] enabled via {} (min interval {}ms)", props.getBaseUrl(), props.getMinIntervalMillis());
        return new NominatimGeocoder(props);
    }
}
