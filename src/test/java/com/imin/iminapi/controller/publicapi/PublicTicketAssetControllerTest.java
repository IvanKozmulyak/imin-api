package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ticket assets over the real security chain. The configured-wallet paths, their 503 envelope and
 * unknown-token 404 are owned by {@code PublicTicketAssetControllerWalletTest} and {@code GoogleWalletEndpointTest}.
 */
@IminIntegrationTest
class PublicTicketAssetControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired AppleWalletPassService apple;

    @Test
    void qrPng_returnsDecodablePngForKnownTicket() throws Exception {
        Ticket t = persistTicket(Ticket.STATE_ISSUED);

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
     * A dead ticket is 409 even with the wallet switched off, as it is in the shared context.
     *
     * <p>It used to be 503: the controller consulted {@code isConfigured()} first
     * and {@code AppleWalletPassService} only checked the state afterwards. The
     * plan's rule is one shared rule for both wallets, and 409 is the true answer
     * whatever env vars a deployment happens to carry — "temporarily unavailable"
     * would be a lie that invites a retry which can never succeed.
     */
    @Test
    void applePass_returns409ForARefundedTicketEvenThoughTheWalletIsUnconfigured() throws Exception {
        Ticket t = persistTicket(Ticket.STATE_REFUNDED);
        assertThat(apple.isConfigured()).as("the test yaml carries no imin.apple-wallet block").isFalse();

        mvc.perform(get("/api/v1/public/tickets/" + t.getToken() + "/apple-wallet.pkpass"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_ALREADY_REFUNDED"));
    }

    private Ticket persistTicket(String state) {
        Organization org = fx.org();
        Event ev = fx.event(org, fx.owner(org), EventStatus.LIVE, null);
        return fx.ticket(fx.order(ev, fx.email("buyer")), state);
    }
}
