package com.imin.iminapi.predictor.sources.prim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.Executor;

/** IDFM PRIM source with its own timed HTTP client; a non-https base fails startup, naming variables, never values. */
@Configuration
@EnableConfigurationProperties(PrimProperties.class)
public class PrimConfig {

    private static final Logger log = LoggerFactory.getLogger(PrimConfig.class);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    public PrimDisruptionsClient primDisruptionsClient(PrimProperties props) {
        if (!props.getBaseUrl().startsWith("https://")) {
            throw new IllegalStateException("PREDICTOR_PRIM_BASE_URL must be an https URL");
        }
        if (props.isEnabled() && props.getApiKey().isBlank()) {
            log.warn("PREDICTOR_PRIM_ENABLED is true but IDFM_PRIM_API_KEY is blank: the prim source stays off");
        }
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)));
        return new PrimDisruptionsClient(builder, props);
    }

    /** One thread and a queue of one: the boot seed never competes with the other seeds or runs twice. */
    @Bean(name = "primSyncExecutor")
    public Executor primSyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(1);
        exec.setThreadNamePrefix("prim-sync-");
        exec.initialize();
        return exec;
    }
}
