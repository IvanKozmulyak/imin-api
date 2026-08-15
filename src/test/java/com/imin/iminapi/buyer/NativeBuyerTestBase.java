package com.imin.iminapi.buyer;

import com.imin.iminapi.email.EmailService;
import com.jayway.jsonpath.JsonPath;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signup → verify → <b>native</b> sign-in, for every test that needs a bearer
 * token rather than a session cookie.
 *
 * <p>Deliberately not annotated. Subclasses carry {@code @SpringBootTest},
 * {@code @AutoConfigureMockMvc} and {@code @Import(TestRateLimitConfig.class)}
 * themselves, so a reader of a concrete test still sees how its context is
 * built.
 *
 * <p><b>{@code mvc} and {@code email} live here and must not be re-declared in
 * a subclass.</b> Spring collects {@code @BeanOverride} fields across the whole
 * class hierarchy, and two by-type Mockito handlers with the same type and the
 * same field name compare equal — the override registry's {@code Assert.state}
 * then fails the context before a single test runs. Inherited field injection
 * covers both, so a subclass simply uses them.
 */
abstract class NativeBuyerTestBase {

    protected static final String ORIGIN = "http://localhost:3000";
    protected static final String PASSWORD = "correct-horse-battery";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired protected MockMvc mvc;

    /** The mocked mailer the six-digit verification code is read back out of. */
    @MockitoBean protected EmailService email;

    /** A brand-new address, so tests never collide on the account uniqueness rule. */
    protected static String newAddress() {
        return "native-" + UUID.randomUUID() + "@example.test";
    }

    /**
     * A verified account signed in the way the app does it, returning the raw
     * bearer token. Each call makes a <b>different</b> buyer.
     */
    protected String signUpAndSignInNative() throws Exception {
        return signUpAndSignInNative(newAddress());
    }

    protected String signUpAndSignInNative(String to) throws Exception {
        register(to);
        return tokenOf(nativeLogin(to));
    }

    /**
     * The account id behind a bearer token, read back off {@code /buyer/me} —
     * the only place a test can learn it without reaching around the API.
     */
    protected UUID accountIdOf(String bearer) throws Exception {
        String body = mvc.perform(get("/api/v1/buyer/me")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** A native sign-in: no {@code Origin}, no cookie, token in the body. */
    protected MvcResult nativeLogin(String to) throws Exception {
        return mvc.perform(post("/api/v1/buyer/auth/login")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(to)))
                .andExpect(status().isOk())
                .andReturn();
    }

    protected static String tokenOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"sessionToken\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as("sessionToken in %s", body).isTrue();
        return m.group(1);
    }

    protected static String loginBody(String to) {
        return "{\"email\":\"" + to + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    /**
     * Signup → read the six-digit code out of the mocked mail → verify.
     *
     * <p>The parameter is {@code to}, not {@code email}: naming it {@code email}
     * would shadow the {@code @MockitoBean EmailService email} field, so
     * {@code verify(email, …)} would infer {@code String} and stop compiling.
     *
     * <p>The mock is reset on the way in as well as on the way out, so a second
     * account created inside one test method reads its own code and not the
     * previous one's.
     */
    protected void register(String to) throws Exception {
        reset(email);
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
