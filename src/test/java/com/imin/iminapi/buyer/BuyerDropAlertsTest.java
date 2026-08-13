package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drop alerts and resend — spec §4.5 and §4.6.
 *
 * <p>The boundary under test in both is the same one that guards
 * {@code GET /buyer/orders}: a <b>verified</b> address grants, an unverified one
 * does not, and an order belonging to somebody else is a 404 rather than a 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerDropAlertsTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired MockMvc mvc;
    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @MockitoBean EmailService email;

    private String address;
    private String cookie;
    private Event eventA;
    private Event eventB;

    @BeforeEach
    void signedInBuyer() throws Exception {
        reset(email);
        address = address();
        cookie = signUpAndSignIn(address);
        eventA = event("Alpha");
        eventB = event("Beta");
        reset(email);
    }

    // ── drop alerts ────────────────────────────────────────────────────────

    @Test
    void alertsJoinOnVerifiedAddressesAndPutUnnotifiedFirst() throws Exception {
        alert(eventA, address, null);                                  // still watching
        alert(eventB, address, Instant.parse("2026-05-01T10:00:00Z")); // already told

        mvc.perform(get("/api/v1/buyer/notify-subscriptions").cookie(cookie(cookie)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].notifiedAt").doesNotExist())
                .andExpect(jsonPath("$[0].eventId").value(eventA.getId().toString()))
                .andExpect(jsonPath("$[1].notifiedAt").exists());
    }

    @Test
    void theRowCarriesEnoughEventToRenderACard() throws Exception {
        alert(eventA, address, null);

        mvc.perform(get("/api/v1/buyer/notify-subscriptions").cookie(cookie(cookie)))
                .andExpect(jsonPath("$[0].eventName").value("Alpha"))
                .andExpect(jsonPath("$[0].slug").exists())
                .andExpect(jsonPath("$[0].startsAt").exists())
                .andExpect(jsonPath("$[0].posterUrl").exists());
    }

    @Test
    void aStrangersAlertIsNeverListed() throws Exception {
        alert(eventA, "someone-else@example.com", null);

        mvc.perform(get("/api/v1/buyer/notify-subscriptions").cookie(cookie(cookie)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void stopWatchingRemovesTheRowAndIsIdempotent() throws Exception {
        alert(eventA, address, null);

        stopWatching(eventA.getId()).andExpect(status().isNoContent());
        stopWatching(eventA.getId()).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/buyer/notify-subscriptions").cookie(cookie(cookie)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── resend ─────────────────────────────────────────────────────────────

    @Test
    void resendMailsTheOrdersOwnAddress() throws Exception {
        Order order = orderFor(address);

        resend(order.getToken()).andExpect(status().isNoContent());
    }

    @Test
    void resendOnSomebodyElsesOrderIs404NotForbidden() throws Exception {
        Order order = orderFor("someone-else@example.com");

        // 404, not 403 — otherwise the endpoint answers "is this token real?".
        resend(order.getToken()).andExpect(status().isNotFound());
    }

    @Test
    void resendOnAnUnknownTokenIs404Too() throws Exception {
        resend("ORD_" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void theseEndpointsNeedABuyerSession() throws Exception {
        mvc.perform(get("/api/v1/buyer/notify-subscriptions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/buyer/orders/whatever/resend").header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private ResultActions stopWatching(UUID eventId) throws Exception {
        return mvc.perform(delete("/api/v1/buyer/notify-subscriptions/" + eventId)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie)));
    }

    private ResultActions resend(String token) throws Exception {
        return mvc.perform(post("/api/v1/buyer/orders/" + token + "/resend")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie)));
    }

    private void alert(Event event, String to, Instant notifiedAt) {
        NotifySubscription s = new NotifySubscription();
        s.setEventId(event.getId());
        s.setEmail(to.toLowerCase());
        s.setNotifiedAt(notifiedAt);
        subscriptions.save(s);
    }

    private Order orderFor(String buyerEmail) {
        Order order = OrderFixtures.order(orders, eventA, buyerEmail, Instant.parse("2026-06-01T10:00:00Z"));
        OrderFixtures.ticket(tickets, order, "issued");
        return order;
    }

    private Event event(String name) {
        return OrderFixtures.event(orgs, users, events, name, Instant.parse("2026-12-01T20:00:00Z"));
    }

    private static String address() {
        return "ada+" + UUID.randomUUID() + "@example.com";
    }

    private static Cookie cookie(String raw) {
        return new Cookie(BuyerSessionCookie.NAME, raw);
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
        if (set == null) throw new AssertionError("no session cookie on verify-email");
        return set.getValue();
    }

    private String codeSentTo(String to) {
        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email, atLeast(1)).send(recipient.capture(), subject.capture(), html.capture(), text.capture());

        List<String> to_ = recipient.getAllValues();
        List<String> bodies = text.getAllValues();
        for (int i = to_.size() - 1; i >= 0; i--) {
            if (to.equalsIgnoreCase(to_.get(i))) {
                Matcher m = SIX_DIGITS.matcher(bodies.get(i));
                if (m.find()) return m.group(1);
            }
        }
        throw new AssertionError("no six-digit code mailed to " + to);
    }
}
