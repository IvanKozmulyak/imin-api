package com.imin.iminapi.config;

import com.imin.iminapi.storage.InMemoryMediaStorage;
import com.imin.iminapi.storage.MediaStorage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestMediaStorageConfig {

    @Bean @Primary
    public MediaStorage inMemoryMediaStorage() {
        return new InMemoryMediaStorage("https://test-media.invalid/");
    }
}
