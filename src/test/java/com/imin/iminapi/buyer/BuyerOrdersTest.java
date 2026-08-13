package com.imin.iminapi.buyer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.OrderFixtures;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/buyer/orders} — the cross-organizer ticket list (R1.3).
 *
 * <p>The first test in this file is the one the whole slice exists to make
 * true: an <b>unverified</b> address returns nothing. Each item in this
 * response carries an {@code orderToken}, which opens an order page and its
 * tickets with no further authentication, so a query that matched unverified
 * rows would turn "add an address" into "read a stranger's tickets".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerOrdersTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired MockMvc mvc;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository emails;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @MockitoBean EmailService email;

    final ObjectMapper json = new ObjectMapper();

    private String primary;
    private String cookie;

    @BeforeEach
    void signedInBuyer() throws Exception {
        reset(email);
        primary = address();
        cookie = signUpAndSignIn(primary);
        reset(email);
    }

    // ── The security boundary ──────────────────────────────────────────────

    /**
     * THE TEST THIS SLICE EXISTS FOR. Between {@code POST /buyer/emails} and the
     * redeemed code the row is a claim anyone can make about any address. If it
     * ever grants read access, the neutral "check your inbox" response becomes a
     * theft primitive rather than a privacy measure.
     */
    @Test
    void an_unverified_address_returns_no_orders() throws Exception {
        String stranger = address();
        Event event = event("Unverified Probe");
        orderWithTickets(event, stranger, Instant.parse("2026-07-01T10:00:00Z"), "issued");

        addAddress(stranger).andExpect(status().isNoContent());

        assertThat(rowFor(stranger).isVerified()).isFalse();
        listOrders().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void verifying_the_address_is_what_joins_its_orders() throws Exception {
        String second = address();
        Event event = event("Join On Verify");
        Order theirs = orderWithTickets(event, second, Instant.parse("2026-07-02T10:00:00Z"), "issued");

        addAddress(second).andExpect(status().isNoContent());
        listOrders().andExpect(jsonPath("$.items.length()").value(0));

        verifyAddress(second, codeSentTo(second)).andExpect(status().isOk());

        listOrders().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderToken").value(theirs.getToken()));
    }

    @Test
    void removing_a_verified_address_takes_its_orders_back_out_of_the_list() throws Exception {
        String second = address();
        Event event = event("Removed Address");
        orderWithTickets(event, second, Instant.parse("2026-07-03T10:00:00Z"), "issued");
        addAddress(second).andExpect(status().isNoContent());
        verifyAddress(second, codeSentTo(second)).andExpect(status().isOk());
        listOrders().andExpect(jsonPath("$.items.length()").value(1));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(java.net.URI.create("/api/v1/buyer/emails/"
                                + java.net.URLEncoder.encode(second, java.nio.charset.StandardCharsets.UTF_8)
                                        .replace("+", "%2B")))
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie(cookie)))
                .andExpect(status().isNoContent());

        listOrders().andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void one_buyer_never_sees_another_buyers_orders() throws Exception {
        String other = address();
        Event event = event("Someone Else");
        orderWithTickets(event, other, Instant.parse("2026-07-04T10:00:00Z"), "issued");
        signUpAndSignIn(other);   // the other buyer verifies it on THEIR account

        listOrders().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void an_organizer_bearer_principal_cannot_read_the_order_list() throws Exception {
        Authentication organizer = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                        com.imin.iminapi.model.UserRole.OWNER, UUID.randomUUID()),
                null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));

        mvc.perform(get("/api/v1/buyer/orders").with(authentication(organizer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void the_list_needs_a_buyer_session() throws Exception {
        mvc.perform(get("/api/v1/buyer/orders")).andExpect(status().isUnauthorized());
    }

    // ── The payload ────────────────────────────────────────────────────────

    @Test
    void an_order_renders_with_its_event_block_and_ticket_state_counts() throws Exception {
        Event event = event("Payload");
        Order order = orderWithTickets(event, primary, Instant.parse("2026-07-05T10:00:00Z"),
                "issued", "issued", "redeemed", "refunded", "revoked", "pre");

        listOrders().andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderToken").value(order.getToken()))
                .andExpect(jsonPath("$.items[0].totalMinor").value(1500))
                .andExpect(jsonPath("$.items[0].currency").value("EUR"))
                .andExpect(jsonPath("$.items[0].ticketCount").value(6))
                // 'pre' is the legacy free-flow value; it folds into issued so the
                // buyer never sees two vocabularies for one state.
                .andExpect(jsonPath("$.items[0].states.issued").value(3))
                .andExpect(jsonPath("$.items[0].states.redeemed").value(1))
                .andExpect(jsonPath("$.items[0].states.refunded").value(1))
                .andExpect(jsonPath("$.items[0].states.revoked").value(1))
                .andExpect(jsonPath("$.items[0].event.id").value(event.getId().toString()))
                .andExpect(jsonPath("$.items[0].event.name").value("Payload"))
                .andExpect(jsonPath("$.items[0].event.venueCity").value("Berlin"))
                .andExpect(jsonPath("$.items[0].event.timezone").value("Europe/Berlin"));
    }

    /**
     * {@code qrPayload} is signed per ticket at read time and belongs on the page
     * that displays it, not in a list of everything a buyer has ever bought.
     * This also guards the wider rule: if a field is added here, decide first
     * whether it is safe in a body that is a bundle of bearer credentials.
     */
    @Test
    void the_item_carries_no_qr_payload_and_no_surprise_fields() throws Exception {
        orderWithTickets(event("Allowlist"), primary, Instant.parse("2026-07-06T10:00:00Z"), "issued");

        MvcResult result = listOrders().andExpect(status().isOk()).andReturn();
        JsonNode item = json.readTree(result.getResponse().getContentAsString()).get("items").get(0);

        assertThat(names(item)).containsExactlyInAnyOrder(
                "orderToken", "createdAt", "totalMinor", "currency", "event", "ticketCount", "states");
        assertThat(names(item.get("event"))).containsExactlyInAnyOrder(
                "id", "name", "slug", "startsAt", "endsAt", "timezone",
                "venueName", "venueCity", "posterUrl");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("qrPayload");
    }

    @Test
    void the_list_crosses_organizers_and_is_newest_first() throws Exception {
        Event a = event("Org A");
        Event b = event("Org B");
        assertThat(a.getOrgId()).isNotEqualTo(b.getOrgId());
        Order older = orderWithTickets(a, primary, Instant.parse("2026-05-01T10:00:00Z"), "issued");
        Order newer = orderWithTickets(b, primary, Instant.parse("2026-06-01T10:00:00Z"), "issued");

        listOrders().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].orderToken").value(newer.getToken()))
                .andExpect(jsonPath("$.items[1].orderToken").value(older.getToken()));
    }

    @Test
    void orders_from_two_verified_addresses_appear_in_one_list() throws Exception {
        Event event = event("Two Addresses");
        String second = address();
        orderWithTickets(event, primary, Instant.parse("2026-05-02T10:00:00Z"), "issued");
        orderWithTickets(event, second, Instant.parse("2026-05-03T10:00:00Z"), "issued");

        addAddress(second).andExpect(status().isNoContent());
        verifyAddress(second, codeSentTo(second)).andExpect(status().isOk());

        listOrders().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    // ── Pagination ─────────────────────────────────────────────────────────

    @Test
    void the_default_page_is_twenty_and_a_large_set_is_bounded() throws Exception {
        Event event = event("Bounded");
        seedOrders(event, primary, 60);

        listOrders().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    @Test
    void a_caller_asking_for_more_than_fifty_gets_fifty_rather_than_an_error() throws Exception {
        Event event = event("Clamped");
        seedOrders(event, primary, 60);

        listOrders("?limit=1000").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50));
    }

    /**
     * Every row exactly once, and no row twice. Keyset paging on
     * {@code (created_at DESC, id DESC)} is what buys that; an offset would
     * repeat or skip rows the moment the set changed underneath the walk, and
     * {@code created_at} alone is not a key because orders can share a timestamp.
     */
    @Test
    void walking_the_cursor_visits_every_order_exactly_once() throws Exception {
        Event event = event("Walk");
        int total = 47;
        seedOrders(event, primary, total);

        List<String> seen = new ArrayList<>();
        String cursorParam = "?limit=7";
        for (int page = 0; page < 20; page++) {
            JsonNode body = json.readTree(
                    listOrders(cursorParam).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString());
            body.get("items").forEach(item -> seen.add(item.get("orderToken").asText()));
            if (body.get("nextCursor").isNull()) break;
            cursorParam = "?limit=7&cursor=" + body.get("nextCursor").asText();
        }

        assertThat(seen).hasSize(total);
        assertThat(seen).doesNotHaveDuplicates();
    }

    /**
     * Ten orders sharing one {@code created_at} — the case a timestamp-only
     * cursor gets wrong, either looping forever or silently dropping nine rows.
     */
    @Test
    void a_cursor_survives_orders_that_share_a_timestamp() throws Exception {
        Event event = event("Same Instant");
        Instant sameMoment = Instant.parse("2026-04-01T12:00:00Z");
        for (int i = 0; i < 10; i++) {
            orderWithTickets(event, primary, sameMoment, "issued");
        }

        List<String> seen = new ArrayList<>();
        String params = "?limit=3";
        for (int page = 0; page < 10; page++) {
            JsonNode body = json.readTree(listOrders(params).andReturn().getResponse().getContentAsString());
            body.get("items").forEach(item -> seen.add(item.get("orderToken").asText()));
            if (body.get("nextCursor").isNull()) break;
            params = "?limit=3&cursor=" + body.get("nextCursor").asText();
        }

        assertThat(seen).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void the_last_page_carries_no_cursor() throws Exception {
        Event event = event("Last Page");
        seedOrders(event, primary, 3);

        listOrders("?limit=10").andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void an_empty_list_is_an_empty_array_and_not_a_404() throws Exception {
        listOrders().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void a_malformed_cursor_is_a_400_rather_than_a_silent_restart() throws Exception {
        // Silently restarting at page one would show a buyer the top of their
        // list again and read as data loss.
        listOrders("?cursor=not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void a_cursor_forged_from_another_buyers_page_still_only_walks_your_own_list() throws Exception {
        // The cursor is opaque but unsigned, which is safe precisely because the
        // query is re-scoped to the caller's verified addresses on every call.
        Event event = event("Forged Cursor");
        String other = address();
        Order theirs = orderWithTickets(event, other, Instant.parse("2026-03-01T10:00:00Z"), "issued");
        signUpAndSignIn(other);

        String forged = com.imin.iminapi.buyer.service.BuyerOrdersCursor
                .encode(theirs.getCreatedAt().plusSeconds(1), theirs.getId());

        listOrders("?cursor=" + forged).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private static String address() {
        return "ada+" + UUID.randomUUID() + "@example.com";
    }

    private Event event(String name) {
        return OrderFixtures.event(orgs, users, events, name, Instant.parse("2026-12-01T20:00:00Z"));
    }

    private Order orderWithTickets(Event event, String buyerEmail, Instant createdAt, String... states) {
        Order order = OrderFixtures.order(orders, event, buyerEmail, createdAt);
        for (String state : states) {
            OrderFixtures.ticket(tickets, order, state);
        }
        return order;
    }

    private void seedOrders(Event event, String buyerEmail, int count) {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            orderWithTickets(event, buyerEmail, base.plusSeconds(i * 3600L), "issued");
        }
    }

    private ResultActions listOrders() throws Exception {
        return listOrders("");
    }

    private ResultActions listOrders(String query) throws Exception {
        return mvc.perform(get("/api/v1/buyer/orders" + query).cookie(cookie(cookie)));
    }

    private ResultActions addAddress(String to) throws Exception {
        return mvc.perform(post("/api/v1/buyer/emails")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\"}"));
    }

    private ResultActions verifyAddress(String to, String code) throws Exception {
        return mvc.perform(post("/api/v1/buyer/emails/verify")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\",\"code\":\"" + code + "\"}"));
    }

    private String signUpAndSignIn(String to) throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/signup")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + to + "\",\"password\":\"" + PASSWORD + "\",\"locale\":\"en\"}"))
                .andExpect(status().isNoContent());
        MvcResult verified = mvc.perform(post("/api/v1/buyer/auth/verify-email")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + to + "\",\"code\":\"" + codeSentTo(to) + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie set = verified.getResponse().getCookie(BuyerSessionCookie.NAME);
        assertThat(set).isNotNull();
        return set.getValue();
    }

    private static Cookie cookie(String raw) {
        return new Cookie(BuyerSessionCookie.NAME, raw);
    }

    private com.imin.iminapi.buyer.model.BuyerAccountEmail rowFor(String to) {
        String normalized = to.trim().toLowerCase();
        List<com.imin.iminapi.buyer.model.BuyerAccountEmail> rows = accounts.findAll().stream()
                .flatMap(a -> emails.findByBuyerAccountIdOrderByCreatedAtAsc(a.getId()).stream())
                .filter(r -> normalized.equals(r.getEmailNormalized()))
                .toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private String codeSentTo(String to) {
        return bodySentTo(to).map(body -> {
            Matcher m = SIX_DIGITS.matcher(body);
            return m.find() ? m.group(1) : null;
        }).orElse(null);
    }

    private Optional<String> bodySentTo(String to) {
        ArgumentCaptor<String> recipients = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        try {
            verify(email, atLeast(0)).send(recipients.capture(), subject.capture(), html.capture(), text.capture());
        } catch (AssertionError e) {
            return Optional.empty();
        }
        List<String> to0 = recipients.getAllValues();
        for (int i = to0.size() - 1; i >= 0; i--) {
            if (to.equalsIgnoreCase(to0.get(i))) return Optional.of(text.getAllValues().get(i));
        }
        return Optional.empty();
    }
}
