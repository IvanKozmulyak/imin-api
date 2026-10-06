package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/v1/public/orders/recover} is unauthenticated (SecurityConfig
 * permitAll) and has no rate-limit bucket — its only cap is an in-DB 5/hour
 * counter. So an attacker chooses how often the Resend round trip happens, and
 * every one of those sends used to run inside {@code requestRecovery}'s
 * transaction: a pooled JDBC connection (prod max 20) held open for the whole
 * outbound HTTP call. {@code BuyerOrderActionsController} already treats exactly
 * this pattern as a defect for exactly this emailer — "holding a pooled
 * connection open across an outbound HTTP request is how a slow third party
 * turns into an exhausted connection pool".
 */
@IminIntegrationTest
class OrderRecoveryTransactionBoundaryTest {

    @Autowired OrderRecoveryService service;
    @Autowired IminFixtures fx;
    @Autowired RecordingEmailService email;
    @Autowired Clock clock;

    @Test
    void the_resend_call_does_not_hold_a_pooled_connection() {
        Organization org = fx.org();
        Event event = fx.event(org, fx.owner(org), EventStatus.LIVE, clock.instant().plus(Duration.ofDays(7)));
        String buyer = fx.email("buyer");
        fx.order(event, buyer);
        // The 5/hour cap counts by email and by IP over a shared table, so both are unique here.
        String u = UUID.randomUUID().toString();
        String clientIp = "2001:db8::" + u.substring(0, 4) + ":" + u.substring(4, 8);

        service.requestRecovery(buyer, null, clientIp);

        List<RecordingEmailService.SentEmail> sent = email.sent();
        List<Integer> toBuyer = IntStream.range(0, sent.size())
                .filter(i -> buyer.equals(sent.get(i).to())).boxed().toList();
        assertThat(toBuyer).as("the recovery email is sent exactly once").hasSize(1);
        assertThat(email.sentInTransaction(toBuyer.get(0)))
                .as("the outbound Resend send must happen outside any transaction")
                .isFalse();
    }
}
