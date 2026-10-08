package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
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
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IminIntegrationTest
class PublicTicketPayloadTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;
    @Autowired EventRepository events;

    final ObjectMapper objectMapper = new ObjectMapper();

    static final String POSTER_URL = "https://cdn.example.com/poster-ticket.png";

    Instant startsAt;
    Instant endsAt;

    @BeforeEach
    void setUp() {
        startsAt = clock.instant().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        endsAt = startsAt.plus(8, ChronoUnit.HOURS);
    }

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
                .andExpect(jsonPath("$.event.startsAt").value(startsAt.toString()))
                .andExpect(jsonPath("$.event.endsAt").value(endsAt.toString()))
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
        Organization org = fx.org();
        Event ev = fx.event(org, fx.owner(org), EventStatus.LIVE, startsAt);
        ev.setEndsAt(endsAt);
        ev.setPosterUrl(POSTER_URL);
        ev = events.save(ev);
        return fx.ticket(fx.order(ev, fx.email("buyer")), state);
    }
}
