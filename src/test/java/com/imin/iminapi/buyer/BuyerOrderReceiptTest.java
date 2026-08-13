package com.imin.iminapi.buyer;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.OrderFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.imin.iminapi.config.TestRateLimitConfig;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The receipt breakdown on {@code GET /public/orders/{token}} (spec §4.6).
 *
 * <p>The point of this file is that the receipt <b>adds up</b>. A receipt whose
 * lines do not reconcile to the amount charged is worse than no receipt: it
 * makes the buyer doubt the charge rather than understand it.
 *
 * <p>There is no refund-protection row. imin does not sell that product, and
 * the design's mock-up showing one is not a reason to invent a charge.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerOrderReceiptTest {

    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private Event event;

    @BeforeEach
    void anEvent() {
        event = OrderFixtures.event(orgs, users, events, "Receipt", Instant.parse("2026-12-01T20:00:00Z"));
    }

    @Test
    void theReceiptAddsUpToWhatWasCharged() throws Exception {
        // 2 × €18.00 = €36.00 subtotal, €3.78 fee, €39.78 charged.
        Order order = order(3978L, 378L, null);
        priced(order, "Standard", 1800, 2);

        mvc.perform(get("/api/v1/public/orders/" + order.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].tierName").value("Standard"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitPriceMinor").value(1800))
                .andExpect(jsonPath("$.lines[0].amountMinor").value(3600))
                .andExpect(jsonPath("$.subtotalMinor").value(3600))
                .andExpect(jsonPath("$.feeMinor").value(378))
                .andExpect(jsonPath("$.discountMinor").value(0))
                .andExpect(jsonPath("$.totalMinor").value(3978));
    }

    @Test
    void twoTiersAreTwoLines() throws Exception {
        Order order = order(5400L, 0L, null);
        priced(order, "Standard", 1800, 2);
        priced(order, "VIP", 1800, 1);

        mvc.perform(get("/api/v1/public/orders/" + order.getToken()))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.subtotalMinor").value(5400));
    }

    @Test
    void oneTierAtTwoPricesIsTwoLinesNotAnAveragedOne() throws Exception {
        Order order = order(3000L, 0L, null);
        UUID tier = UUID.randomUUID();
        priced(order, tier, "Standard", 2000, 1);
        priced(order, tier, "Standard", 1000, 1);

        mvc.perform(get("/api/v1/public/orders/" + order.getToken()))
                .andExpect(jsonPath("$.lines.length()").value(2));
    }

    /**
     * The fabrication guard. {@code PaidCheckoutService} snapshots each ticket's
     * price from the tier at webhook time, so an organizer who moved a price
     * between checkout and fulfilment leaves a residue in
     * {@code subtotal + fee - total}. Reporting that as a discount would put a
     * discount on a receipt for an order that never had one.
     */
    @Test
    void aPriceDriftResidueIsNotReportedAsADiscountWhenThereWasNoPromo() throws Exception {
        Order order = order(3000L, 0L, null);       // total says 3000
        priced(order, "Standard", 1800, 2);         // tickets say 3600

        mvc.perform(get("/api/v1/public/orders/" + order.getToken()))
                .andExpect(jsonPath("$.discountMinor").value(0));
    }

    @Test
    void aRealPromoDoesShowAsADiscount() throws Exception {
        Order order = order(3000L, 0L, UUID.randomUUID());   // promo present
        priced(order, "Standard", 1800, 2);

        mvc.perform(get("/api/v1/public/orders/" + order.getToken()))
                .andExpect(jsonPath("$.discountMinor").value(600));
    }

    @Test
    void theExistingFieldsKeepTheirNamesAndMeaning() throws Exception {
        Order order = order(3978L, 378L, null);
        priced(order, "Standard", 1800, 2);

        mvc.perform(get("/api/v1/public/orders/" + order.getToken()))
                .andExpect(jsonPath("$.token").value(order.getToken()))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.event.name").exists())
                .andExpect(jsonPath("$.tickets.length()").value(2));
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private Order order(long totalMinor, long feeMinor, UUID promoId) {
        Order order = OrderFixtures.order(orders, event, "ada@example.com", Instant.parse("2026-06-01T10:00:00Z"));
        order.setTotalMinor(totalMinor);
        order.setApplicationFeeMinor(feeMinor);
        order.setPromoCodeId(promoId);
        return orders.save(order);
    }

    private void priced(Order order, String tierName, long priceMinor, int quantity) {
        priced(order, UUID.randomUUID(), tierName, priceMinor, quantity);
    }

    private void priced(Order order, UUID tierId, String tierName, long priceMinor, int quantity) {
        for (int i = 0; i < quantity; i++) {
            Ticket ticket = new Ticket();
            ticket.setToken("TKT_" + UUID.randomUUID());
            ticket.setOrderId(order.getId());
            ticket.setEventId(order.getEventId());
            ticket.setTierId(tierId);
            ticket.setTierName(tierName);
            ticket.setPriceMinor((int) priceMinor);
            ticket.setState("issued");
            tickets.save(ticket);
        }
    }
}
