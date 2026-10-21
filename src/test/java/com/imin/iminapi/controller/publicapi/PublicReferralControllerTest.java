package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.referral.EventReferralPerkRepository;
import com.imin.iminapi.referral.ReferralProperties;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v1/public/events/{eventId}/referrals/{code}: the friend's banner only for links that will count. */
@IminIntegrationTest
class PublicReferralControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired EventRepository events;
    @Autowired EventReferralPerkRepository perks;
    @Autowired ReferralProperties referralProps;
    @Autowired StripeProperties stripeProps;
    @Autowired PropertyFlips flips;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private User owner;
    private Event event;
    private Order referrer;

    @BeforeEach
    void setUp() {
        flips.set(referralProps, "perksEnabled", true);
        flips.set(stripeProps, "secretKey", "sk_live_dummy");
        org = fx.org();
        owner = fx.owner(org);
        event = publicEvent();
        referrer = fx.order(event, fx.email("referrer"));
        fx.ticket(referrer, Ticket.STATE_ISSUED);
    }

    enum WontCount {
        UNKNOWN_CODE, CODE_OF_ANOTHER_EVENT, PRIVATE_EVENT, DRAFT_EVENT,
        TEST_MODE_REFERRER_ON_A_LIVE_KEY, LIVE_REFERRER_ON_A_TEST_KEY
    }

    @ParameterizedTest
    @EnumSource(WontCount.class)
    void lookupAnswers404ForLinksThatWontCount(WontCount c) throws Exception {
        String code = referrer.getReferralCode();
        switch (c) {
            case UNKNOWN_CODE -> code = "Nope" + referrer.getReferralCode();
            case CODE_OF_ANOTHER_EVENT -> {
                Order elsewhere = fx.order(publicEvent(), fx.email("elsewhere"));
                code = elsewhere.getReferralCode();
            }
            case PRIVATE_EVENT -> {
                event.setVisibility(EventVisibility.PRIVATE);
                events.save(event);
            }
            case DRAFT_EVENT -> {
                event.setStatus(EventStatus.DRAFT);
                events.save(event);
            }
            case TEST_MODE_REFERRER_ON_A_LIVE_KEY ->
                    jdbc.update("UPDATE orders SET test_mode = true WHERE id = ?", referrer.getId());
            case LIVE_REFERRER_ON_A_TEST_KEY -> flips.set(stripeProps, "secretKey", "sk_test_dummy");
        }

        mvc.perform(get("/api/v1/public/events/" + event.getId() + "/referrals/" + code))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void returnsThePerkForAValidLink() throws Exception {
        perks.upsert(event.getId(), "Skip the line", (short) 3, Instant.now(), null);

        mvc.perform(get("/api/v1/public/events/" + event.getId() + "/referrals/" + referrer.getReferralCode()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=60"))
                .andExpect(jsonPath("$.perk.label").value("Skip the line"))
                .andExpect(jsonPath("$.perk.threshold").value(3));
    }

    private Event publicEvent() {
        Event e = fx.event(org, owner, EventStatus.LIVE, Instant.now().plus(10, ChronoUnit.DAYS));
        e.setPublishedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        return events.save(e);
    }
}
