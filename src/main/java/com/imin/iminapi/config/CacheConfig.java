package com.imin.iminapi.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager("dashboard");
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(10_000));
        return mgr;
    }
}
