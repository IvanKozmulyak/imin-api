package com.imin.iminapi.service.ticket;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class TicketRedeemServiceTest {

    @Autowired TicketRedeemService service;
    @Autowired TicketRepository tickets;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired QrPayloadSigner signer;

    private Event event;
    private Order order;

    @BeforeEach
    void setUp() {
        tickets.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();

        Organization org = new Organization();
        org.setName("Redeem Org");
        org.setSlug("redeem-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("redeem@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("redeem-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Redeem Event");
        event.setSlug("redeem-event-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setCurrency("EUR");
        event.setCreatedBy(owner.getId());
        event = events.save(event);

        order = new Order();
        order.setToken("ORD_" + UUID.randomUUID());
        order.setEventId(event.getId());
        order.setOrgId(org.getId());
        order.setEmail("buyer@example.com");
        order.setTotalMinor(1500L);
        order.setCurrency("EUR");
        order.setPaymentMethod("stripe");
        order = orders.save(order);
    }

    @AfterEach
    void tearDown() {
        tickets.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    @Test
    void redeem_atomic_transitions_issued_to_redeemed_then_already_redeemed() {
        Ticket t = persistTicket("issued");
        UUID userId = UUID.randomUUID();
        String payload = signer.sign(t.getToken());

        TicketRedeemService.Result first = service.redeem(event.getId(), payload, userId);
        assertThat(first.outcome()).isEqualTo(TicketRedeemService.Outcome.REDEEMED);
        assertThat(first.ticket().getRedeemedAt()).isNotNull();

        TicketRedeemService.Result second = service.redeem(event.getId(), payload, userId);
        assertThat(second.outcome()).isEqualTo(TicketRedeemService.Outcome.ALREADY_REDEEMED);
    }

    @Test
    void legacy_pre_state_redeems_as_issued() {
        Ticket t = persistTicket("pre");
        UUID userId = UUID.randomUUID();
        String payload = signer.sign(t.getToken());

        TicketRedeemService.Result r = service.redeem(event.getId(), payload, userId);
        assertThat(r.outcome()).isEqualTo(TicketRedeemService.Outcome.REDEEMED);
    }

    @Test
    void invalid_signature_returns_invalid_without_state_change() {
        Ticket t = persistTicket("issued");

        TicketRedeemService.Result r = service.redeem(event.getId(),
                "imin1." + t.getToken() + ".BADSIGAAAAAAAAAAAAAAAA", UUID.randomUUID());

        assertThat(r.outcome()).isEqualTo(TicketRedeemService.Outcome.INVALID);
        Ticket fresh = tickets.findByToken(t.getToken()).orElseThrow();
        assertThat(fresh.getState()).isEqualTo("issued");
    }

    @Test
    void unknown_token_is_invalid_not_404() {
        TicketRedeemService.Result r = service.redeem(event.getId(),
                signer.sign("NEVER_ISSUED_TOKEN"), UUID.randomUUID());
        assertThat(r.outcome()).isEqualTo(TicketRedeemService.Outcome.INVALID);
    }

    @Test
    void wrong_event_returns_wrong_event() {
        Ticket t = persistTicket("issued");

        TicketRedeemService.Result r = service.redeem(UUID.randomUUID(),
                signer.sign(t.getToken()), UUID.randomUUID());

        assertThat(r.outcome()).isEqualTo(TicketRedeemService.Outcome.WRONG_EVENT);
        assertThat(r.ticket()).isNull(); // no leak of event id
    }

    @Test
    void revoked_returns_revoked() {
        Ticket t = persistTicket("revoked");

        TicketRedeemService.Result r = service.redeem(event.getId(),
                signer.sign(t.getToken()), UUID.randomUUID());

        assertThat(r.outcome()).isEqualTo(TicketRedeemService.Outcome.REVOKED);
    }

    private Ticket persistTicket(String state) {
        Ticket t = new Ticket();
        t.setToken("TKT_" + UUID.randomUUID());
        t.setOrderId(order.getId());
        t.setEventId(event.getId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setState(state);
        return tickets.save(t);
    }
}
