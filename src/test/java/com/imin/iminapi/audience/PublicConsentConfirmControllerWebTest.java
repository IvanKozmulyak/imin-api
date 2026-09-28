package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.ConsentConfirmationResponse;
import com.imin.iminapi.audience.service.ConsentConfirmationService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Routing, security and rate limiting of the public confirmation link; the service behind it is mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class PublicConsentConfirmControllerWebTest {

    private static final String URL = "/api/v1/public/consent/confirm";

    @Autowired MockMvc mvc;
    @MockitoBean ConsentConfirmationService service;
    @MockitoBean RateLimiter rateLimiter;

    @Test
    void get_isOpenWithoutAuth_isNotRateLimited_previews_andIsNotCached() throws Exception {
        when(service.preview("tok")).thenReturn(new ConsentConfirmationResponse("pending", "Vechirka"));

        mvc.perform(get(URL).param("t", "tok"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("pending"))
                .andExpect(jsonPath("$.organizerName").value("Vechirka"));

        // The buyer site previews from one server IP; a shared bucket would turn a busy hour into dead links.
        verify(rateLimiter, never()).consume(any(), any());
        verify(service, never()).confirm(any());
    }

    @Test
    void post_isOpenWithoutAuth_chargesTheBucketPerIp_confirms_andIsNotCached() throws Exception {
        when(service.confirm("tok")).thenReturn(new ConsentConfirmationResponse("confirmed", "Vechirka"));

        mvc.perform(post(URL).param("t", "tok"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("confirmed"))
                .andExpect(jsonPath("$.organizerName").value("Vechirka"));

        verify(rateLimiter).consume("consent-confirm", "ip:127.0.0.1");
    }

    @Test
    void post_withoutToken_passesNull_andAnswersTheNeutralInvalid() throws Exception {
        when(service.confirm(null)).thenReturn(ConsentConfirmationResponse.invalid());

        mvc.perform(post(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("invalid"))
                .andExpect(jsonPath("$.organizerName").doesNotExist());
    }

    @Test
    void post_rateLimited_is429_andNeverReachesTheService() throws Exception {
        doThrow(ApiException.rateLimited()).when(rateLimiter).consume("consent-confirm", "ip:127.0.0.1");

        mvc.perform(post(URL).param("t", "tok"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        verify(service, never()).confirm(any());
    }
}
