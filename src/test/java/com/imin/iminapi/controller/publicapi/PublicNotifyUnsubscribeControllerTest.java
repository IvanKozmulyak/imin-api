package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A guest can subscribe to a drop alert without an account, and until now the
 * notify-me email carried no opt-out — removing the subscription required
 * signing in, which a guest cannot do. A standing request for mail the recipient
 * cannot withdraw is not a lawful one (CPCE L34-5).
 */
@IminIntegrationTest
class PublicNotifyUnsubscribeControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;
    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired UnsubscribeTokenService tokens;

    private NotifySubscription saved() {
        Organization org = fx.org();
        Event e = fx.event(org, fx.owner(org), EventStatus.LIVE, clock.instant().plusSeconds(86400));

        NotifySubscription s = new NotifySubscription();
        s.setEventId(e.getId());
        s.setEmail(fx.email("guest"));
        return subscriptions.save(s);
    }

    @Test
    void a_signed_token_removes_the_subscription_and_answers_204() throws Exception {
        NotifySubscription sub = saved();

        mvc.perform(post("/api/v1/public/notify/unsubscribe/{token}", tokens.signNotify(sub.getId())))
                .andExpect(status().isNoContent());

        assertThat(subscriptions.findById(sub.getId())).isEmpty();
    }

    /** A second click, or an email client retrying, must look like success. */
    @Test
    void it_is_idempotent() throws Exception {
        NotifySubscription sub = saved();
        String token = tokens.signNotify(sub.getId());

        mvc.perform(post("/api/v1/public/notify/unsubscribe/{token}", token))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/public/notify/unsubscribe/{token}", token))
                .andExpect(status().isNoContent());
    }

    @Test
    void an_unsigned_or_forged_token_is_404_and_removes_nothing() throws Exception {
        NotifySubscription sub = saved();

        mvc.perform(post("/api/v1/public/notify/unsubscribe/{token}", "not.a.token"))
                .andExpect(status().isNotFound());
        // A raw subscription id is not a token — the HMAC is the whole control.
        mvc.perform(post("/api/v1/public/notify/unsubscribe/{token}", sub.getId().toString()))
                .andExpect(status().isNotFound());

        assertThat(subscriptions.findById(sub.getId())).isPresent();
    }

    /** A marketing opt-out token must not resolve here — different subject entirely. */
    @Test
    void a_marketing_optout_token_is_not_accepted() throws Exception {
        String marketing = tokens.sign(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "email");

        mvc.perform(post("/api/v1/public/notify/unsubscribe/{token}", marketing))
                .andExpect(status().isNotFound());
    }
}
