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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class PublicTicketAssetControllerTest {

    @Autowired MockMvc mvc;
    @Autowired TicketRepository tickets;
    @Autowired OrderRepository orders;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    @Test
    void qrPng_returnsDecodablePngForKnownTicket() throws Exception {
        Ticket t = persistTicket();

        byte[] bytes = mvc.perform(get("/api/v1/public/tickets/" + t.getToken() + "/qr.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse().getContentAsByteArray();

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(320);
        assertThat(img.getHeight()).isEqualTo(320);
    }

    @Test
    void qrPng_returns404ForUnknownToken() throws Exception {
        mvc.perform(get("/api/v1/public/tickets/no-such-token/qr.png"))
                .andExpect(status().isNotFound());
    }

    /**
     * The 503 used to be {@code ResponseEntity.status(503).build()} — an empty
     * body, where every other error in this API is an {@code ApiError} envelope
     * and {@code imin-public} reads {@code $.error.code}.
     *
     * <p>Green here for the right reason: {@code src/test/resources/application.yaml}
     * REPLACES the main file and carries no {@code imin.apple-wallet} block, so
     * every field binds from its Java default and {@code fullyConfigured()} is
     * false.
     */
    @Test
    void applePass_returns503WithAnErrorEnvelopeWhenWalletNotConfigured() throws Exception {
        Ticket t = persistTicket();
        mvc.perform(get("/api/v1/public/tickets/" + t.getToken() + "/apple-wallet.pkpass"))
                .andExpect(status().isServiceUnavailable())
                // $.error.code, never $.code — ApiError wraps the body.
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void applePass_returns404ForUnknownToken() throws Exception {
        mvc.perform(get("/api/v1/public/tickets/no-such-token/apple-wallet.pkpass"))
                .andExpect(status().isNotFound());
    }

    /**
     * A dead ticket is 409 even with the wallet switched off.
     *
     * <p>It used to be 503: the controller consulted {@code isConfigured()} first
     * and {@code AppleWalletPassService} only checked the state afterwards. The
     * plan's rule is one shared rule for both wallets, and 409 is the true answer
     * whatever env vars a deployment happens to carry — "temporarily unavailable"
     * would be a lie that invites a retry which can never succeed.
     */
    @Test
    void applePass_returns409ForARefundedTicketEvenThoughTheWalletIsUnconfigured() throws Exception {
        Ticket t = persistTicket();
        t.setState(Ticket.STATE_REFUNDED);
        tickets.save(t);

        mvc.perform(get("/api/v1/public/tickets/" + t.getToken() + "/apple-wallet.pkpass"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_ALREADY_REFUNDED"));
    }

    /**
     * The Google save link over the <b>real security chain</b>, which is the only
     * thing this file can prove that {@code GoogleWalletEndpointTest}'s standalone
     * MockMvc cannot: {@code SecurityConfig} blanket-permits
     * {@code GET /api/v1/public/**} and this route has no matcher of its own, so a
     * change to that rule shows up here as a 401 or 403 instead of a 503.
     *
     * <p>503 and not 200 because {@code src/test/resources/application.yaml}
     * replaces the main YAML and carries no {@code imin.google-wallet} block — the
     * gate is closed on every developer machine and every CI run, which is the
     * state to assert.
     */
    @Test
    void googleWallet_returns503WithAnErrorEnvelopeWhenWalletNotConfigured() throws Exception {
        Ticket t = persistTicket();
        mvc.perform(get("/api/v1/public/tickets/" + t.getToken() + "/google-wallet"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));
    }

    /** The token is the credential, and an unknown one is 404 before the gate is consulted. */
    @Test
    void googleWallet_returns404ForUnknownToken() throws Exception {
        mvc.perform(get("/api/v1/public/tickets/no-such-token/google-wallet"))
                .andExpect(status().isNotFound());
    }

    private Ticket persistTicket() {
        Organization org = new Organization();
        org.setName("Asset Test Org");
        org.setSlug("asset-test-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("asset@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("asset-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event ev = new Event();
        ev.setOrgId(org.getId());
        ev.setName("Asset Test Event");
        ev.setSlug("asset-test-event-" + UUID.randomUUID().toString().substring(0, 8));
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
