package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/buyer/saved} — PUBLIC_PAGE_API.md §20.4.
 *
 * <p>Documented since R1.4 and never implemented, so the buyer site has been
 * calling all four of these into a 404 the whole time. The properties that
 * matter and each have a test here: PUT is idempotent, merge is a
 * <b>union</b> that silently drops dead ids, and one account can never see
 * another's list.
 *
 * <p>Every state-changing call carries an {@code Origin} header.
 * {@code BuyerRequestGuardFilter} rejects POST/PUT/PATCH/DELETE without one
 * with a 403 before any controller runs — a test that omits it fails
 * identically whether the endpoint is correct or missing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerSavedEventsTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired MockMvc mvc;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @MockitoBean EmailService email;

    private String cookie;
    private UUID eventId;
    private UUID otherEventId;

    @BeforeEach
    void signedInBuyer() throws Exception {
        reset(email);
        cookie = signUpAndSignIn(address());
        eventId = event("Vechirka").getId();
        otherEventId = event("Second Night").getId();
        reset(email);
    }

    @Test
    void putIsIdempotentAndListReturnsIt() throws Exception {
        save(eventId).andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        save(eventId).andExpect(status().isNoContent());

        list().andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$[0].savedAt").exists());
    }

    @Test
    void deleteIsIdempotentToo() throws Exception {
        save(eventId).andExpect(status().isNoContent());
        unsave(eventId).andExpect(status().isNoContent());
        unsave(eventId).andExpect(status().isNoContent());

        list().andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void putOnAnUnknownEventIs404() throws Exception {
        save(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void mergeUnionsAndSilentlyDropsIdsThatNoLongerResolve() throws Exception {
        save(eventId).andExpect(status().isNoContent());

        merge(List.of(otherEventId, UUID.randomUUID()))
                .andExpect(status().isOk())
                // union: the pre-existing save survives, the resolvable id joins,
                // the dead id is dropped without an error.
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void mergeNeverDeletesWhatTheClientDidNotSend() throws Exception {
        save(eventId).andExpect(status().isNoContent());

        merge(List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(eventId.toString()));
    }

    @Test
    void savedEventsAreScopedToTheAccount() throws Exception {
        save(eventId).andExpect(status().isNoContent());

        String stranger = signUpAndSignIn(address());
        mvc.perform(get("/api/v1/buyer/saved").cookie(cookie(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theSavedEndpointsNeedABuyerSession() throws Exception {
        mvc.perform(get("/api/v1/buyer/saved")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/buyer/saved/" + eventId).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aStateChangeWithoutAnOriginIsRejectedBeforeTheController() throws Exception {
        mvc.perform(put("/api/v1/buyer/saved/" + eventId).cookie(cookie(cookie)))
                .andExpect(status().isForbidden());
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private ResultActions list() throws Exception {
        return mvc.perform(get("/api/v1/buyer/saved").cookie(cookie(cookie)));
    }

    private ResultActions save(UUID id) throws Exception {
        return mvc.perform(put("/api/v1/buyer/saved/" + id)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie)));
    }

    private ResultActions unsave(UUID id) throws Exception {
        return mvc.perform(delete("/api/v1/buyer/saved/" + id)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie)));
    }

    private ResultActions merge(List<UUID> ids) throws Exception {
        String body = "{\"eventIds\":["
                + String.join(",", ids.stream().map(id -> "\"" + id + "\"").toList())
                + "]}";
        return mvc.perform(post("/api/v1/buyer/saved/merge")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(cookie(cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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

    /** Signs a brand-new account up, verifies it, returns the session cookie value. */
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
