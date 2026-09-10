package com.imin.iminapi.marketing.unsubscribe;

import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class PublicUnsubscribeControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UnsubscribeTokenService tokenService;
    @MockitoBean ConsentService consentService;

    @Test
    void oneClickPost_validToken_unsubscribesOnChannel() throws Exception {
        UUID org = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        String token = tokenService.sign(org, member, campaign, "email");

        mvc.perform(post("/api/v1/public/unsubscribe/{token}", token))
                .andExpect(status().isOk());

        verify(consentService).unsubscribe(eq(org), eq(member), eq("one_click"), eq("email"),
                eq(ConsentOrigin.DATA_SUBJECT), any());
    }

    @Test
    void oneClickPost_badToken_returns404LeakSafe() throws Exception {
        mvc.perform(post("/api/v1/public/unsubscribe/{token}", "garbage.token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirmationPage_validToken_returnsHtml() throws Exception {
        String token = tokenService.sign(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "sms");
        mvc.perform(get("/api/v1/public/unsubscribe/{token}", token))
                .andExpect(status().isOk());
    }

    /**
     * The GET used to write the opt-out, so a link prefetcher, a mail-client
     * image proxy or a corporate URL scanner unsubscribed people who never
     * clicked. It now renders a confirm page and touches nothing; the POST that
     * page submits is what mutates.
     */
    @Test
    void confirmationPageGet_doesNotMutate_and_offers_a_post_form_to_the_same_url() throws Exception {
        UUID org = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String token = tokenService.sign(org, member, UUID.randomUUID(), "email");

        String html = mvc.perform(get("/api/v1/public/unsubscribe/{token}", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verifyNoInteractions(consentService);
        assertThat(html).contains("method=\"post\"");
        assertThat(html).contains("/api/v1/public/unsubscribe/" + token);
    }

    /** The confirm page's own submit still writes the sticky opt-out. */
    @Test
    void the_post_the_confirm_page_submits_unsubscribes_as_data_subject() throws Exception {
        UUID org = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String token = tokenService.sign(org, member, UUID.randomUUID(), "email");

        mvc.perform(post("/api/v1/public/unsubscribe/{token}", token))
                .andExpect(status().isOk());

        verify(consentService).unsubscribe(eq(org), eq(member), eq("one_click"), eq("email"),
                eq(ConsentOrigin.DATA_SUBJECT), any());
    }

    @Test
    void confirmationPage_badToken_returns404() throws Exception {
        mvc.perform(get("/api/v1/public/unsubscribe/{token}", "garbage.token"))
                .andExpect(status().isNotFound());
    }
}
