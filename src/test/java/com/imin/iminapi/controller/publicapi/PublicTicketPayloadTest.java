package com.imin.iminapi.controller.publicapi;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class PublicTicketPayloadTest {

    @Autowired MockMvc mvc;
    @Autowired TicketRepository tickets;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    @Test
    void getTicket_emitsSignedQrPayloadAndWalletFlag() throws Exception {
        Ticket t = persistIssuedTicket();

        mvc.perform(get("/api/v1/public/tickets/" + t.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrPayload").value(
                        org.hamcrest.Matchers.startsWith("imin1." + t.getToken() + ".")))
                .andExpect(jsonPath("$.walletAvailable").isBoolean())
                .andExpect(jsonPath("$.qrUrl").value(
                        org.hamcrest.Matchers.endsWith(
                                "/api/v1/public/tickets/" + t.getToken() + "/qr.png")))
                .andExpect(jsonPath("$.state").value("issued"));
    }

    private Ticket persistIssuedTicket() {
        Organization org = new Organization();
        org.setName("Payload Test Org");
        org.setSlug("payload-test-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("payload@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("payload-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event ev = new Event();
        ev.setOrgId(org.getId());
        ev.setName("Payload Test Event");
        ev.setSlug("payload-test-event-" + UUID.randomUUID().toString().substring(0, 8));
        ev.setVisibility(EventVisibility.PUBLIC);
        ev.setStatus(EventStatus.LIVE);
        ev.setCurrency("EUR");
        ev.setCreatedBy(owner.getId());
        ev = events.save(ev);

        Order order = new Order();
        order.setToken("ORD_" + UUID.randomUUID());
        order.setEventId(ev.getId());
        order.setOrgId(org.getId());
        order.setEmail("buyer@example.com");
        order.setTotalMinor(1500L);
        order.setCurrency("EUR");
        order.setPaymentMethod("stripe");
        order = orders.save(order);

        Ticket t = new Ticket();
        t.setToken("TKT_" + UUID.randomUUID());
        t.setOrderId(order.getId());
        t.setEventId(ev.getId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setState("issued");
        return tickets.save(t);
    }
}
