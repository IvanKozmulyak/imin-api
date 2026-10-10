package com.imin.iminapi.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.util.Times;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public refund-request surface over the real service: no enumeration, and allow-listed keys only. */
@IminIntegrationTest
class PublicRefundRequestControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingEmailService mail;
    @Autowired RefundRequestTokenRepository tokens;
    @Autowired RefundRequestRepository requests;
    @Autowired Clock clock;
    final ObjectMapper json = new ObjectMapper();

    private UUID orgId;
    private Order order;
    private String buyer;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgId = org.getId();
        User owner = fx.owner(org);
        Event event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(30)));
        buyer = fx.email("buyer");
        order = fx.order(event, buyer);
        // test_mode as checkout stamps it under the suite's sk_test key.
        jdbc.update("UPDATE orders SET stripe_payment_intent_id = ?, test_mode = true WHERE id = ?",
                "pi_" + UUID.randomUUID().toString().replace("-", ""), order.getId());
        fx.ticket(order, Ticket.STATE_ISSUED);
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, List.of(orgId));
    }

    /** The answer is the same 200 whatever is asked, so a probe cannot tell which addresses bought. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"known order email", "unknown email", "empty body"})
    void linkRequest_isAlways200_andMailsOnlyAKnownBuyer(String kind) throws Exception {
        String unknown = fx.email("nobody");
        MockHttpServletRequestBuilder req = post("/api/v1/public/refund-requests")
                .contentType(MediaType.APPLICATION_JSON)
                // A fresh client address per call: the per-IP hourly cap must not be what keeps a mail back.
                .with(r -> { r.setRemoteAddr(randomIp()); return r; });
        switch (kind) {
            case "known order email" -> req.content("{\"email\":\"" + buyer + "\"}");
            case "unknown email" -> req.content("{\"email\":\"" + unknown + "\"}");
            default -> { }
        }

        mvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        assertThat(mailsTo(buyer)).isEqualTo("known order email".equals(kind) ? 1 : 0);
        assertThat(mailsTo(unknown)).isZero();
    }

    // Both by-token routes are PUBLIC: the token travels by email and ends up in a URL, so a widened DTO
    // leaks to whoever holds the link. A failure here means a field was added — check it is safe, then the list.

    @Test
    void submit_withARealToken_is201_withOnlyAllowListedKeys() throws Exception {
        String raw = token();

        MvcResult result = mvc.perform(post("/api/v1/public/refund-requests/by-token/{t}", raw)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"cant_attend\",\"explanation\":\"Can't make it.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn();

        JsonNode root = json.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(root))
                .as("PublicRefundSubmitResponse keys leaked or missing")
                .isEqualTo(Set.of("id", "reference", "status", "submittedAt"));
        // The buyer receipt quotes the reference, not the UUID.
        RefundRequest stored = requests.findById(UUID.fromString(root.get("id").asText())).orElseThrow();
        assertThat(stored.getOrderId()).isEqualTo(order.getId());
        assertThat(root.get("reference").asText()).isEqualTo(stored.getReference())
                .matches(RefundReferenceGenerator.SHAPE);
    }

    @Test
    void form_hasOnlyAllowListedKeys() throws Exception {
        fx.ticket(order, Ticket.STATE_REDEEMED);
        fx.ticket(order, Ticket.STATE_REDEEMED);

        MvcResult result = mvc.perform(get("/api/v1/public/refund-requests/by-token/{t}", token()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = json.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(root))
                .as("PublicRefundFormResponse keys leaked or missing")
                .isEqualTo(Set.of("orderId", "event", "tickets", "nonRefundableTicketCount",
                        "estimatedRefundMinor", "currency", "reasons", "openRequestReference"));
        // imin-public gates its banner on `nonRefundableTicketCount ?? 0`: absent or null reads as "none excluded".
        assertThat(root.get("nonRefundableTicketCount").isNumber())
                .as("nonRefundableTicketCount must serialise as a JSON number")
                .isTrue();
        assertThat(root.get("nonRefundableTicketCount").asInt()).isEqualTo(2);
        assertThat(fieldNames(root.get("event")))
                .as("PublicRefundFormResponse.EventSummary keys leaked or missing")
                .isEqualTo(Set.of("name", "startsAt", "timezone", "venueName", "currency"));
        assertThat(fieldNames(root.get("tickets").get(0)))
                .as("PublicRefundFormResponse.TicketLine keys leaked or missing")
                .isEqualTo(Set.of("id", "tierName", "faceMinor"));
    }

    /** An order the running Stripe key cannot refund gets no form, no estimate and no request on file. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"form", "submit"})
    void wrongModeOrder_is409OrderNotRefundable_notNoRefundableTickets(String route) throws Exception {
        jdbc.update("UPDATE orders SET test_mode = false WHERE id = ?", order.getId());
        String raw = token();

        MockHttpServletRequestBuilder req = "form".equals(route)
                ? get("/api/v1/public/refund-requests/by-token/{t}", raw)
                : post("/api/v1/public/refund-requests/by-token/{t}", raw)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"cant_attend\",\"explanation\":\"Can't make it.\"}");
        mvc.perform(req)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_REFUNDABLE"))
                .andExpect(jsonPath("$.error.message").value("This order cannot be refunded online. Contact the organizer."));
        assertThat(requests.findFirstByOrderIdAndStatus(order.getId(), RefundRequestStatus.PENDING)).isEmpty();
    }

    /** A live, unconsumed link token for this test's order; returns the raw token the email would carry. */
    private String token() {
        String raw = "rt-" + UUID.randomUUID();
        RefundRequestToken t = new RefundRequestToken();
        t.setTokenHash(RefundRequestService.sha256Hex(raw));
        t.setOrderId(order.getId());
        t.setEmailNormalized(buyer);
        t.setExpiresAt(Times.nowMicros().plus(Duration.ofHours(1)));
        tokens.save(t);
        return raw;
    }

    private long mailsTo(String to) {
        return mail.sent().stream().filter(m -> to.equals(m.to())).count();
    }

    private static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
