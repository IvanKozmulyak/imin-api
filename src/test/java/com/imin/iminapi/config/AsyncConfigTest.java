package com.imin.iminapi.config;

import com.imin.iminapi.service.EventContentService;
import com.imin.iminapi.service.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class AsyncConfigTest {

    @MockitoBean(name = "replicateRestClient")
    RestClient replicateRestClient;

    @MockitoBean EventContentService eventContentService;
    @MockitoBean AuthService authService;

    @Autowired @Qualifier("campaignSendExecutor") Executor campaignSendExecutor;
    @Autowired @Qualifier("ticketEmailExecutor") Executor ticketEmailExecutor;

    @Test
    void campaignSendExecutor_isSeparatePoolFromTicketExecutor() {
        assertThat(campaignSendExecutor).isNotSameAs(ticketEmailExecutor);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) campaignSendExecutor;
        assertThat(pool.getThreadNamePrefix()).isEqualTo("campaign-send-");
    }
}
