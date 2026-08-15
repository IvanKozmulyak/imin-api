package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.security.BuyerSessionCookie;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The native bearer lane. The properties that matter:
 * a native sign-in gets a token, that token authenticates without a cookie and
 * without an Origin, a web sign-in is byte-identical to before, and a request
 * carrying a cookie still gets the full CSRF guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerNativeSessionTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired MockMvc mvc;
    @MockitoBean EmailService email;

    private String address;

    @BeforeEach
    void freshAddress() {
        reset(email);
        address = "native-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    void nativeLoginReturnsATokenAndNoCookie() throws Exception {
        register(address);

        MvcResult nativeLogin = mvc.perform(post("/api/v1/buyer/auth/login")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(address)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").isNotEmpty())
                // React Native keeps a platform cookie jar. If we emit this, the
                // app stores it, stops looking cookieless, and every later
                // mutation 403s on the missing Origin.
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertThat(tokenOf(nativeLogin)).isNotBlank();
    }

    @Test
    void webLoginReturnsACookieAndNoToken() throws Exception {
        register(address);

        mvc.perform(post("/api/v1/buyer/auth/login")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(address)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").doesNotExist())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("imin_buyer_session")));
    }

    /**
     * The escalation the Origin condition exists to stop: page JavaScript on
     * app.imin.wtf can set X-Imin-Client (buyer CORS allows any header), but it
     * cannot suppress the Origin its own browser attaches.
     */
    @Test
    void pageJavascriptCannotHarvestTheTokenByAddingTheNativeHeader() throws Exception {
        register(address);

        mvc.perform(post("/api/v1/buyer/auth/login")
                        .header("X-Imin-Client", "native")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(address)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").doesNotExist());
    }

    /**
     * Precedence, pinned. A stale or planted cookie must never win over the
     * bearer token the caller actually presented.
     */
    @Test
    void bearerWinsWhenACookieIsAlsoPresent() throws Exception {
        register(address);
        String bearerA = tokenOf(nativeLogin(address));

        String other = "other-" + UUID.randomUUID() + "@example.test";
        register(other);
        MvcResult webB = mvc.perform(post("/api/v1/buyer/auth/login")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(other)))
                .andExpect(status().isOk())
                .andReturn();
        String cookieB = sessionCookieValue(webB);
        assertThat(cookieB).isNotNull();

        mvc.perform(get("/api/v1/buyer/me")
                        .header("Authorization", "Bearer " + bearerA)
                        .cookie(new Cookie(BuyerSessionCookie.NAME, cookieB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emails[0].email").value(address));
    }

    @Test
    void bearerTokenAuthenticatesWithNoCookieAndNoOrigin() throws Exception {
        register(address);
        String token = tokenOf(nativeLogin(address));

        mvc.perform(get("/api/v1/buyer/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emails[0].email").value(address));

        // A state-changing call with no Origin at all — the case that 403s today.
        mvc.perform(post("/api/v1/buyer/auth/logout")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Imin-Client", "native"))
                .andExpect(status().isNoContent());
    }

    @Test
    void revokedTokenStopsWorking() throws Exception {
        register(address);
        String token = tokenOf(nativeLogin(address));

        mvc.perform(post("/api/v1/buyer/auth/logout")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Imin-Client", "native"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/buyer/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieCarryingRequestStillNeedsAnOriginEvenWithTheNativeHeader() throws Exception {
        register(address);
        MvcResult webLogin = mvc.perform(post("/api/v1/buyer/auth/login")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(address)))
                .andExpect(status().isOk())
                .andReturn();
        String cookie = sessionCookieValue(webLogin);
        assertThat(cookie).isNotNull();

        // Native header present, but so is a cookie: the guard must NOT be skipped.
        mvc.perform(post("/api/v1/buyer/auth/logout")
                        .header("X-Imin-Client", "native")
                        .cookie(new Cookie(BuyerSessionCookie.NAME, cookie)))
                .andExpect(status().isForbidden());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private MvcResult nativeLogin(String to) throws Exception {
        return mvc.perform(post("/api/v1/buyer/auth/login")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(to)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String tokenOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"sessionToken\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as("sessionToken in %s", body).isTrue();
        return m.group(1);
    }

    /**
     * MockHttpServletRequest does not parse a raw {@code Cookie} header into
     * {@code getCookies()}, so the cookie has to be replayed with
     * {@code .cookie(...)} — the same thing the rest of the buyer suite does.
     */
    private static String sessionCookieValue(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(BuyerSessionCookie.NAME);
        return cookie == null ? null : cookie.getValue();
    }

    private static String loginBody(String to) {
        return "{\"email\":\"" + to + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    /** Signup → read the six-digit code out of the mocked mail → verify. */
    private void register(String to) throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/signup")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + to + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, atLeast(1)).send(org.mockito.ArgumentMatchers.eq(to),
                org.mockito.ArgumentMatchers.anyString(), html.capture(),
                org.mockito.ArgumentMatchers.anyString());
        Matcher m = SIX_DIGITS.matcher(html.getValue());
        assertThat(m.find()).isTrue();

        mvc.perform(post("/api/v1/buyer/auth/verify-email")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + to + "\",\"code\":\"" + m.group(1) + "\"}"))
                .andExpect(status().isOk());
        reset(email);
    }
}
