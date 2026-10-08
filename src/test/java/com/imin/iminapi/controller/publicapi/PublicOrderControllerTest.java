package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/public/orders/{token}} — the buyer's confirmation page feed.
 *
 * <p>Covers the W0.1 regression (a refunded ticket used to 500 the whole order,
 * because {@code TicketState.fromWire} threw on the {@code 'refunded'} value that
 * {@code RefundService} writes) and the W0.6 payload widening.
 */
@IminIntegrationTest
class PublicOrderControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;
    @Autowired EventRepository events;
    @Autowired MetaPixelConnectionRepository metaPixelConnections;

    final ObjectMapper objectMapper = new ObjectMapper();

    static final String POSTER_URL = "https://cdn.example.com/poster-order.png";

    Instant startsAt;
    Instant endsAt;
    String buyer;

    @BeforeEach
    void setUp() {
        startsAt = clock.instant().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        endsAt = startsAt.plus(8, ChronoUnit.HOURS);
        buyer = fx.email("buyer");
    }

    @Test
    void getOrder_returnsFullPayload() throws Exception {
        Fixture f = persistOrderWithTicket("issued");

        mvc.perform(get("/api/v1/public/orders/" + f.order.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(f.order.getToken()))
                .andExpect(jsonPath("$.email").value(buyer))
                .andExpect(jsonPath("$.totalMinor").value(1500))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.paymentMethod").value("stripe"))
                .andExpect(jsonPath("$.event.eventId").value(f.event.getId().toString()))
                .andExpect(jsonPath("$.event.name").value(f.event.getName()))
                .andExpect(jsonPath("$.event.slug").value(f.event.getSlug()))
                .andExpect(jsonPath("$.event.startsAt").value(startsAt.toString()))
                .andExpect(jsonPath("$.event.endsAt").value(endsAt.toString()))
                .andExpect(jsonPath("$.event.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.event.venueName").value("Venue X"))
                .andExpect(jsonPath("$.event.venueCity").value("Berlin"))
                .andExpect(jsonPath("$.event.posterUrl").value(POSTER_URL))
                // No connection seeded for this org.
                .andExpect(jsonPath("$.event.metaPixelId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tickets.length()").value(1))
                .andExpect(jsonPath("$.tickets[0].token").value(f.ticket.getToken()))
                .andExpect(jsonPath("$.tickets[0].tierName").value("GA"))
                .andExpect(jsonPath("$.tickets[0].state").value("issued"))
                .andExpect(jsonPath("$.tickets[0].qrPayload").value(
                        org.hamcrest.Matchers.startsWith("imin1." + f.ticket.getToken() + ".")));
    }

    /**
     * THE W0.1 REGRESSION. A refunded ticket must not take the whole order down —
     * {@code 'refunded'} is a real value written by the refund flow.
     */
    @Test
    void getOrder_refundedTicket_returns200WithRefundedState() throws Exception {
        Fixture f = persistOrderWithTicket(Ticket.STATE_REFUNDED);

        mvc.perform(get("/api/v1/public/orders/" + f.order.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets[0].state").value("refunded"));
    }

    @Test
    void getOrder_metaPixelIdPresent_whenOrgHasActiveConnection() throws Exception {
        Fixture f = persistOrderWithTicket("issued");

        MetaPixelConnection c = new MetaPixelConnection();
        c.setId(UUID.randomUUID());
        c.setOrgId(f.event.getOrgId());
        c.setEventId(null); // org-wide default
        c.setPixelId("PIX-ORDER-1");
        c.setCapiAccessTokenEnc("enc");
        metaPixelConnections.save(c);

        mvc.perform(get("/api/v1/public/orders/" + f.order.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.metaPixelId").value("PIX-ORDER-1"));
    }

    @Test
    void getOrder_metaPixelIdNull_whenNoConnection() throws Exception {
        Fixture f = persistOrderWithTicket("issued");

        MvcResult result = mvc.perform(get("/api/v1/public/orders/" + f.order.getToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode event = objectMapper.readTree(result.getResponse().getContentAsString()).get("event");
        assertThat(event.hasNonNull("metaPixelId")).isFalse();
    }

    /**
     * THE LEAK GUARDRAIL.
     *
     * Asserts that the JSON response keys at the top level, within "event", and within
     * "tickets[0]" are EXACTLY the allow-listed sets. If this test fails, you added a
     * field to PublicOrderResponse (or a nested record). Verify it is safe to expose
     * publicly — this endpoint is unauthenticated, token-guessing is the only barrier —
     * then update this test's allowlist.
     */
    @Test
    void getOrder_responseHasOnlyAllowListedKeys() throws Exception {
        Fixture f = persistOrderWithTicket("issued");

        MvcResult result = mvc.perform(get("/api/v1/public/orders/" + f.order.getToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        Set<String> expectedRootKeys = Set.of(
                "token", "email", "totalMinor", "currency",
                "paymentMethod", "createdAt", "event", "tickets",
                // Receipt breakdown (spec §4.6). All four are money the buyer was
                // already shown at checkout, restated so the receipt reconciles to
                // the charge; none is new information about them.
                "lines", "subtotalMinor", "discountMinor", "feeMinor");
        assertThat(fieldNames(root))
                .as("Top-level keys leaked or missing. " +
                    "If this fails, you added a field to PublicOrderResponse. " +
                    "Verify it is safe to expose, then update this test's allowlist.")
                .isEqualTo(expectedRootKeys);

        Set<String> expectedEventKeys = Set.of(
                "eventId", "name", "slug", "startsAt", "endsAt", "timezone",
                "venueName", "venueStreet", "venueCity", "venuePostalCode", "venueCountry",
                "posterUrl", "metaPixelId");
        assertThat(fieldNames(root.get("event")))
                .as("event keys leaked or missing. " +
                    "If this fails, you added a field to PublicOrderResponse.Event. " +
                    "Verify it is safe to expose, then update this test's allowlist.")
                .isEqualTo(expectedEventKeys);

        Set<String> expectedTicketKeys = Set.of("token", "tierName", "state", "qrPayload",
                // Per-ticket, so the order page stops fetching a whole extra
                // ticket through a Suspense boundary to learn one boolean.
                "wallet");
        assertThat(fieldNames(root.get("tickets").get(0)))
                .as("tickets[0] keys leaked or missing. " +
                    "If this fails, you added a field to PublicOrderResponse.Ticket. " +
                    "Verify it is safe to expose, then update this test's allowlist.")
                .isEqualTo(expectedTicketKeys);
    }

    @Test
    void getOrder_returns404_forUnknownToken() throws Exception {
        mvc.perform(get("/api/v1/public/orders/ORD_nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // --- helpers ---

    private record Fixture(Event event, Order order, Ticket ticket) {}

    private Fixture persistOrderWithTicket(String ticketState) {
        Organization org = fx.org();
        User owner = fx.owner(org);

        Event ev = fx.event(org, owner, EventStatus.LIVE, startsAt);
        ev.setEndsAt(endsAt);
        ev.setVenueName("Venue X");
        ev.setVenueStreet("123 Main St");
        ev.setVenueCity("Berlin");
        ev.setVenuePostalCode("10115");
        ev.setVenueCountry("DE");
        ev.setPosterUrl(POSTER_URL);
        ev = events.save(ev);

        Order order = fx.order(ev, buyer);
        Ticket t = fx.ticket(order, ticketState);

        return new Fixture(ev, order, t);
    }

    private static Set<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(
                ((Iterable<String>) node::fieldNames).spliterator(), false
        ).collect(Collectors.toSet());
    }
}
