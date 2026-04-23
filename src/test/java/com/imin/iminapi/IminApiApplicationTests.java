package com.imin.iminapi;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.service.EventContentService;
import com.imin.iminapi.service.EventCreatorService;
import com.imin.iminapi.service.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class IminApiApplicationTests {

    @MockitoBean(name = "replicateRestClient")
    RestClient replicateRestClient;

    @MockitoBean EventCreatorService eventCreatorService;
    @MockitoBean EventContentService eventContentService;
    @MockitoBean AuthService authService;

    @Test
    void contextLoads() {
    }
}
