package com.imin.iminapi.buyer;

import com.imin.iminapi.email.RecordingEmailService;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signup → verify → <b>native</b> sign-in, for every test that needs a bearer
 * token rather than a session cookie.
 *
 * <p>Deliberately not annotated: subclasses carry {@code @IminIntegrationTest}
 * themselves, so a reader of a concrete test still sees which context it runs in.
 * The six-digit code is read back out of the shared {@link RecordingEmailService}.
 */
abstract class NativeBuyerTestBase {

    protected static final String ORIGIN = "http://localhost:3000";
    protected static final String PASSWORD = "correct-horse-battery";

    @Autowired protected MockMvc mvc;

    /** The recording mailer the six-digit verification code is read back out of. */
    @Autowired protected RecordingEmailService mail;

    /** Buyer account mail is sent AFTER_COMMIT on this pool — see {@link BuyerMailSync}. */
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("ticketEmailExecutor")
    protected java.util.concurrent.Executor mailExecutor;

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
     * Signup → read the six-digit code out of the newest mail to {@code to} → verify.
     * Every account has its own address, so a second account in one test reads its own code.
     */
    protected void register(String to) throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/signup")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + to + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        String code = BuyerMailSync.codeTo(mail, mailExecutor, to);

        mvc.perform(post("/api/v1/buyer/auth/verify-email")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + to + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        BuyerMailSync.drain(mailExecutor);
    }
}
