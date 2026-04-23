package com.imin.iminapi;

import com.imin.iminapi.service.EventContentService;
import com.imin.iminapi.service.EventCreatorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

@SpringBootTest
class IminApiApplicationTests {

    @MockitoBean(name = "replicateRestClient")
    RestClient replicateRestClient;

    @MockitoBean EventCreatorService eventCreatorService;
    @MockitoBean EventContentService eventContentService;

    @Test
    void contextLoads() {
    }
}
