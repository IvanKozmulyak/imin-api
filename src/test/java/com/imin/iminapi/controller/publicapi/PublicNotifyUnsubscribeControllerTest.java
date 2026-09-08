package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

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
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class PublicNotifyUnsubscribeControllerTest {

    @Autowired MockMvc mvc;
    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired UnsubscribeTokenService tokens;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private NotifySubscription saved() {
        Organization org = new Organization();
        org.setName("Notify Org");
        org.setSlug("notify-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("notify-org@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setOrgId(org.getId());
        owner.setEmail("notify-owner-" + UUID.randomUUID() + "@example.com");
        owner.setFirstName("N");
        owner.setLastName("O");
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setCreatedBy(owner.getId());
        e.setName("Release Night");
        e.setSlug("release-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(java.time.Instant.now().plusSeconds(86400));
        e.setTimezone("Europe/Berlin");
        e.setCurrency("EUR");
        e = events.save(e);

        NotifySubscription s = new NotifySubscription();
        s.setEventId(e.getId());
        s.setEmail("guest-" + System.nanoTime() + "@example.com");
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
