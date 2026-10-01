package com.imin.iminapi.predictor.sources.wikimedia;

import com.imin.iminapi.predictor.rules.QuestionBank;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Wikimedia Pageviews source. The HTTP client is private to it (not a bean), with explicit
 * timeouts: the static {@code RestClient.builder()} ignores Boot's client settings.
 */
@Configuration
@EnableConfigurationProperties(WikimediaProperties.class)
public class WikimediaConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    public WikimediaPageviewsClient wikimediaPageviewsClient(WikimediaProperties props) {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT)));
        return new WikimediaPageviewsClient(builder, props);
    }

    /** Loaded whatever the flag, so a bad article map fails every boot. */
    @Bean
    public WikimediaArticles wikimediaArticles(ResourceLoader resources, QuestionBank bank) {
        return WikimediaArticles.load(resources, bank);
    }

    /** One thread of its own, so the boot seed never competes with the calendar seed. */
    @Bean(name = "wikimediaSyncExecutor")
    public Executor wikimediaSyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(1);
        exec.setThreadNamePrefix("wikimedia-sync-");
        exec.initialize();
        return exec;
    }
}
