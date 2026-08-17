package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
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

    final ObjectMapper objectMapper = new ObjectMapper();

    static final Instant STARTS_AT = Instant.parse("2026-09-01T20:00:00Z");
    static final Instant ENDS_AT = Instant.parse("2026-09-02T04:00:00Z");
    static final String POSTER_URL = "https://cdn.example.com/poster-ticket.png";

    @Test
    void getTicket_emitsSignedQrPayloadAndWalletFlag() throws Exception {
        Ticket t = persistTicket("issued");

        mvc.perform(get("/api/v1/public/tickets/" + t.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrPayload").value(
                        org.hamcrest.Matchers.startsWith("imin1." + t.getToken() + ".")))
                .andExpect(jsonPath("$.walletAvailable").isBoolean())
                .andExpect(jsonPath("$.qrUrl").value(
                        org.hamcrest.Matchers.endsWith(
                                "/api/v1/public/tickets/" + t.getToken() + "/qr.png")))
                .andExpect(jsonPath("$.state").value("issued"))
                .andExpect(jsonPath("$.event.eventId").value(t.getEventId().toString()))
                .andExpect(jsonPath("$.event.startsAt").value("2026-09-01T20:00:00Z"))
                .andExpect(jsonPath("$.event.endsAt").value("2026-09-02T04:00:00Z"))
                .andExpect(jsonPath("$.event.posterUrl").value(POSTER_URL));
    }

    /**
     * THE W0.1 REGRESSION. {@code RefundService} writes {@code 'refunded'}; before
     * TicketState mapped it, every read of a refunded ticket 500'd.
     */
    @Test
    void getTicket_refunded_returns200WithRefundedState() throws Exception {
        Ticket t = persistTicket(Ticket.STATE_REFUNDED);

        mvc.perform(get("/api/v1/public/tickets/" + t.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("refunded"));
    }

    /**
     * THE LEAK GUARDRAIL — same discipline as PublicEventControllerTest. If this
     * fails you added a field to PublicTicketResponse (or a nested record). Verify
     * it is safe to expose on this unauthenticated endpoint, then update the list.
     */
    @Test
    void getTicket_responseHasOnlyAllowListedKeys() throws Exception {
        Ticket t = persistTicket("issued");

        MvcResult result = mvc.perform(get("/api/v1/public/tickets/" + t.getToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(fieldNames(root))
                .as("Top-level keys leaked or missing on PublicTicketResponse.")
                .isEqualTo(Set.of("token", "state", "tierName", "qrPayload", "qrUrl",
                        "walletAvailable", "wallet", "event", "order"));

        assertThat(fieldNames(root.get("wallet")))
                .as("wallet keys leaked or missing on TicketWallets. Keyed by wallet "
                    + "VENDOR, never by device platform — see the record's javadoc.")
                .isEqualTo(Set.of("apple", "google"));

        // Both nested objects are always present with both keys, including on a
        // server where neither wallet is configured (which is this one). A
        // closed wallet is `{available:false,url:null}`, never an absent object
        // and never an absent key: a client that has to distinguish "false" from
        // "missing" is a client that will get one of them wrong.
        for (String vendor : new String[]{"apple", "google"}) {
            assertThat(fieldNames(root.get("wallet").get(vendor)))
                    .as("wallet." + vendor + " keys leaked or missing on TicketWallets.WalletPass. "
                        + "No reason/state field belongs here — a buyer can act on none of it.")
                    .isEqualTo(Set.of("available", "url"));
        }

        assertThat(fieldNames(root.get("event")))
                .as("event keys leaked or missing on PublicTicketResponse.Event. " +
                    "metaPixelId belongs to the order page only — do not add it here.")
                .isEqualTo(Set.of("eventId", "name", "slug", "startsAt", "endsAt", "timezone",
                        "venueName", "venueStreet", "venueCity", "venuePostalCode",
                        "venueCountry", "posterUrl"));

        assertThat(fieldNames(root.get("order")))
                .as("order keys leaked or missing on PublicTicketResponse.Order.")
                .isEqualTo(Set.of("token", "email"));
    }

    private static Set<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(
                ((Iterable<String>) node::fieldNames).spliterator(), false
        ).collect(Collectors.toSet());
    }

    private Ticket persistTicket(String state) {
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
        ev.setStartsAt(STARTS_AT);
        ev.setEndsAt(ENDS_AT);
        ev.setPosterUrl(POSTER_URL);
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
        t.setState(state);
        return tickets.save(t);
    }
}
