# Mobile Phase 0 — Backend Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `imin-api` serve a native mobile buyer client — token auth, a native payment sheet, native sign-in, and push notifications — so the Expo app in `imin-fan-app` can be built against a real contract.

**Status:** revised 2026-08-15 after an adversarial review (`2026-08-15-mobile-phase0-backend-REVIEW.md`, 38 verified findings). Two blockers in Task 3 — a native buyer being charged without receiving a ticket, and no path from a PaymentIntent to its order — are fixed in Steps 6 and 7 of that task. **Read Task 3's "two facts about fulfilment" note before writing any code there.**

**Architecture:** Every change is **additive**. The browser at `app.imin.wtf` must observe byte-identical behaviour after this plan lands: same cookie, same CSRF guard, same hosted-Checkout redirect, same responses. Native clients identify themselves with one header (`X-Imin-Client: native`) and get a bearer token instead of a cookie; native sign-in verifies OS-issued ID tokens directly instead of driving a browser redirect; native payment uses a PaymentIntent instead of a hosted Checkout Session, over the *same* reservation and fulfilment machinery.

**Tech Stack:** Java 17 · Spring Boot 4.0.5 · Spring Security · Spring Data JPA · Flyway · PostgreSQL 17 (H2 PG-compat in tests) · Stripe Java SDK · Nimbus JOSE+JWT · JUnit 5 + MockMvc + Mockito

---

## Global Constraints

- **Additive only.** No existing endpoint changes its request shape, response shape, or status codes for a browser client. Every new response field is nullable and absent-by-default for web.
- **Migrations:** Flyway forward-only, next numbers are **V92** (Task 6) and **V93** (Task 8). Never edit an existing migration. Use `TIMESTAMP WITH TIME ZONE`; **no partial indexes** (H2 backs the test suite — use the leading-marker index shape from V87).
- **Tests:** `./mvnw test` must stay fully green. Tests run on H2 in PG-compat mode; all external services (Stripe, Expo, Google, Apple JWKS) are mocked. New integration tests follow `BuyerSavedEventsTest`: `@SpringBootTest @AutoConfigureMockMvc @Import(TestRateLimitConfig.class)`.
- **Nullable String params in JPQL:** never put a nullable `String` into `concat`/`lower`/`like` — it passes on H2 and 500s on Postgres (`lower(bytea)`). Split into two queries.
- **Security invariants that must survive:** buyer data is never readable by an organizer token (`hasRole("BUYER")`, not `.authenticated()`); a cookie-carrying request is always subject to the full `BuyerRequestGuardFilter`; `emailVerified` gates any flow that joins or mints an address claim.
- **Commits:** one per task, conventional-commit prefix, no `git add -A`. Every commit carries the `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>` trailer — the tasks' `git commit -m` blocks omit it for brevity, but the repo's recent history has it on every commit and the harness requires it.
- **Error envelope:** `ApiError` wraps the body in `error`, so assertions are `$.error.code` and `$.error.message` — never `$.code`. (`$.error.code` appears 106 times in the existing suite; `$.code` zero times.)
- **A permissive test double certifies the bug.** Several tasks here mock the very component under test for HTTP wiring; where that happens, a second non-Spring unit test drives the real logic. Do not delete those — they are the tests that fail when the logic breaks.
- **MockMvc never parses a raw `Cookie` header.** `MockHttpServletRequest.addHeader` special-cases only `Content-Type` and `Accept-Language`; everything else is stored verbatim and never reaches `getCookies()`, which is what `BuyerSessionCookie.read` and `isShadowed` actually read. Always send a cookie with `.cookie(new Cookie(BuyerSessionCookie.NAME, value))` and read it back with `response.getCookie(NAME).getValue()`. Found the hard way in Task 1, where the header form would have made the bearer-precedence test pass with no cookie present at all — green, and proving nothing.
- **Do not name a test helper's parameter `email`** in a class that has a `@MockitoBean EmailService email`: the parameter shadows the field and `verify(email, …)` stops compiling. Use `to`.
- **A `@MockitoBean` replaces the bean wholesale, so a `@TestPropertySource` cannot open a gate that lives on it.** If a controller asks a mocked service "are you configured?", the answer is Mockito's default `false` no matter what the property says, and every case 404s. Stub the gate in a `@BeforeEach`. The property is still worth keeping — it documents the production configuration — but it is documentation, not wiring.
- **Every `imin.oauth.*` key is enumerated explicitly in `application.yaml` with a `${ENV:default}` placeholder.** A new property therefore needs a line there, or Spring's relaxed binding accepts only the long `IMIN_OAUTH_…` form and the short env var this plan documents is silently inert.
- **Deploy:** `imin-api` master → Railway auto-deploy. `imin-webapp`/`imin-public` run `api:sync` against **production**, so this must be merged and deployed before any FE type regeneration.
- **Out of scope for this plan:** Ukrainian locale work (user confirmed out of scope), Apple/Google Wallet passes (separate plan — see §Scope note), event-reminder push, SMS notification column.

### Scope note

Wallet passes (`APPLE_WALLET_*` env + certs, and the net-new Google Wallet Event Ticket pass class) are a **separate plan**. They share no code, no credentials and no data model with anything here, and nothing in this plan blocks or is blocked by them. They should be planned and executed independently, in parallel.

### Push notification design (designed here, since no design existed)

The handoff has no push surface, so this plan defines the minimum honest one:

- **Push is drop-alerts only in v1.** "Notify me when tickets drop" is the one alert the design actually promises, and it already exists as an email path (`NotifySubscription` + `NotifyReleaseSender`). Push rides the same trigger. Ticket delivery stays email (the app polls); event reminders stay dark behind `IMIN_REMINDERS_ENABLED`.
- **Push requires an account.** `notify_subscriptions` is email-keyed and works for guests; a device is account-keyed. Guests keep getting email. This avoids an unauthenticated device-registration write endpoint, and gives the app a real reason to offer sign-in.
- **Delivery goes through Expo's push service** (`https://exp.host/--/api/v2/push/send`), which fans out to both APNs and FCM from one HTTP POST with one credential. The app is Expo, so its tokens are already Expo push tokens. *Ceiling:* this couples delivery to Expo's availability and their 100-messages-per-request batch limit. The upgrade path is to replace the body of `ExpoPushSender.send` with Firebase Admin + an APNs client; nothing else in the codebase knows the difference.
- **Token hygiene:** a token is unique across accounts (`UNIQUE (expo_token)`) and re-registration re-points it — the same physical device signing in as a different buyer must not keep receiving the first buyer's alerts. Tokens Expo reports as `DeviceNotRegistered` are revoked immediately. Receipt polling is deferred.
- **Opt-out:** one column, `push_drop_alerts` on `buyer_notification_preferences`, default true. The OS permission is the primary gate; this is the in-app switch the Notifications screen renders.
- **Push never gates email.** `NotifyReleaseSender.mark()` stays driven by the email send only, so a push failure can never suppress the email a buyer is owed.

---

## File Structure

**Task 1 — bearer auth**
- Modify `buyer/security/BuyerSessionAuthFilter.java` — resolve a bearer token as well as the cookie
- Create `buyer/security/BuyerClientKind.java` — the one place `X-Imin-Client: native` is read
- Modify `buyer/security/BuyerRequestGuardFilter.java` — exempt cookieless native requests
- Modify `buyer/dto/BuyerMeResponse.java` — nullable `sessionToken`
- Modify `buyer/controller/BuyerAuthController.java` — emit the token to native sign-ins
- Test `src/test/java/com/imin/iminapi/buyer/BuyerNativeSessionTest.java`

**Task 2 — checkout response discriminant**
- Modify `stripe/StripeCheckoutService.java` — return a typed result, not a bare URL
- Modify `stripe/StripeCheckoutController.java` — widen `CheckoutResponse`
- Test `src/test/java/com/imin/iminapi/stripe/CheckoutResponseShapeTest.java`

**Task 3 — PaymentIntent surface**
- Create `stripe/StripePaymentIntentService.java` — reservation + PI create, sharing the fee/promo/metadata path
- Create `stripe/StripePaymentIntentController.java` — `POST /api/v1/public/events/{eventId}/payment-intent`
- Modify `config/SecurityConfig.java` — permitAll the new endpoint
- Test `src/test/java/com/imin/iminapi/stripe/NativePaymentIntentTest.java`

**Task 4 — native Google sign-in**
- Modify `oauth/OAuthProperties.java` — `google.native-audience`
- Modify `oauth/GoogleOAuthService.java` — `verifyNativeIdToken(String)`
- Modify `buyer/controller/BuyerAuthController.java` — `POST /buyer/auth/google/native`
- Modify `config/SecurityConfig.java` — permitAll
- Test `src/test/java/com/imin/iminapi/buyer/BuyerNativeGoogleSignInTest.java`

**Task 5 — native Sign in with Apple**
- Modify `buyer/model/BuyerIdentity.java` — `PROVIDER_APPLE`
- Modify `oauth/OAuthProperties.java` — `apple.native-audience`
- Create `oauth/AppleNativeIdentityService.java` — verify Apple ID token → `OAuthUserInfo` (never reusing `AppleOAuthService`)
- Modify `buyer/controller/BuyerAuthController.java` — `POST /buyer/auth/apple/native`
- Modify `config/SecurityConfig.java` — permitAll
- Test `src/test/java/com/imin/iminapi/buyer/BuyerNativeAppleSignInTest.java`

**Task 6 — push device registry**
- Create `src/main/resources/db/migration/V92__buyer_push_devices.sql`
- Create `buyer/model/BuyerPushDevice.java`
- Create `buyer/repository/BuyerPushDeviceRepository.java`
- Create `buyer/dto/BuyerPushDeviceRequests.java`
- Create `buyer/service/BuyerPushDeviceService.java`
- Create `buyer/controller/BuyerPushDeviceController.java`
- Modify `buyer/model/BuyerNotificationPreference.java`, `buyer/dto/BuyerPreferencesResponse.java`, `buyer/service/BuyerPreferencesService.java`
- Test `src/test/java/com/imin/iminapi/buyer/BuyerPushDeviceTest.java`

**Task 7 — push delivery**
- Create `push/PushProperties.java`, `push/PushConfig.java`, `push/ExpoPushSender.java`, `push/PushMessage.java`
- Modify `service/event/NotifyReleaseSender.java` — best-effort push fan-out
- Modify `buyer/repository/BuyerPushDeviceRepository.java` — `@Transactional` on `revokeByTokens`
- Modify `service/event/NotifyReleaseSenderTest.java` — the constructor widening breaks it
- Modify `application.yaml`
- Test `push/DropAlertPushTest.java`, `push/ExpoPushSenderTest.java`, `push/DropAlertFanOutTest.java`

**Task 8 — one-way doors**
- Create `app/AppVersions.java`, `app/AppReleaseProperties.java`, `app/AppConfig.java`, `controller/publicapi/AppConfigController.java`
- Create `db/migration/V93__native_client_fields.sql`
- Modify the PaymentIntent pair for `Idempotency-Key`, three buyer list controllers for `{items, nextCursor}`, and `TrackRequest` for `client`

---

## Task 1: Bearer session lane for buyers

The credential is already an opaque 32-byte `SecureRandom` token from the shared `TokenService`; `BuyerSessionService.IssuedSession` already carries `rawToken`. It is simply never emitted outside the cookie. This task emits it to native clients and teaches the two filters to accept it.

**Why the guard exemption is safe:** `BuyerRequestGuardFilter`'s `Origin` check exists because a cookie became a credential — a browser attaches cookies to cross-site requests automatically. A browser cannot attach a custom `X-Imin-Client` header to a cross-site request without a CORS preflight, and the buyer CORS config is an exact-origin allowlist that will deny an attacker's preflight. So requiring the header *is* the CSRF defence for that lane (the OWASP custom-request-header pattern). The exemption is conditional on **no session cookie being present**, so any cookie-carrying request keeps the full guard unchanged.

**Files:**
- Create: `src/main/java/com/imin/iminapi/buyer/security/BuyerClientKind.java`
- Modify: `src/main/java/com/imin/iminapi/buyer/security/BuyerSessionAuthFilter.java`
- Modify: `src/main/java/com/imin/iminapi/buyer/security/BuyerRequestGuardFilter.java:74-101`
- Modify: `src/main/java/com/imin/iminapi/buyer/dto/BuyerMeResponse.java:22-73`
- Modify: `src/main/java/com/imin/iminapi/buyer/controller/BuyerAuthController.java:205-217`
- Test: `src/test/java/com/imin/iminapi/buyer/BuyerNativeSessionTest.java`

**Interfaces:**
- Produces: `BuyerClientKind.isNative(HttpServletRequest) : boolean`, `BuyerClientKind.HEADER = "X-Imin-Client"`, `BuyerClientKind.NATIVE = "native"`; `BuyerMeResponse.withSessionToken(String) : BuyerMeResponse`
- Consumes: `TokenService.hashOf(String)`, `BuyerSessionRepository.findByTokenHashAndRevokedAtIsNull(String)`, `BuyerSessionCookie.read(HttpServletRequest)`, `BuyerSessionService.IssuedSession.rawToken()`

---

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/imin/iminapi/buyer/BuyerNativeSessionTest.java`:

```java
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

        mvc.perform(get("/api/v1/buyer/me")
                        .header("Authorization", "Bearer " + bearerA)
                        // MockMvc does NOT parse a raw Cookie header into
                        // getCookies() — see the note below. Sending it that way
                        // would make this test pass vacuously, with no cookie for
                        // the bearer to actually beat.
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

    private static String loginBody(String to) {
        return "{\"email\":\"" + to + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    /**
     * Reads the session cookie off a sign-in response so it can be replayed with
     * {@code .cookie(...)} — the same thing the rest of the buyer suite does
     * ({@code BuyerCredentialFlowTest:172,306}, {@code BuyerOAuthAudienceTest:131}).
     *
     * <p><b>Never send it as a raw {@code Cookie} header.</b>
     * {@code MockHttpServletRequest.addHeader} special-cases only Content-Type
     * and Accept-Language; every other header, including {@code Cookie}, is
     * stored verbatim and never parsed into {@code getCookies()}. Both
     * {@code BuyerSessionCookie.read} and {@code isShadowed} read
     * {@code getCookies()}, so a raw header means "no cookie" — which would make
     * the precedence test above pass with nothing for the bearer to beat.
     */
    private static String sessionCookieValue(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(BuyerSessionCookie.NAME);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    /**
     * Signup → read the six-digit code out of the mocked mail → verify.
     *
     * <p>The parameter is {@code to}, not {@code email}: naming it {@code email}
     * shadows the {@code @MockitoBean EmailService email} field, so
     * {@code verify(email, …)} infers {@code String} and does not compile.
     */
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=BuyerNativeSessionTest`
Expected: **FAIL, 4 of 7**, all with `Status expected:<200> but was:<403>` — the Origin-less native login is rejected by `BuyerRequestGuardFilter` before a token can be minted, so the failures are on the *login status*, not on a missing `$.sessionToken`. If you see a different failure shape, stop and re-read: the guard exemption is the thing being built.

- [ ] **Step 3: Add the client-kind helper**

Create `src/main/java/com/imin/iminapi/buyer/security/BuyerClientKind.java`:

```java
package com.imin.iminapi.buyer.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one place {@code X-Imin-Client: native} is read.
 *
 * <p>A native client cannot hold an {@code HttpOnly} cookie, so it needs the raw
 * session token and a way to say so. This header is that signal, and it is
 * deliberately opt-in: a browser never sends it, so the web response shape is
 * unchanged.
 *
 * <h2>Why the header is also a CSRF defence</h2>
 *
 * <p>{@link BuyerRequestGuardFilter}'s {@code Origin} check exists because a
 * cookie became a credential — browsers attach cookies to cross-site requests
 * on their own. A browser cannot attach a <i>custom header</i> cross-site
 * without a CORS preflight, and the buyer CORS config is an exact-origin
 * allowlist that denies an attacker's preflight. So a request carrying this
 * header and <b>no session cookie</b> cannot be a cross-site forgery, which is
 * exactly the OWASP custom-request-header pattern.
 *
 * <p>The "no cookie" half is load-bearing and must not be dropped: a request
 * that carries a cookie is CSRF-able no matter what headers it also carries, so
 * it keeps the full guard.
 */
public final class BuyerClientKind {

    public static final String HEADER = "X-Imin-Client";
    public static final String NATIVE = "native";

    private BuyerClientKind() {}

    /** True when the caller declared itself native. Case-insensitive, trimmed. */
    public static boolean isNative(HttpServletRequest request) {
        String value = request.getHeader(HEADER);
        return value != null && NATIVE.equalsIgnoreCase(value.trim());
    }

    /**
     * True when this request may skip the cookie-era CSRF guard: it declared
     * itself native AND carries no session cookie.
     */
    public static boolean isCookielessNative(HttpServletRequest request) {
        return isNative(request) && BuyerSessionCookie.read(request) == null;
    }

    /**
     * True when this request may be answered with the <b>raw session token</b>
     * in the response body.
     *
     * <p>Deliberately stricter than {@link #isNative}. The buyer CORS config
     * registers {@code app.imin.wtf} with {@code allowedHeaders("*")}, so page
     * JavaScript on the buyer site can set {@code X-Imin-Client: native} on any
     * request it makes — hooking {@code fetch} on the sign-in screen would turn
     * an ordinary password login into a response carrying a portable
     * <b>180-day</b> credential, usable off-device with no cookie, no
     * {@code Origin} and no {@code SameSite} constraint. That is a real
     * escalation even though script execution on that origin is already bad
     * news, because the token outlives the page.
     *
     * <p>So emission additionally requires the absence of an {@code Origin}
     * header. Browsers set {@code Origin} on every cross-origin request and on
     * every same-origin state-changing one; a native HTTP client sets none.
     * A page therefore cannot construct a request that satisfies this, whatever
     * headers it adds.
     */
    public static boolean mayReceiveRawToken(HttpServletRequest request) {
        return isCookielessNative(request)
                && request.getHeader(org.springframework.http.HttpHeaders.ORIGIN) == null;
    }
}
```

- [ ] **Step 4: Teach the guard to exempt cookieless native requests**

In `src/main/java/com/imin/iminapi/buyer/security/BuyerRequestGuardFilter.java`, replace the body of `doFilterInternal` (lines 74-101) with:

```java
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (!BuyerSessionAuthFilter.isBuyerPath(request.getRequestURI())
                || !STATE_CHANGING.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // Native clients carry a bearer token, never the cookie. Both checks
        // below defend a cookie credential against cross-site forgery, and a
        // cookieless request has no such credential to forge. The declaration
        // itself is unforgeable cross-site — see BuyerClientKind's Javadoc.
        if (BuyerClientKind.isCookielessNative(request)) {
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || !props.getAllowedOrigins().contains(origin)) {
            log.debug("[buyer] rejected {} {} — origin {}", request.getMethod(), request.getRequestURI(), origin);
            write(response, 403, ErrorCode.FORBIDDEN,
                    "Request origin is not allowed for this endpoint");
            return;
        }

        if (!contentTypeAcceptable(request)) {
            log.debug("[buyer] rejected {} {} — content-type {}",
                    request.getMethod(), request.getRequestURI(), request.getContentType());
            write(response, 415, ErrorCode.INVALID_REQUEST,
                    "Buyer endpoints accept application/json only");
            return;
        }

        chain.doFilter(request, response);
    }
```

- [ ] **Step 5: Teach the session filter to resolve a bearer token**

In `src/main/java/com/imin/iminapi/buyer/security/BuyerSessionAuthFilter.java`, replace lines 91-110 (from the `isBuyerPath` check through the `raw == null` return) with:

```java
        if (!isBuyerPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        // Bearer first. A native client holds the raw token; the cookie is a
        // browser transport for the same opaque credential, resolved against the
        // same rows by the same hash. When a bearer token is present the cookie
        // is ignored entirely rather than used as a fallback — one request, one
        // credential, no ambiguity about which identity won.
        String raw = bearerToken(request);
        if (raw == null) {
            if (BuyerSessionCookie.isShadowed(request)) {
                // Two cookies of this name: one of them was set with Domain=imin.wtf
                // by some other host under the registrable domain. Which one
                // getCookies() returns first is not specified, so authenticate
                // nobody rather than authenticate whoever won the ordering.
                // See BuyerSessionCookie's Javadoc for why not __Host-.
                log.warn("[buyer] refused a request carrying duplicate {} cookies", BuyerSessionCookie.NAME);
                request.setAttribute("imin.authErrorCode", ErrorCode.AUTH_MISSING);
                chain.doFilter(request, response);
                return;
            }
            raw = BuyerSessionCookie.read(request);
        }
        if (raw == null) {
            chain.doFilter(request, response);
            return;
        }
```

Then add this helper beside `isBuyerPath` at the bottom of the class:

```java
    /**
     * The raw token from {@code Authorization: Bearer …}, or null.
     *
     * <p>This runs after {@link com.imin.iminapi.security.BearerTokenAuthFilter},
     * which will already have tried the same header as an organizer session and
     * as a gate token and found neither — the three token kinds live in
     * different tables, so a buyer token falls through unauthenticated and
     * reaches here. The no-op-if-already-authenticated guard at the top of
     * {@code doFilterInternal} is what keeps the two from fighting.
     */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        String raw = header.substring("Bearer ".length()).trim();
        return raw.isEmpty() ? null : raw;
    }
```

- [ ] **Step 6: Add the nullable token to the sign-in projection**

In `src/main/java/com/imin/iminapi/buyer/dto/BuyerMeResponse.java`, add a trailing component and a wither. Change the record header (line 22-41) to end with:

```java
        Instant createdAt,
        List<Address> emails,
        /**
         * The raw session token, for native clients only.
         *
         * <p>Populated <b>only</b> by the sign-in endpoints and <b>only</b> when
         * the request declared {@code X-Imin-Client: native}. A browser gets
         * null here and its {@code HttpOnly} cookie instead — handing the raw
         * token to page JavaScript would give up the whole point of the cookie.
         * {@code GET /buyer/me} never populates it.
         */
        String sessionToken) {
```

Update `of(...)` to pass `null` as the final argument, and add:

```java
    /** The same projection carrying the raw session token. Native sign-ins only. */
    public BuyerMeResponse withSessionToken(String token) {
        return new BuyerMeResponse(id, displayName, firstName, lastName, dateOfBirth,
                city, locale, status, termsAcceptedAt, deleteAt, createdAt, emails, token);
    }
```

- [ ] **Step 7: Emit the token on native sign-ins**

In `src/main/java/com/imin/iminapi/buyer/controller/BuyerAuthController.java`, change the three sign-in call sites to pass the request, and rewrite the helper:

There are exactly **two** `signedInResponse(...)` call sites — `verifyEmail` and `login`. Replace `signedInResponse(signedIn.account(), signedIn.session())` with `signedInResponse(signedIn.account(), signedIn.session(), http)` in both. `googleCallback` builds its `ResponseEntity` inline and stays untouched.

Then replace the helper:

```java
    /**
     * A signed-in response, in one of two mutually exclusive shapes.
     *
     * <p><b>Native clients get the token and NO cookie; browsers get the cookie
     * and no token.</b> The exclusivity is load-bearing in both directions:
     *
     * <ul>
     *   <li>Emitting {@code Set-Cookie} to a native client would break the CSRF
     *       exemption by construction. React Native's {@code fetch} runs on
     *       NSURLSession / OkHttp with the <b>platform cookie store enabled by
     *       default</b>, so the app would store {@code imin_buyer_session}
     *       (host-only, {@code Path=/api/v1/buyer}, {@code Secure} — every
     *       attribute satisfied in production) and replay it on every later
     *       request. {@link BuyerClientKind#isCookielessNative} would then
     *       return false, the guard would demand an {@code Origin} the app does
     *       not send, and logout, push-device registration and preference
     *       updates would all 403.</li>
     *   <li>Emitting the token to a browser would hand page JavaScript a
     *       portable 180-day credential — see
     *       {@link BuyerClientKind#mayReceiveRawToken}.</li>
     * </ul>
     */
    private ResponseEntity<BuyerMeResponse> signedInResponse(BuyerAccount account,
                                                             BuyerSessionService.IssuedSession session,
                                                             HttpServletRequest http) {
        BuyerMeResponse me = body(account);
        var response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE);
        if (BuyerClientKind.mayReceiveRawToken(http)) {
            return response.body(me.withSessionToken(session.rawToken()));
        }
        return response
                .header(HttpHeaders.SET_COOKIE, session.cookie().toString())
                .body(me);
    }
```

Add the import `com.imin.iminapi.buyer.security.BuyerClientKind`.

**Do NOT change `googleCallback`.** The web Google callback has no native caller — Task 4 gives native Google its own endpoint — so emitting a token there would be pure attack surface. Leave it exactly as it is.

Also update `logout` so a bearer-authenticated native client can revoke its own token:

```java
    @PostMapping("/api/v1/buyer/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        // Bearer first, cookie second — the same precedence the auth filter uses,
        // so logout always revokes the credential the caller actually presented.
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String raw = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim()
                : BuyerSessionCookie.read(request);
        sessions.revokeByRawToken(raw);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessions.clearCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .build();
    }
```

- [ ] **Step 8: Run the tests**

Run: `./mvnw test -Dtest=BuyerNativeSessionTest`
Expected: PASS (7 tests)

- [ ] **Step 9: Run the buyer suite to prove nothing regressed**

Run: `./mvnw test -Dtest='Buyer*'`
Expected: PASS. Every existing buyer test drives the cookie path with an `Origin` header, and neither is altered.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/imin/iminapi/buyer/security/BuyerClientKind.java \
        src/main/java/com/imin/iminapi/buyer/security/BuyerSessionAuthFilter.java \
        src/main/java/com/imin/iminapi/buyer/security/BuyerRequestGuardFilter.java \
        src/main/java/com/imin/iminapi/buyer/dto/BuyerMeResponse.java \
        src/main/java/com/imin/iminapi/buyer/controller/BuyerAuthController.java \
        src/test/java/com/imin/iminapi/buyer/BuyerNativeSessionTest.java
git commit -m "feat(buyer): bearer session lane for native clients"
```

---

## Task 2: Checkout response discriminant

`POST /public/events/{id}/checkout` returns `{url}` for both a Stripe hosted session and the free/zero-net order page, so a client has to string-parse a URL to pick control flow. This adds an explicit `kind` and the `sessionId` a client needs to poll `GET /public/checkout/{sessionId}`. Cheap, additive, and it unblocks both the app and any future web change.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/stripe/StripeCheckoutService.java:100-431`
- Modify: `src/main/java/com/imin/iminapi/stripe/StripeCheckoutController.java:32-59,104`
- Test: `src/test/java/com/imin/iminapi/stripe/CheckoutResponseShapeTest.java`

**Interfaces:**
- Produces: `StripeCheckoutService.CheckoutResult(String kind, String url, String sessionId, String orderToken)` with `kind` ∈ `{"stripe", "order"}`; `StripeCheckoutService.createCheckout(...)` returning it. `StripeCheckoutController.CheckoutResponse(String url, String kind, String sessionId, String orderToken)`.
- Consumes: nothing new.

---

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/imin/iminapi/stripe/CheckoutResponseShapeTest.java`:

```java
package com.imin.iminapi.stripe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checkout response discriminant. A client must never have to parse a URL
 * to learn whether it is holding a Stripe session or a finished free order.
 */
class CheckoutResponseShapeTest {

    @Test
    void stripeResultCarriesTheSessionId() {
        var r = StripeCheckoutService.CheckoutResult.stripe(
                "https://checkout.stripe.com/c/pay/cs_test_123", "cs_test_123");
        assertThat(r.kind()).isEqualTo("stripe");
        assertThat(r.sessionId()).isEqualTo("cs_test_123");
        assertThat(r.url()).startsWith("https://checkout.stripe.com/");
    }

    @Test
    void freeOrderResultCarriesTheOrderTokenAndNoSessionId() {
        var r = StripeCheckoutService.CheckoutResult.order(
                "https://app.imin.wtf/order/tok_abc", "tok_abc");
        assertThat(r.kind()).isEqualTo("order");
        assertThat(r.sessionId()).isNull();
        // The app must never have to slice this out of the URL.
        assertThat(r.orderToken()).isEqualTo("tok_abc");
    }

    @Test
    void stripeResultHasNoOrderToken() {
        var r = StripeCheckoutService.CheckoutResult.stripe(
                "https://checkout.stripe.com/c/pay/cs_test_123", "cs_test_123");
        assertThat(r.orderToken()).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=CheckoutResponseShapeTest`
Expected: FAIL — compilation error, `CheckoutResult` does not exist.

- [ ] **Step 3: Add the result type and the new entry point**

In `src/main/java/com/imin/iminapi/stripe/StripeCheckoutService.java`, add the record just below the class declaration (after line 98):

```java
    /**
     * What a checkout call produced. {@code kind} exists because the two
     * branches return structurally different things through what used to be one
     * {@code url} string: a Stripe-hosted session the buyer must be redirected
     * to, or a finished free order's page. Clients switched on the URL's shape;
     * now they switch on this.
     *
     * @param sessionId the Stripe Checkout Session id, or null on the free path.
     *                  Clients poll {@code GET /api/v1/public/checkout/{sessionId}}
     *                  with it to learn when issuance finished.
     */
    public record CheckoutResult(String kind, String url, String sessionId, String orderToken) {

        public static final String KIND_STRIPE = "stripe";
        public static final String KIND_ORDER = "order";

        public static CheckoutResult stripe(String url, String sessionId) {
            return new CheckoutResult(KIND_STRIPE, url, sessionId, null);
        }

        /**
         * The free path finishes the purchase outright. {@code orderToken} is
         * the same value embedded in {@code url} — returned as a field so a
         * client does not have to slice it out of a web URL it should not be
         * parsing. Two of the six events in the design handoff are free, so
         * this is a real app path, not an edge case.
         */
        public static CheckoutResult order(String url, String orderToken) {
            return new CheckoutResult(KIND_ORDER, url, null, orderToken);
        }
    }
```

- [ ] **Step 4: Rename the widest overload and keep the old signature as a shim**

In the same file, rename the **10-argument** `createCheckoutSession` (line 152) to `createCheckout` and change its return type to `CheckoutResult`. Note there are **five** existing overloads (at lines 108, 113, 119, 126 and 134), all of which delegate to it. Inside it:

- change the free-path return (line 220) from `return freeCheckoutService.orderUrl(order);` to `return CheckoutResult.order(freeCheckoutService.orderUrl(order), order.getToken());`
- change the final return (line 430) from `return session.getUrl();` to `return CheckoutResult.stripe(session.getUrl(), session.getId());`

Then add the back-compat shim so the five existing overloads keep working unchanged:

```java
    /**
     * Pre-discriminant form. Kept because the five thin overloads above and
     * their callers expect a bare URL; new callers should use
     * {@link #createCheckout}.
     */
    public String createCheckoutSession(UUID eventId, UUID tierId, int quantity,
                                         String promoCode, Integer expectedPriceMinor, String buyerEmail,
                                         boolean adsConsent, boolean marketingOptIn,
                                         CheckoutAttribution attribution, String rawLocale) {
        return createCheckout(eventId, tierId, quantity, promoCode, expectedPriceMinor, buyerEmail,
                adsConsent, marketingOptIn, attribution, rawLocale).url();
    }
```

- [ ] **Step 5: Widen the controller response**

In `src/main/java/com/imin/iminapi/stripe/StripeCheckoutController.java`, replace the `createCheckoutSession` call and the response record:

```java
        StripeCheckoutService.CheckoutResult result = checkout.createCheckout(eventId, body.tierId(), quantity,
                promoCode, body.expectedPriceMinor(), body.email(), adsConsent, marketingOptIn,
                attribution, body.locale());
        return new CheckoutResponse(result.url(), result.kind(), result.sessionId(), result.orderToken());
```

```java
    /**
     * {@code url} is unchanged and still first — imin-public reads only that.
     * {@code kind} is {@code "stripe"} or {@code "order"}; {@code sessionId} is
     * present only for {@code "stripe"}.
     */
    public record CheckoutResponse(String url, String kind, String sessionId, String orderToken) {}
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw test -Dtest=CheckoutResponseShapeTest`
Expected: PASS (3 tests)

- [ ] **Step 7: Run the checkout suite**

Run: `./mvnw test -Dtest='*Checkout*,*Stripe*'`
Expected: PASS. `url` is still the first field and still the same value, so any existing assertion on it holds.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/imin/iminapi/stripe/StripeCheckoutService.java \
        src/main/java/com/imin/iminapi/stripe/StripeCheckoutController.java \
        src/test/java/com/imin/iminapi/stripe/CheckoutResponseShapeTest.java
git commit -m "feat(checkout): discriminate stripe vs free-order results and expose sessionId"
```

---

## Task 3: PaymentIntent surface for native Apple Pay / Google Pay

The native Stripe PaymentSheet binds to a PaymentIntent `client_secret`. There is no PaymentIntent on the buyer surface today — hosted Checkout creates one internally and we never see it. This creates one directly, reusing the **exact** validation, eligibility, promo, inventory-reservation, fee and metadata path the hosted flow uses, so the two cannot drift on money or inventory.

**Critical invariant:** the metadata map must be identical to the hosted path's, because `payment_intent.succeeded` is what drives fulfilment and `PaidCheckoutService` reads `reservation_id` / `tier_id` / `qty` / `event_id` / `promo_id` / `ads_consent` / `marketing_opt_in` / `utm_*` / `buyer_locale` off the PI. A missing key means a paid buyer with no ticket.

**Two facts about fulfilment that metadata parity alone does not cover, and that a native PaymentIntent breaks. Both are money defects; read this before writing any code.**

1. **The buyer's email does not travel in metadata on the hosted path — it travels on the Checkout Session.** `StripeCheckoutService:383-385` calls `setCustomerEmail`, and `PaidCheckoutService.resolveBuyerAndSession:205-245` recovers it from exactly two places: `charge.billing_details.email`, and *the Checkout Session listed by PaymentIntent id*. A natively-created PI **has no Session**, so that second source is structurally empty, and the PaymentSheet does not populate `billing_details.email` by default. When both come up empty, `PaidCheckoutService:130` throws and **every Stripe retry fails identically — the buyer is charged and never gets a ticket.** Setting `receipt_email` does not help: `grep -rn "receipt_email" src/main/java` returns zero readers. Step 1 therefore adds `buyer_email` to the shared metadata map, and Step 6 teaches the resolver to read it.

2. **The order is found by Session id, and a native order has none.** `CheckoutStatusService.statusFor:32-38` contains a single resolver, `orders.findByStripeSessionId(sessionId)`; `PaidCheckoutService:146` sets that column from the resolved session, which is null natively. So after a successful PaymentSheet the app holds only a `pi_…` id and **cannot obtain the order token** — the Success screen dead-ends, and guest checkout is the headline flow. `OrderRepository:21` already declares `findByStripePaymentIntentId` (used by the dedup path and the reconciler, never by the status service). Step 7 wires it up.

**Deliberate scope limit:** the free/zero-net path is **not** exposed here. A €0 total has nothing for a PaymentSheet to charge; the app calls the existing `/checkout` endpoint for that and reads `kind: "order"` from Task 2.

**Files:**
- Create: `src/main/java/com/imin/iminapi/stripe/StripePaymentIntentService.java`
- Create: `src/main/java/com/imin/iminapi/stripe/StripePaymentIntentController.java`
- Modify: `src/main/java/com/imin/iminapi/stripe/StripeCheckoutService.java` — split out `priceIt` + `reserveAndBuildMetadata`
- Modify: `src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java:205-231` — **blocker fix**, buyer email
- Modify: `src/main/java/com/imin/iminapi/service/ticket/CheckoutStatusService.java:32-38` — **blocker fix**, PI lookup
- Modify: `src/main/java/com/imin/iminapi/service/event/ReservationSweeper.java` — cancel released native intents
- Modify: `src/main/java/com/imin/iminapi/config/SecurityConfig.java:129`
- Test: `src/test/java/com/imin/iminapi/stripe/NativePaymentIntentTest.java`
- Test: `src/test/java/com/imin/iminapi/service/ticket/PaidCheckoutServiceTest.java` — add the native-metadata case
- Test: the `CheckoutStatusService` test (locate with `grep -rln CheckoutStatusService src/test/java`)
- Test: the `ReservationSweeper` test — add the cancel cases
- Doc: `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md` — the widened `/public/checkout/{id}` contract

**Interfaces:**
- Consumes: `StripeCheckoutService.CheckoutResult` (Task 2); `InventoryService.reserve(UUID, int, Instant, String)`, `InventoryService.releaseReservation(UUID, String)`, `InventoryService.attachSessionId(UUID, String)`; `QuoteService.computeFee(long, int, int, int)`; `PublicTierEligibility.loadBuyableTier`, `.assertExpectedPriceMatches`; `StripeConnectService.getStatusLive(UUID)`.
- Produces:
  - `StripeCheckoutService.Priced` and `StripeCheckoutService.PaidPrelude` — the two shared records, fields as declared in Step 1
  - `StripeCheckoutService.priceIt(UUID eventId, UUID tierId, int quantity, String promoCode, Integer expectedPriceMinor) : Priced` — side-effect free
  - `StripeCheckoutService.reserveAndBuildMetadata(Priced priced, UUID eventId, UUID tierId, int quantity, String buyerEmail, boolean adsConsent, boolean marketingOptIn, CheckoutAttribution attribution, String rawLocale, boolean nativeClient) : PaidPrelude`
  - `StripePaymentIntentService.NativeIntent(String clientSecret, String paymentIntentId, long amountMinor, long feeMinor, String currency)` — exactly five components, no publishable key (the app holds that as build config, and minting it server-side would imply it is per-request when it is not)
  - Modified: `CheckoutStatusService.statusFor(String id)` now resolves `pi_`-prefixed ids too
  - Modified: `PaidCheckoutService.resolveBuyerAndSession` gains the `buyer_email` metadata fallback

---

- [ ] **Step 1: Split the shared prelude in two, in StripeCheckoutService**

Before writing anything new, pull the validate-and-reserve work out of `createCheckout` so both flows provably share it.

**Split it into two methods, not one.** A single `preparePaid` cannot express "reject a free total on the native path" correctly: a free tier hits `notFound("Event")` at :223 (blank `stripePriceId`) or :231-239 (the connected-account readiness gate) *before* any total is known, so a native caller would get 404 rather than a useful 400 — and if it did reach the reserve, the guard would be cleaning up a hold it should never have taken. So:

- **`priceIt(...)` — side-effect free.** Loads and validates the event and tier, asserts expected price, resolves the promo, computes `subtotalMinor` / `discountMinor` / `netTotalMinor`. Touches nothing. This is current lines 156-192.
- **`reserveAndBuildMetadata(...)` — takes a `Priced` and does the rest.** The Stripe-price check, org load, readiness gate, the inventory reservation, the fee computation and the metadata map. This is current lines 223-276 plus 302-340, **minus** `createOneShotCoupon`.

Do not describe the boundary by line number when you make the edit — describe it by content, because the ranges are not contiguous and 279-300 (the hosted `SessionCreateParams` line items) sits in the middle of them and must **stay** in `createCheckout`, along with the free branch (194-221) and everything from 342 down.

In `StripeCheckoutService.java` add:

```java
    /**
     * Everything both the hosted and the native flow must agree on: a buyable
     * tier, a resolved promo, a ready connected account, a held reservation and
     * the metadata map the fulfilment webhook reads.
     *
     * <p>Extracted so the two flows cannot drift. If you add a metadata key,
     * add it here — {@code PaidCheckoutService} reads them off the PaymentIntent
     * either way, and a key present on only one path is a paid buyer with no
     * ticket.
     */
    /** Prices a request without touching anything. No reservation, no Stripe, no org gate. */
    public record Priced(Event event, TicketTier tier, PromoCode promo,
                         long subtotalMinor, long discountMinor, long netTotalMinor) {}

    public record PaidPrelude(Event event, TicketTier tier, Organization org, PromoCode promo,
                              UUID reservationId, Instant expiresAt, long subtotalMinor,
                              long discountMinor, long netTotalMinor, long applicationFee,
                              String currency, Map<String, String> metadata) {}
```

Extract the two methods as described above and call them in sequence from `createCheckout`. **Do not change any of that logic** — this is a pure move. `couponId` creation stays in `createCheckout`, because a one-shot Stripe Coupon is a hosted-Checkout concept; the native path applies the discount to the PI amount directly (Step 4).

**One additive change while you are in the metadata map** — add these two entries, which are harmless on the hosted path and load-bearing on the native one:

```java
        // The hosted path carries the buyer address on the Checkout Session
        // (setCustomerEmail, :383-385) and PaidCheckoutService recovers it by
        // listing the Session for the PaymentIntent. A native PI has no Session,
        // so without this the address is unrecoverable and fulfilment throws on
        // every webhook retry — a charged buyer with no ticket.
        if (buyerEmail != null && !buyerEmail.isBlank()) {
            metadata.put("buyer_email", buyerEmail.trim());
        }
        // Lets the fulfilment path skip a Stripe round trip it knows will be
        // empty, and lets analytics tell app orders from web ones.
        metadata.put("client", nativeClient ? "native" : "web");
```

Add a `boolean nativeClient` parameter to `reserveAndBuildMetadata` — `false` from `createCheckout`, `true` from the native service.

- [ ] **Step 2: Write the failing test**

This is a **plain Mockito test, not a Spring test.** `EventFixtures` and `StripeTestDoubles` do not exist, `OrderFixtures` is static-only and builds events with no tiers, and providing a `StripeClient` `@Bean` collides with `StripeConfig:26-27` (`spring.main.allow-bean-definition-overriding` is set nowhere). Mirror `StripeCheckoutServiceTest` instead — read it first; it is the pattern for this exact service.

**What these tests are really for.** Asserting the JSON response proves almost nothing: dropping `.putAllMetadata(...)`, `.setApplicationFeeAmount(...)`, `.setTransferData(...)` or `.setTransferGroup(...)` would leave a response-only suite entirely green while every native buyer's payment goes unfulfilled or untransferred. So the load-bearing assertions capture **what is handed to Stripe**, exactly as the hosted suite does at `StripeCheckoutServiceTest:170-265`.

Create `src/test/java/com/imin/iminapi/stripe/NativePaymentIntentTest.java`:

```java
package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.security.ApiException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The native PaymentIntent surface, tested at the boundary that matters: what
 * this service hands to Stripe, and what it does to inventory when Stripe fails.
 *
 * <p>The response fields are the least interesting assertions here. The money
 * invariants — fee computed on the UNDISCOUNTED subtotal, the full fulfilment
 * metadata attached to the PaymentIntent, the destination and transfer group —
 * are only visible in the captured {@link PaymentIntentCreateParams}.
 */
class NativePaymentIntentTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID TIER = UUID.randomUUID();
    private static final UUID RESERVATION = UUID.randomUUID();

    private com.stripe.StripeClient stripeClient;
    private com.stripe.service.PaymentIntentService paymentIntents;
    private StripeCheckoutService checkoutService;
    private com.imin.iminapi.service.event.InventoryService inventory;
    private StripePaymentIntentService service;

    @BeforeEach
    void setUp() throws Exception {
        stripeClient = mock(com.stripe.StripeClient.class);
        paymentIntents = mock(com.stripe.service.PaymentIntentService.class);
        when(stripeClient.paymentIntents()).thenReturn(paymentIntents);

        PaymentIntent created = new PaymentIntent();
        created.setId("pi_test_123");
        created.setClientSecret("pi_test_123_secret_abc");
        when(paymentIntents.create(any(PaymentIntentCreateParams.class))).thenReturn(created);

        checkoutService = mock(StripeCheckoutService.class);
        inventory = mock(com.imin.iminapi.service.event.InventoryService.class);
        service = new StripePaymentIntentService(stripeClient, checkoutService, inventory);
    }

    @Test
    void chargesSubtotalPlusFeeAndAttachesEverythingFulfilmentNeeds() throws Exception {
        // 2 x EUR 25.00, no promo. Fee = 5% of 5000 + 99 per ticket = 250 + 198 = 448.
        stubPrelude(5000L, 0L, 5000L, 448L, null);

        var intent = service.create(EVENT, TIER, 2, null, null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en");

        assertThat(intent.clientSecret()).isEqualTo("pi_test_123_secret_abc");
        assertThat(intent.paymentIntentId()).isEqualTo("pi_test_123");
        assertThat(intent.amountMinor()).isEqualTo(5448L);
        assertThat(intent.feeMinor()).isEqualTo(448L);
        assertThat(intent.currency()).isEqualTo("eur");

        ArgumentCaptor<PaymentIntentCreateParams> captor =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        verify(paymentIntents).create(captor.capture());
        PaymentIntentCreateParams sent = captor.getValue();

        assertThat(sent.getAmount()).isEqualTo(5448L);
        assertThat(sent.getApplicationFeeAmount()).isEqualTo(448L);
        assertThat(sent.getTransferData().getDestination()).isEqualTo("acct_test_org");
        assertThat(sent.getTransferGroup()).isEqualTo(EVENT.toString());

        // Everything PaidCheckoutService reads off the PI. A missing key here is
        // a charged buyer with no ticket, so assert each one by name.
        assertThat(sent.getMetadata())
                .containsEntry("reservation_id", RESERVATION.toString())
                .containsEntry("tier_id", TIER.toString())
                .containsEntry("qty", "2")
                .containsEntry("event_id", EVENT.toString())
                .containsEntry("ads_consent", "false")
                .containsEntry("marketing_opt_in", "false")
                .containsEntry("buyer_locale", "en")
                // The native-only additions. Without buyer_email the address is
                // unrecoverable at fulfilment: there is no Checkout Session to
                // list, and nothing reads receipt_email.
                .containsEntry("buyer_email", "buyer@example.test")
                .containsEntry("client", "native");
    }

    /**
     * The one place native and hosted money math genuinely diverge. The hosted
     * path expresses a discount as a Stripe Coupon scoped to the ticket product;
     * the native path subtracts it from the amount — and the fee must STILL be
     * computed on the undiscounted subtotal, or a promo silently shrinks the
     * platform's cut.
     */
    @Test
    void aPromoDiscountsTheTicketsAndNeverTheFee() throws Exception {
        // 2 x EUR 25.00 with 20% off. Discount 1000, net 4000. Fee still on 5000.
        stubPrelude(5000L, 1000L, 4000L, 448L, "promo-abc");

        var intent = service.create(EVENT, TIER, 2, "VECHIRKA20", null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en");

        assertThat(intent.amountMinor()).isEqualTo(4448L);
        // Identical to the no-promo case above. This is the assertion that fails
        // if someone writes computeFee(netTotal, ...).
        assertThat(intent.feeMinor()).isEqualTo(448L);

        ArgumentCaptor<PaymentIntentCreateParams> captor =
                ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        verify(paymentIntents).create(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualTo(4448L);
        assertThat(captor.getValue().getApplicationFeeAmount()).isEqualTo(448L);
        assertThat(captor.getValue().getMetadata()).containsEntry("promo_id", "promo-abc");
    }

    @Test
    void aFreeTotalIsRejectedBeforeAnythingIsReserved() {
        when(checkoutService.priceIt(EVENT, TIER, 1, null, null))
                .thenReturn(new StripeCheckoutService.Priced(null, null, null, 0L, 0L, 0L));

        assertThatThrownBy(() -> service.create(EVENT, TIER, 1, null, null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("/checkout");

        // The point of pricing before reserving: no hold was ever taken, so
        // there is nothing to release.
        verify(checkoutService, never()).reserveAndBuildMetadata(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(inventory, never()).releaseReservation(any(), any());
    }

    @Test
    void aStripeFailureReturnsTheSeatsToThePool() throws Exception {
        stubPrelude(5000L, 0L, 5000L, 448L, null);
        when(paymentIntents.create(any(PaymentIntentCreateParams.class)))
                .thenThrow(new com.stripe.exception.ApiException("boom", null, null, 500, null));

        assertThatThrownBy(() -> service.create(EVENT, TIER, 2, null, null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en"))
                .isInstanceOf(ApiException.class);

        verify(inventory).releaseReservation(RESERVATION, "STRIPE_PI_CREATE_FAILED");
    }

    /** Stamps the PI id on the hold so the sweeper can cancel it later (Step 8). */
    @Test
    void stampsThePaymentIntentIdOntoTheReservation() throws Exception {
        stubPrelude(5000L, 0L, 5000L, 448L, null);

        service.create(EVENT, TIER, 2, null, null, "buyer@example.test",
                false, false, CheckoutAttribution.NONE, "en");

        verify(inventory).attachSessionId(RESERVATION, "pi_test_123");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubPrelude(long subtotal, long discount, long net, long fee, String promoId) {
        var priced = new StripeCheckoutService.Priced(null, null, null, subtotal, discount, net);
        when(checkoutService.priceIt(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenReturn(priced);

        var org = new com.imin.iminapi.model.Organization();
        org.setStripeAccountId("acct_test_org");

        var metadata = new java.util.HashMap<String, String>();
        metadata.put("reservation_id", RESERVATION.toString());
        metadata.put("tier_id", TIER.toString());
        metadata.put("qty", "2");
        metadata.put("event_id", EVENT.toString());
        metadata.put("ads_consent", "false");
        metadata.put("marketing_opt_in", "false");
        metadata.put("buyer_locale", "en");
        metadata.put("buyer_email", "buyer@example.test");
        metadata.put("client", "native");
        if (promoId != null) metadata.put("promo_id", promoId);

        when(checkoutService.reserveAndBuildMetadata(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new StripeCheckoutService.PaidPrelude(
                        null, null, org, null, RESERVATION, java.time.Instant.now(),
                        subtotal, discount, net, fee, "eur", metadata));
    }
}
```

> **Fee arithmetic, verified.** `src/test/resources/application.yaml:53-54` pins `application-fee-bps: 500` and `application-fee-fixed-minor: 99`, and `QuoteService.computeFee` is `(long subtotalMinor, int quantity, int bps, int fixedMinor)` — **four ints, not `(long, int, int, long)`**. 5% of 5000 = 250, plus 99 × 2 = 198, total 448. Confirm both config values before trusting the numbers above.

> **Also add an HTTP-level test** — a small `@SpringBootTest @AutoConfigureMockMvc` class with `@MockitoBean StripePaymentIntentService` — asserting only the wiring: 200 with the JSON shape, `Cache-Control: private, no-store`, a missing `tierId` giving 400, and `quantity: 11` giving 400 (which proves `@Valid` is actually on the parameter — see Step 5). Do not re-test the money there.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=NativePaymentIntentTest`
Expected: FAIL — 404 on every case, the endpoint does not exist.

- [ ] **Step 4: Write the service**

Create `src/main/java/com/imin/iminapi/stripe/StripePaymentIntentService.java`:

```java
package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Creates a PaymentIntent for the <b>native</b> Stripe PaymentSheet, which is
 * what makes real Apple Pay and Google Pay possible in the mobile app. The
 * hosted Checkout flow in {@link StripeCheckoutService} is unchanged and remains
 * what the web uses.
 *
 * <h2>Why this shares a prelude with the hosted flow</h2>
 *
 * <p>Both flows must agree on which tiers are buyable, what a ticket costs, what
 * the platform fee is, that inventory is held before money is asked for, and
 * which metadata the {@code payment_intent.succeeded} handler will read. Those
 * are the money and inventory invariants; two implementations of them is two
 * chances to be wrong. So everything up to "we have a held reservation and a
 * metadata map" comes from {@link StripeCheckoutService.PaidPrelude}, and this
 * class only differs in what it hands Stripe.
 *
 * <h2>The two real differences</h2>
 *
 * <ol>
 *   <li><b>Discounts are applied to the amount, not attached as a Coupon.</b> A
 *       one-shot Stripe Coupon is a hosted-Checkout construct; a PaymentIntent
 *       has a single amount. So the promo discount is subtracted here and the
 *       resulting amount is charged. The fee is still computed on the
 *       <i>undiscounted</i> subtotal, exactly as the hosted path does, so a
 *       promo never shrinks the platform's cut.</li>
 *   <li><b>There is no free path.</b> A zero total has nothing for a payment
 *       sheet to charge. Callers get 400 and are pointed at
 *       {@code POST /public/events/{id}/checkout}, which returns
 *       {@code kind: "order"} for that case.</li>
 * </ol>
 *
 * <p>Deliberately NOT {@code @Transactional}, for the same reason
 * {@link StripeCheckoutService#createCheckout} is not: the reservation takes a
 * pessimistic row lock, and holding it across a blocking Stripe HTTP call would
 * serialize every concurrent buyer of a hot tier behind remote I/O.
 */
@Service
public class StripePaymentIntentService {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentIntentService.class);

    private final StripeClient stripeClient;
    private final StripeCheckoutService checkoutService;
    private final com.imin.iminapi.service.event.InventoryService inventoryService;

    public StripePaymentIntentService(StripeClient stripeClient,
                                      StripeCheckoutService checkoutService,
                                      com.imin.iminapi.service.event.InventoryService inventoryService) {
        this.stripeClient = stripeClient;
        this.checkoutService = checkoutService;
        this.inventoryService = inventoryService;
    }

    /**
     * @param clientSecret    what the native PaymentSheet binds to. Short-lived,
     *                        scoped to this one intent; safe to hand the client.
     * @param amountMinor     what the buyer will be charged, net of any promo.
     * @param feeMinor        the buyer-visible service fee inside {@code amountMinor}.
     *                        The app must render this from here and never recompute it.
     */
    public record NativeIntent(String clientSecret, String paymentIntentId,
                               long amountMinor, long feeMinor, String currency) {}

    public NativeIntent create(UUID eventId, UUID tierId, int quantity, String promoCode,
                               Integer expectedPriceMinor, String buyerEmail,
                               boolean adsConsent, boolean marketingOptIn,
                               CheckoutAttribution attribution, String rawLocale) {

        // Price first, and reject a free total BEFORE anything is reserved and
        // before the connected-account gate runs. Reversing these two would 404
        // a free tier (blank stripePriceId at :223) instead of explaining
        // itself, and would take a hold it then had to clean up.
        StripeCheckoutService.Priced priced = checkoutService.priceIt(
                eventId, tierId, quantity, promoCode, expectedPriceMinor);

        if (priced.netTotalMinor() == 0L) {
            // A zero total has nothing for a payment sheet to charge. The hosted
            // endpoint owns that branch and issues the order outright; sending
            // the app around in a circle would be worse than saying so.
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "This ticket is free — use POST /api/v1/public/events/{eventId}/checkout");
        }

        StripeCheckoutService.PaidPrelude p = checkoutService.reserveAndBuildMetadata(
                priced, eventId, tierId, quantity, buyerEmail,
                adsConsent, marketingOptIn, attribution, rawLocale, true);

        // Charge the discounted ticket total plus the undiscounted fee — the same
        // arithmetic the hosted session performs across its two line items.
        long amount = p.netTotalMinor() + p.applicationFee();

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(amount)
                .setCurrency(p.currency())
                .setApplicationFeeAmount(p.applicationFee())
                .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                        .setDestination(p.org().getStripeAccountId())
                        .build())
                // Same grouping the hosted path uses, so per-event reconciliation
                // sees native and web payments in one bucket.
                .setTransferGroup(eventId.toString())
                .putAllMetadata(p.metadata())
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build());

        if (buyerEmail != null && !buyerEmail.isBlank()) {
            builder.setReceiptEmail(buyerEmail.trim());
        }

        PaymentIntent intent;
        try {
            intent = stripeClient.paymentIntents().create(builder.build());
        } catch (StripeException e) {
            // Same rollback contract as the hosted path: we promised the buyer
            // nothing, so the seats go back to the pool.
            try {
                inventoryService.releaseReservation(p.reservationId(), "STRIPE_PI_CREATE_FAILED");
            } catch (Exception releaseFailure) {
                log.error("Failed to release reservation {} after PaymentIntent create failure: {}",
                        p.reservationId(), releaseFailure.getMessage(), releaseFailure);
            }
            log.error("Stripe PaymentIntent create failed (event {}, tier {}, reservation {}): {}",
                    eventId, tierId, p.reservationId(), e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Payment could not be started", e);
        }

        try {
            inventoryService.attachSessionId(p.reservationId(), intent.getId());
        } catch (Exception e) {
            log.warn("Failed to attach payment intent {} to reservation {}: {}",
                    intent.getId(), p.reservationId(), e.getMessage());
        }

        return new NativeIntent(intent.getClientSecret(), intent.getId(),
                amount, p.applicationFee(), p.currency());
    }
}
```

> `priceIt(...)` and `reserveAndBuildMetadata(...)` are the two package-visible extractions from Step 1. Use exactly those names and the signatures listed under **Interfaces** above — the tests in Step 2 stub both by name.

- [ ] **Step 5: Write the controller**

Create `src/main/java/com/imin/iminapi/stripe/StripePaymentIntentController.java`:

```java
package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public (unauthenticated) native-checkout endpoint. Returns a PaymentIntent
 * client secret for the Stripe native PaymentSheet — the only way to get a real
 * Apple Pay / Google Pay sheet rather than a browser redirect.
 *
 * <p>Unauthenticated on purpose, exactly like the hosted {@code /checkout}
 * sibling: buying a ticket does not require an account, and requiring one here
 * would make the native flow stricter than the web for no security gain. It
 * carries the same per-IP rate limit, which is what stops a loop minting
 * unbounded intents and holding real inventory for 30-minute windows.
 */
@RestController
@RequestMapping("/api/v1/public/events")
public class StripePaymentIntentController {

    private final StripePaymentIntentService intents;
    private final RateLimiter rateLimiter;

    public StripePaymentIntentController(StripePaymentIntentService intents, RateLimiter rateLimiter) {
        this.intents = intents;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/{eventId}/payment-intent")
    public ResponseEntity<StripePaymentIntentService.NativeIntent> create(
            @PathVariable UUID eventId,
            // @Valid is required, not decorative: Spring only cascades bean
            // validation into a @RequestBody when the parameter carries it, so
            // without this the @NotNull/@Min/@Max on PaymentIntentRequest below
            // never run and a quantity of 500 reaches the service.
            @Valid @RequestBody PaymentIntentRequest body,
            HttpServletRequest http) {
        // Shares the "checkout" bucket with the hosted endpoint deliberately: the
        // two are the same scarce operation (a Stripe object plus a real
        // inventory hold), so they must share one budget or the limit is bypassable
        // by alternating between them.
        rateLimiter.consume("checkout", "ip:" + http.getRemoteAddr());
        if (body == null || body.tierId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "tierId is required");
        }
        int quantity = body.quantity() == null ? 1 : body.quantity();
        CheckoutAttribution attribution = new CheckoutAttribution(
                body.utmSource(), body.utmMedium(), body.utmCampaign(), body.anonId());

        StripePaymentIntentService.NativeIntent intent = intents.create(
                eventId, body.tierId(), quantity, body.promoCode(), body.expectedPriceMinor(),
                body.email(), Boolean.TRUE.equals(body.adsConsent()),
                Boolean.TRUE.equals(body.marketingOptIn()), attribution, body.locale());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(intent);
    }

    /** Mirrors {@code StripeCheckoutController.CheckoutRequest} field-for-field. */
    public record PaymentIntentRequest(@NotNull UUID tierId,
                                        @Min(1) @Max(10) Integer quantity,
                                        String promoCode,
                                        Integer expectedPriceMinor,
                                        String email,
                                        Boolean adsConsent,
                                        Boolean marketingOptIn,
                                        String utmSource,
                                        String utmMedium,
                                        String utmCampaign,
                                        String anonId,
                                        String locale) {}
}
```

- [ ] **Step 6: Make the buyer's email recoverable at fulfilment (BLOCKER)**

Without this, every native purchase is charged and never fulfilled. See the two facts at the top of this task.

In `src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java`, change `resolveBuyerAndSession` (currently :205-231) to consult metadata first and skip the Session lookup entirely when the PI was created natively:

```java
    /** One Stripe lookup that returns both the buyer email and the session id. */
    private Resolved resolveBuyerAndSession(PaymentIntent pi) {
        Map<String, String> meta = pi.getMetadata() == null ? Map.of() : pi.getMetadata();

        // A natively-created PaymentIntent has NO Checkout Session, so listing
        // sessions for it is a guaranteed-empty round trip and the address can
        // only come from metadata. StripePaymentIntentService stamps both keys.
        if ("native".equals(meta.get("client"))) {
            String fromMeta = trimToNull(meta.get("buyer_email"));
            String email = fromMeta != null ? fromMeta : readChargeEmail(pi);
            return new Resolved(email, null);
        }

        String email = readChargeEmail(pi);
        String sessionId = null;
        try {
            var coll = stripeClient.checkout().sessions().list(
                    SessionListParams.builder()
                            .setPaymentIntent(pi.getId())
                            .setLimit(1L)
                            .build());
            if (coll != null && coll.getData() != null && !coll.getData().isEmpty()) {
                Session s = coll.getData().get(0);
                sessionId = s.getId();
                if (email == null) {
                    if (s.getCustomerDetails() != null && s.getCustomerDetails().getEmail() != null) {
                        email = s.getCustomerDetails().getEmail();
                    } else if (s.getCustomerEmail() != null) {
                        email = s.getCustomerEmail();
                    }
                }
            }
        } catch (StripeException e) {
            log.warn("Failed to list sessions for PI {}: {}", pi.getId(), e.getMessage());
        }
        // Last resort for a hosted PI whose session lookup failed. Costs nothing
        // and turns an unfulfillable order into a fulfilled one.
        if (email == null) email = trimToNull(meta.get("buyer_email"));
        return new Resolved(email, sessionId);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
```

Add a test to `PaidCheckoutServiceTest` (it already has 15+ cases in this exact shape — copy one):

```java
    @Test
    void nativePaymentIntentIsFulfilledFromMetadataWithNoCheckoutSession() {
        // No latest_charge, and the session list is never consulted — exactly the
        // shape a Stripe PaymentSheet purchase arrives in.
        PaymentIntent pi = paymentIntentWithMetadata("pi_test_native", Map.of(
                "reservation_id", reservationId.toString(),
                "tier_id", tierId.toString(),
                "qty", "1",
                "event_id", eventId.toString(),
                "buyer_email", "native-buyer@example.test",
                "client", "native"));

        service.fulfil(pi);

        Order order = orders.findByStripePaymentIntentId("pi_test_native").orElseThrow();
        assertThat(order.getBuyerEmail()).isEqualTo("native-buyer@example.test");
        assertThat(order.getStripeSessionId()).isNull();
        verify(stripeClient, never()).checkout();
    }
```

- [ ] **Step 7: Let the app find the order it just paid for (BLOCKER)**

`CheckoutStatusService.statusFor:32-38` resolves only by Session id. Widen it:

```java
    /**
     * Resolves a just-paid checkout to its order token.
     *
     * <p>Two id shapes reach here. The web sends a Stripe Checkout Session id
     * (`cs_…`), which lands on `orders.stripe_session_id`. The app sends a
     * PaymentIntent id (`pi_…`), because a native PaymentSheet purchase has no
     * Session at all — and `orders.stripe_session_id` is therefore null for it.
     * Dispatching on the prefix keeps one endpoint and one polling contract for
     * both clients.
     *
     * <p>The id in the URL is the authorization, in both shapes: only the buyer's
     * own client ever holds it, and the response is `private, no-store`.
     */
    public Result statusFor(String id) {
        if (id == null || id.isBlank()) {
            return new Result(Status.PENDING, null);
        }
        Optional<Order> o = id.startsWith("pi_")
                ? orders.findByStripePaymentIntentId(id)
                : orders.findByStripeSessionId(id);
        return o.map(order -> new Result(Status.READY, order.getToken()))
                .orElse(new Result(Status.PENDING, null));
    }
```

`OrderRepository:21` already declares `findByStripePaymentIntentId` — no repository change. Add two cases to whichever test covers `CheckoutStatusService` (find it with `grep -rln CheckoutStatusService src/test/java`): a `pi_`-keyed order resolves to READY with its token, and an unknown `pi_` stays PENDING.

Update `imin-public/docs/PUBLIC_PAGE_API.md` in the same change: `GET /api/v1/public/checkout/{id}` now accepts either id shape.

- [ ] **Step 8: Cancel the PaymentIntent when its hold is released**

On the hosted path the reservation and the payability window are the same instant — `StripeCheckoutService:253` feeds both the reservation row and `.setExpiresAt(...)` on the Session (:368), so once `ReservationSweeper` returns the seats the session can no longer be paid. **A PaymentIntent has no `expires_at`.** Left alone, a native intent stays payable indefinitely: mint intents on a hot tier, wait out the 30-minute TTL, let the seats resell, then confirm. `InventoryService.confirmSold` on a `RELEASED` row deliberately credits `sold` anyway and logs `[OVERSOLD]`, so that becomes a real, fully-charged, fully-transferred oversold sale.

Step 5 already stamps the `pi_…` id onto the reservation via `attachSessionId`, so the sweeper has what it needs. In `ReservationSweeper`, when releasing an expired hold whose stored id starts with `pi_`, cancel it best-effort:

```java
    /**
     * A released hold must also stop being payable. The hosted path gets this
     * free from the Session's expires_at; a PaymentIntent has no equivalent, so
     * releasing the seats without cancelling would leave a payable intent for
     * inventory somebody else can now buy — and confirmSold credits an OVERSOLD
     * row rather than refusing.
     *
     * <p>Best-effort: a cancel failure must never stop the sweep. Stripe also
     * refuses to cancel an intent that already succeeded, which is correct — that
     * one is a real sale and the webhook will fulfil it.
     */
    private void cancelIfNativeIntent(TicketReservation reservation) {
        String id = reservation.getStripeSessionId();
        if (id == null || !id.startsWith("pi_")) return;
        try {
            stripeClient.paymentIntents().cancel(id);
        } catch (Exception e) {
            log.warn("Could not cancel PaymentIntent {} for released reservation {}: {}",
                    id, reservation.getId(), e.getMessage());
        }
    }
```

Inject `StripeClient` into `ReservationSweeper` and call this immediately after each release. Add a test: an expired `HELD` row carrying a `pi_` id is released **and** `paymentIntents().cancel(...)` is called with that id; a row carrying a `cs_` id is released and `cancel` is never called.
- [ ] **Step 9: Permit the endpoint in SecurityConfig**

In `src/main/java/com/imin/iminapi/config/SecurityConfig.java`, directly below the existing checkout rule at line 129, add:

```java
                        // Native checkout — same trust model as the hosted sibling above.
                        .requestMatchers(HttpMethod.POST, "/api/v1/public/events/*/payment-intent").permitAll()
```

- [ ] **Step 10: Run the tests**

Run: `./mvnw test -Dtest=NativePaymentIntentTest`
Expected: PASS (4 tests)

- [ ] **Step 11: Prove the two flows still agree on money**

Run: `./mvnw test -Dtest='*Checkout*,*Stripe*,*Quote*,*Inventory*'`
Expected: PASS. If a fee or inventory test fails, the Step 1 extraction changed behaviour — revert and redo it as a pure move.

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/imin/iminapi/stripe/StripePaymentIntentService.java \
        src/main/java/com/imin/iminapi/stripe/StripePaymentIntentController.java \
        src/main/java/com/imin/iminapi/stripe/StripeCheckoutService.java \
        src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java \
        src/main/java/com/imin/iminapi/service/ticket/CheckoutStatusService.java \
        src/main/java/com/imin/iminapi/service/event/ReservationSweeper.java \
        src/main/java/com/imin/iminapi/config/SecurityConfig.java \
        src/test/java/com/imin/iminapi/stripe/NativePaymentIntentTest.java \
        src/test/java/com/imin/iminapi/service/ticket/PaidCheckoutServiceTest.java
git commit -m "feat(checkout): PaymentIntent surface for the native payment sheet"
```

---

## Task 4: Native Google sign-in

The existing buyer Google flow is browser-shaped twice over: the authorize URL sets an `HttpOnly` nonce cookie that an `ASWebAuthenticationSession` cannot share with the app's HTTP client, and the redirect URI is one fixed web URL.

**None of that needs solving.** Native Google Sign-In hands the app an **ID token directly from the OS**. There is no code exchange, no redirect, no state and no nonce cookie — only a JWT to verify. The app requests it with the existing **web** client id as `serverClientId`, so one audience covers iOS and Android and no new Google client is needed.

The resolution matrix is untouched: this reuses `BuyerOAuthService.resolve`, which already handles known-identity / no-email / unverified-email / existing-address / brand-new, and is the class that must never import `OAuthAccountService`.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/oauth/OAuthProperties.java`
- Modify: `src/main/resources/application.yaml` — **required**, see Step 3
- Modify: `src/main/java/com/imin/iminapi/oauth/GoogleOAuthService.java:173-181`
- Modify: `src/main/java/com/imin/iminapi/buyer/controller/BuyerAuthController.java`
- Modify: `src/main/java/com/imin/iminapi/buyer/dto/BuyerAuthRequests.java`
- Modify: `src/main/java/com/imin/iminapi/config/SecurityConfig.java:178`
- Test: `src/test/java/com/imin/iminapi/buyer/BuyerNativeGoogleSignInTest.java`

**Interfaces:**
- Produces: `GoogleOAuthService.verifyNativeIdToken(String idToken) : OAuthUserInfo`; `BuyerAuthRequests.NativeIdToken(String idToken)`; `POST /api/v1/buyer/auth/google/native`.
- Consumes: `BuyerOAuthService.resolve(OAuthUserInfo, String)` (already public and `@Transactional`); `BuyerClientKind.isNative` (Task 1); `BuyerMeResponse.withSessionToken` (Task 1).

---

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/imin/iminapi/buyer/BuyerNativeGoogleSignInTest.java`:

```java
package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.service.BuyerOAuthService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.oauth.GoogleOAuthService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Native Google sign-in — an OS-issued ID token, no redirect, no nonce cookie.
 *
 * <p>The two properties that matter are the same ones
 * {@code BuyerGoogleSignInTest} asserts for the web flow: an unverified Google
 * email cannot mint or join an address claim, and a buyer sign-in creates zero
 * organizations and zero users.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
// Documents the PRODUCTION gate. It does not open the gate here: the class
// also declares @MockitoBean GoogleOAuthService, which replaces the bean
// wholesale, so nativeEnabled() answers Mockito's default false no matter what
// this property says. The @BeforeEach stub below is what actually opens it.
@org.springframework.test.context.TestPropertySource(
        properties = "imin.oauth.google.native-audience=test-native-audience")
class BuyerNativeGoogleSignInTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @MockitoBean EmailService email;
    @MockitoBean GoogleOAuthService google;

    @org.junit.jupiter.api.BeforeEach
    void openTheNativeGate() {
        // The controller asks the (mocked) service whether native sign-in is
        // configured. Without this every case 404s OAUTH_PROVIDER_DISABLED.
        when(google.nativeEnabled()).thenReturn(true);
    }

    @Test
    void verifiedIdTokenSignsInAndReturnsASessionToken() throws Exception {
        String address = "g-" + UUID.randomUUID() + "@example.test";
        when(google.verifyNativeIdToken(eq("id-token-ok")))
                .thenReturn(new OAuthUserInfo("google", "sub-" + address, address, true, "Sofiya", "K", "Sofiya K"));

        long orgsBefore = orgs.count();
        long usersBefore = users.count();

        mvc.perform(post("/api/v1/buyer/auth/google/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"id-token-ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.emails[0].email").value(address))
                .andExpect(jsonPath("$.emails[0].verified").value(true));

        assertThat(orgs.count()).isEqualTo(orgsBefore);
        assertThat(users.count()).isEqualTo(usersBefore);
    }

    @Test
    void unverifiedGoogleEmailIsRejected() throws Exception {
        String address = "unverified-" + UUID.randomUUID() + "@example.test";
        when(google.verifyNativeIdToken(eq("id-token-unverified")))
                .thenReturn(new OAuthUserInfo("google", "sub-x", address, false, null, null, null));

        mvc.perform(post("/api/v1/buyer/auth/google/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"id-token-unverified\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OAUTH_EMAIL_UNVERIFIED"));

        // The status alone would still pass if the gate ran AFTER the write.
        // This is the assertion that proves no address claim was minted.
        assertThat(buyerEmails.findByVerifiedKey(EmailNormalizer.normalize(address))).isEmpty();
    }

    @Test
    void blankIdTokenIs400() throws Exception {
        mvc.perform(post("/api/v1/buyer/auth/google/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=BuyerNativeGoogleSignInTest`
Expected: FAIL — compilation error (`verifyNativeIdToken` undefined) and 404 on the endpoint.

- [ ] **Step 3: Add the native audience property**

In `src/main/java/com/imin/iminapi/oauth/OAuthProperties.java`, inside the `Google` nested class add:

```java
        /**
         * Audience for ID tokens minted by the native mobile apps
         * ({@code GOOGLE_OAUTH_NATIVE_AUDIENCE}). Native Google Sign-In is
         * configured with this as its {@code serverClientId} on both iOS and
         * Android, so one audience covers both platforms.
         *
         * <p>Defaults to the web {@code client-id} because that is exactly what
         * {@code serverClientId} should be set to — a separate value is only
         * needed if the apps are ever pointed at a different Google project.
         */
        private String nativeAudience = "";

        public String getNativeAudience() {
            return nativeAudience == null || nativeAudience.isBlank() ? getClientId() : nativeAudience;
        }

        public void setNativeAudience(String nativeAudience) { this.nativeAudience = nativeAudience; }
```

**Also add the key to `src/main/resources/application.yaml`, under `imin.oauth.google`:**

```yaml
      # Audience for ID tokens from the native apps — the WEB client id above,
      # which is what both iOS and Android pass as serverClientId. Blank falls
      # back to client-id, which is the correct value in the normal case.
      native-audience: ${GOOGLE_OAUTH_NATIVE_AUDIENCE:}
```

This line is not optional. That file enumerates every `imin.oauth.google.*` key explicitly, and Spring's relaxed binding would otherwise only accept `IMIN_OAUTH_GOOGLE_NATIVE_AUDIENCE` — so the env var named everywhere else in this plan would be **silently inert**, falling back to `client-id` and looking correct until the day someone points the apps at a different Google project.

- [ ] **Step 4: Add ID-token verification to GoogleOAuthService**

In `src/main/java/com/imin/iminapi/oauth/GoogleOAuthService.java`, add a second lazily-built verifier and the public method:

```java
    private volatile OidcJwtVerifier nativeVerifier;

    /**
     * Verify an ID token minted by native Google Sign-In on iOS or Android.
     *
     * <p>No code exchange, no redirect URI, no state: the OS performs the
     * authorization and hands the app a signed ID token, so all that remains is
     * to prove the token is Google's, is for us, and has not expired. Nimbus
     * checks signature and {@code exp}; issuer and audience are asserted by
     * {@link OidcJwtVerifier} itself.
     *
     * <p>The audience is a <b>separate</b> verifier from the web one because the
     * expected audience differs — reusing the web verifier would accept a token
     * minted for the wrong client.
     */
    public OAuthUserInfo verifyNativeIdToken(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "idToken is required");
        }
        JWTClaimsSet claims = nativeVerifier().verify(idToken);
        try {
            return new OAuthUserInfo(
                    PROVIDER,
                    claims.getSubject(),
                    claims.getStringClaim("email"),
                    // asBool, NOT getBooleanClaim: Google spells email_verified
                    // as a boolean on some tokens and as the string "true" on
                    // others, and getBooleanClaim throws ParseException on the
                    // string form — which the catch below would turn into a 401
                    // for a perfectly valid token. This private static already
                    // exists at GoogleOAuthService:187-190 and the web path uses
                    // it at :141.
                    asBool(claims.getClaim("email_verified")),
                    claims.getStringClaim("given_name"),
                    claims.getStringClaim("family_name"),
                    claims.getStringClaim("name"));
        } catch (java.text.ParseException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Malformed Google ID token claims");
        }
    }

    private OidcJwtVerifier nativeVerifier() {
        OidcJwtVerifier v = nativeVerifier;
        if (v == null) {
            synchronized (this) {
                v = nativeVerifier;
                if (v == null) {
                    v = new OidcJwtVerifier(JWKS_URL, ISSUERS, props.getGoogle().getNativeAudience());
                    nativeVerifier = v;
                }
            }
        }
        return v;
    }
```

Add imports for `ApiException`, `ErrorCode`, `HttpStatus` if not already present.

- [ ] **Step 5: Add the request DTO**

In `src/main/java/com/imin/iminapi/buyer/dto/BuyerAuthRequests.java`, add:

```java
    /**
     * An OS-issued OIDC ID token from native Google Sign-In or Sign in with
     * Apple. {@code fullName} is Apple-only and first-sign-in-only — Apple
     * returns the name exactly once and never again, so the app must send it on
     * that first call or it is lost forever.
     */
    public record NativeIdToken(@jakarta.validation.constraints.NotBlank String idToken,
                                String fullName) {}
```

- [ ] **Step 6: Add the endpoint**

In `src/main/java/com/imin/iminapi/buyer/controller/BuyerAuthController.java`, add beside the Google web pair:

```java
    /**
     * Native Google sign-in. The app gets an ID token from the OS and posts it
     * here; there is no redirect, no {@code state} and no nonce cookie, because
     * there is no browser in the loop to bind to.
     *
     * <p>Resolution goes through the same {@link BuyerOAuthService#resolve}
     * matrix as the web callback — including the {@code email_verified} gate —
     * so the two entry points cannot diverge on who gets which account.
     */
    @PostMapping("/api/v1/buyer/auth/google/native")
    public ResponseEntity<BuyerMeResponse> googleNative(
            @Valid @RequestBody BuyerAuthRequests.NativeIdToken req,
            HttpServletRequest http) {
        // NOT requireGoogleEnabled(): that gate is isBuyerEnabled(), which
        // requires clientId + clientSecret + buyerRedirectUri. The native lane
        // has no redirect URI and performs no code exchange, so it needs no
        // client secret either — gating on the web flow's config would 404
        // native sign-in whenever GOOGLE_OAUTH_BUYER_REDIRECT_URI is unset, and
        // would 404 it in the test suite, which configures no imin.oauth block
        // at all. Mirror AppleNativeIdentityService.enabled() instead.
        if (!googleIdTokens.nativeEnabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.OAUTH_PROVIDER_DISABLED,
                    "google sign-in is not configured for native clients");
        }
        var info = googleIdTokens.verifyNativeIdToken(req.idToken());
        var signedIn = buyerIdentityResolver.resolve(info, userAgent(http));
        return signedInResponse(signedIn.account(), signedIn.session(), http);
    }
```

Inject `GoogleOAuthService googleIdTokens` into the constructor, and add to `GoogleOAuthService`:

```java
    /** Native sign-in needs only an audience to verify against. */
    public boolean nativeEnabled() {
        String audience = props.getGoogle().getNativeAudience();
        return audience != null && !audience.isBlank();
    }
```

> **Rename in this commit:** the controller's `BuyerOAuthService googleAuth` field becomes `buyerIdentityResolver`. Task 5 makes it resolve Apple identities too, and leaving it named `googleAuth` would mislead every later reader. Pure rename; the bean type is unchanged.

- [ ] **Step 7: Permit the endpoint**

In `SecurityConfig.java`, add `"/api/v1/buyer/auth/google/native"` to the existing permitAll POST list (line 170-178).

- [ ] **Step 8: Run the tests**

Run: `./mvnw test -Dtest=BuyerNativeGoogleSignInTest`
Expected: PASS (3 tests)

- [ ] **Step 9: Prove the web flow is untouched**

Run: `./mvnw test -Dtest='BuyerGoogleSignInTest,BuyerOAuthAudienceTest'`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/imin/iminapi/oauth/OAuthProperties.java \
        src/main/java/com/imin/iminapi/oauth/GoogleOAuthService.java \
        src/main/java/com/imin/iminapi/buyer/dto/BuyerAuthRequests.java \
        src/main/java/com/imin/iminapi/buyer/controller/BuyerAuthController.java \
        src/main/java/com/imin/iminapi/config/SecurityConfig.java \
        src/test/java/com/imin/iminapi/buyer/BuyerNativeGoogleSignInTest.java
git commit -m "feat(buyer): native Google sign-in via OS-issued ID token"
```

---

## Task 5: Native Sign in with Apple

**App Store Guideline 4.8 hard gate.** The app offers Google sign-in, so it must offer an equivalent privacy-preserving option or be rejected.

`BuyerOAuthService`'s Javadoc contains an explicit trap warning: `AppleOAuthService:131` hardcodes `emailVerified = true`, which would make the buyer-side verification gate a no-op. **This task must not reuse `AppleOAuthService`.** It builds a separate, minimal verifier that keeps the gate meaningful.

**The Hide My Email problem, resolved:** Apple's relay address (`…@privaterelay.appleid.com`) matches no past guest order, so a buyer who bought as a guest and then signs in with Apple sees an empty order history. That is correct and must not be "fixed" by matching on anything weaker — it is the same reason identities match on subject, never on email. The recovery path already exists: `/profile/emails` lets them add and verify their real address, which claims the orders. The app must surface that, and the response makes it detectable.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/buyer/model/BuyerIdentity.java:37`
- Modify: `src/main/java/com/imin/iminapi/oauth/OAuthProperties.java`
- Modify: `src/main/resources/application.yaml` — **required**, see Step 4
- Create: `src/main/java/com/imin/iminapi/oauth/AppleNativeIdentityService.java`
- Modify: `src/main/java/com/imin/iminapi/buyer/controller/BuyerAuthController.java`
- Modify: `src/main/java/com/imin/iminapi/config/SecurityConfig.java`
- Test: `src/test/java/com/imin/iminapi/buyer/BuyerNativeAppleSignInTest.java`

**Interfaces:**
- Produces: `BuyerIdentity.PROVIDER_APPLE = "apple"`; `AppleNativeIdentityService.verify(String idToken, String fullName) : OAuthUserInfo`; `AppleNativeIdentityService.enabled() : boolean`; `POST /api/v1/buyer/auth/apple/native`.
- Consumes: `OidcJwtVerifier(String jwkSetUrl, Set<String> issuers, String audience)`; `BuyerOAuthService.resolve(OAuthUserInfo, String)`; `BuyerAuthRequests.NativeIdToken` (Task 4).

> **Prerequisite — `BuyerOAuthService.resolve` is Google-flavoured in four places that are not the `PROVIDER` constant.** The constant (`:88`) is genuinely absent from `resolve`, but grepping for it is the wrong check. Fix all four as part of this task:
>
> 1. **`:205` files every new address as `ADDED_VIA_GOOGLE`.** An Apple relay address would be recorded as having arrived via Google — in `buyer_account_emails.added_via`, which is surfaced on `BuyerMeResponse:51` and feeds DSAR export. Add `BuyerAccountEmail.ADDED_VIA_APPLE = "apple"` and select on `info.provider()`. No migration needed: `V83:51` is `VARCHAR(16) NOT NULL` with no CHECK constraint.
> 2. **`:184`'s 409 message** says "Google has not verified this email address. Sign in with your password instead." — shown to an Apple user. Make it provider-neutral.
> 3. **`:196` and `:211` log** "google identity linked" / "google sign-up created buyer account". Interpolate the provider.
> 4. Only after those: confirm nothing else in the method assumes Google.

---

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/imin/iminapi/buyer/BuyerNativeAppleSignInTest.java`:

```java
package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.repository.BuyerIdentityRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.oauth.AppleNativeIdentityService;
import com.imin.iminapi.oauth.OAuthUserInfo;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Native Sign in with Apple — the App Store Guideline 4.8 requirement.
 *
 * <p>The property that matters most: a returning Apple user is matched on
 * SUBJECT, so a relay address that changes nothing still lands in the same
 * account, and two different Apple users never collide.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerNativeAppleSignInTest {

    @Autowired MockMvc mvc;
    @Autowired BuyerIdentityRepository identities;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @MockitoBean EmailService email;
    @MockitoBean AppleNativeIdentityService apple;

    @Test
    void firstSignInCreatesABuyerAccountAndNoOrganizer() throws Exception {
        String relay = UUID.randomUUID() + "@privaterelay.appleid.com";
        String subject = "apple-sub-" + UUID.randomUUID();
        when(apple.enabled()).thenReturn(true);
        when(apple.verify(eq("apple-token"), any()))
                .thenReturn(new OAuthUserInfo("apple", subject, relay, true, "Sofiya", "K", "Sofiya K"));

        long orgsBefore = orgs.count();
        long usersBefore = users.count();

        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"apple-token\",\"fullName\":\"Sofiya K\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.emails[0].email").value(relay));

        assertThat(orgs.count()).isEqualTo(orgsBefore);
        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(identities.findByProviderAndProviderUserId("apple", subject)).isPresent();
    }

    @Test
    void secondSignInReusesTheSameAccountEvenWithNoEmailInTheToken() throws Exception {
        String relay = UUID.randomUUID() + "@privaterelay.appleid.com";
        String subject = "apple-sub-" + UUID.randomUUID();
        when(apple.enabled()).thenReturn(true);
        when(apple.verify(eq("first"), any()))
                .thenReturn(new OAuthUserInfo("apple", subject, relay, true, "Sofiya", "K", "Sofiya K"));
        // Apple omits email on every sign-in after the first.
        when(apple.verify(eq("second"), any()))
                .thenReturn(new OAuthUserInfo("apple", subject, null, true, null, null, null));

        String first = mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"first\",\"fullName\":\"Sofiya K\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"second\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(idOf(second)).isEqualTo(idOf(first));
    }

    @Test
    void disabledProviderIs404() throws Exception {
        when(apple.enabled()).thenReturn(false);
        mvc.perform(post("/api/v1/buyer/auth/apple/native")
                        .header("X-Imin-Client", "native")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"whatever\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("OAUTH_PROVIDER_DISABLED"));
    }

    private static String idOf(String body) {
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=BuyerNativeAppleSignInTest`
Expected: FAIL — `AppleNativeIdentityService` does not exist.

- [ ] **Step 3: Add the provider constant**

In `src/main/java/com/imin/iminapi/buyer/model/BuyerIdentity.java`, beside `PROVIDER_GOOGLE`:

```java
    /**
     * Sign in with Apple. Note {@code buyer_identities.provider} is
     * {@code varchar(16)} with no CHECK constraint (V83), so this needs no
     * migration.
     */
    public static final String PROVIDER_APPLE = "apple";
```

- [ ] **Step 4: Add the native audience property**

In `OAuthProperties.Apple`, add:

```java
        /**
         * Audience for ID tokens issued to the native iOS app — the app's
         * <b>bundle identifier</b> ({@code APPLE_OAUTH_NATIVE_AUDIENCE}), not
         * the Services ID the web flow uses. Blank disables native Apple
         * sign-in, which the controller reports as
         * {@code 404 OAUTH_PROVIDER_DISABLED}.
         */
        private String nativeAudience = "";

        public String getNativeAudience() { return nativeAudience; }
        public void setNativeAudience(String nativeAudience) { this.nativeAudience = nativeAudience; }
```

**And the key in `src/main/resources/application.yaml`, under `imin.oauth.apple`:**

```yaml
      # Audience for ID tokens from the native iOS app — the app's BUNDLE
      # IDENTIFIER (wtf.imin.fan), NOT the Services ID in client-id above, which
      # is the organizer web flow's audience. Blank => native Apple sign-in 404s,
      # which also fails App Store review under Guideline 4.8.
      native-audience: ${APPLE_OAUTH_NATIVE_AUDIENCE:}
```

**Mandatory, and worse to omit here than on the Google side.** That file enumerates every `imin.oauth.apple.*` key explicitly, so without this line Spring binds only `IMIN_OAUTH_APPLE_NATIVE_AUDIENCE` and the documented env var does nothing. Apple's audience has **no fallback** — blank means disabled — so the symptom is a production gate that stays shut while the Railway variable that was supposed to open it sits there looking correct.

- [ ] **Step 5: Write the verifier**

Create `src/main/java/com/imin/iminapi/oauth/AppleNativeIdentityService.java`:

```java
package com.imin.iminapi.oauth;

import com.imin.iminapi.buyer.model.BuyerIdentity;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Verifies the ID token that {@code expo-apple-authentication} receives from
 * iOS, for the <b>buyer</b> surface.
 *
 * <h2>Why this is not {@link AppleOAuthService}</h2>
 *
 * <p>That class serves the organizer dashboard's web {@code form_post} flow and
 * hardcodes {@code emailVerified = true} at its line 131.
 * {@link com.imin.iminapi.buyer.service.BuyerOAuthService}'s Javadoc names that
 * exact line as a trap: inheriting it would make the buyer-side
 * {@code email_verified} gate a permanent no-op, and that gate is what stops a
 * token naming someone else's address from joining their order history.
 *
 * <p>So this class exists, it is small on purpose, and it asserts the claim
 * rather than assuming it. Apple does populate {@code email_verified} on ID
 * tokens (as a boolean or as the string {@code "true"} — both spellings occur),
 * and a relay address is always verified, so the gate costs a real Apple user
 * nothing.
 *
 * <h2>What native does not need</h2>
 *
 * <p>No client secret, no {@code .p8} key, no team id, no redirect URI, no
 * {@code state}: the OS performs the authorization and hands the app a signed
 * token. Only the JWKS and the audience are required, and the audience is the
 * app's bundle identifier rather than the web Services ID.
 *
 * <h2>The name, and why it arrives only once</h2>
 *
 * <p>Apple returns the user's name on the <b>first</b> authorization only and
 * never again. The app must forward it on that first call; afterwards the token
 * carries a subject and often nothing else. That is why
 * {@link #verify(String, String)} takes the name out-of-band instead of reading
 * it from the token.
 */
@Service
public class AppleNativeIdentityService {

    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final Set<String> ISSUERS = Set.of("https://appleid.apple.com");

    private final OAuthProperties props;
    private volatile OidcJwtVerifier verifier;

    public AppleNativeIdentityService(OAuthProperties props) {
        this.props = props;
    }

    /** False until the app's bundle id is configured — reported as 404, never as a broken button. */
    public boolean enabled() {
        String audience = props.getApple().getNativeAudience();
        return audience != null && !audience.isBlank();
    }

    /**
     * @param fullName the display name from the first authorization, or null on
     *                 every subsequent sign-in. Never trusted for anything but
     *                 display.
     */
    public OAuthUserInfo verify(String idToken, String fullName) {
        if (idToken == null || idToken.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "idToken is required");
        }
        JWTClaimsSet claims = verifier().verify(idToken);
        String subject;
        String email;
        Object verifiedClaim;
        try {
            subject = claims.getSubject();
            email = claims.getStringClaim("email");
            verifiedClaim = claims.getClaim("email_verified");
        } catch (java.text.ParseException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Malformed Apple ID token claims");
        }
        if (subject == null || subject.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Apple ID token has no subject");
        }

        // Apple spells this as a boolean on some tokens and as the string "true"
        // on others. Assert it either way rather than assuming it — see the
        // class Javadoc for why assuming is the bug we are avoiding.
        boolean emailVerified = Boolean.TRUE.equals(verifiedClaim)
                || "true".equalsIgnoreCase(String.valueOf(verifiedClaim));

        String display = fullName == null || fullName.isBlank() ? null : fullName.trim();
        return new OAuthUserInfo(BuyerIdentity.PROVIDER_APPLE, subject, email, emailVerified,
                null, null, display);
    }

    OidcJwtVerifier verifier() {          // package-private: the test injects a stub
        OidcJwtVerifier v = verifier;
        if (v == null) {
            synchronized (this) {
                v = verifier;
                if (v == null) {
                    v = new OidcJwtVerifier(JWKS_URL, ISSUERS, props.getApple().getNativeAudience());
                    verifier = v;
                }
            }
        }
        return v;
    }

    /** Test seam — lets a unit test drive {@link #verify} over canned claim sets. */
    void setVerifierForTest(OidcJwtVerifier stub) {
        this.verifier = stub;
    }
}
```

**Now unit-test the claim parsing, which is the whole reason this class exists.** The MockMvc test in Step 1 replaces this service with a mock whose stubs return `emailVerified = true` — reproducing the exact `AppleOAuthService:131` shortcut the class was written to avoid. Implementing `verify` as `new OAuthUserInfo(PROVIDER_APPLE, subject, email, true, …)` would leave all three of those tests green.

Create `src/test/java/com/imin/iminapi/oauth/AppleNativeIdentityServiceTest.java`:

```java
package com.imin.iminapi.oauth;

import com.imin.iminapi.security.ApiException;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The email_verified gate, which is the entire point of this class existing
 * separately from {@link AppleOAuthService} (that one hardcodes true at :131).
 * If these pass while `verify` hardcodes true, they are worthless — so the
 * false and absent cases are the load-bearing ones.
 */
class AppleNativeIdentityServiceTest {

    private OidcJwtVerifier jwt;
    private AppleNativeIdentityService service;

    @BeforeEach
    void setUp() {
        OAuthProperties props = new OAuthProperties();
        props.getApple().setNativeAudience("wtf.imin.fan");
        jwt = mock(OidcJwtVerifier.class);
        service = new AppleNativeIdentityService(props);
        service.setVerifierForTest(jwt);
    }

    @Test
    void booleanTrueIsVerified() {
        stub(Map.of("sub", "apple-1", "email", "a@b.test", "email_verified", true));
        assertThat(service.verify("tok", null).emailVerified()).isTrue();
    }

    /** Apple emits the string form on some tokens. Both must be accepted. */
    @Test
    void stringTrueIsAlsoVerified() {
        stub(Map.of("sub", "apple-2", "email", "a@b.test", "email_verified", "true"));
        assertThat(service.verify("tok", null).emailVerified()).isTrue();
    }

    @Test
    void booleanFalseIsNotVerified() {
        stub(Map.of("sub", "apple-3", "email", "a@b.test", "email_verified", false));
        assertThat(service.verify("tok", null).emailVerified()).isFalse();
    }

    /** No claim means no assertion — never assume true. */
    @Test
    void absentClaimIsNotVerified() {
        stub(Map.of("sub", "apple-4", "email", "a@b.test"));
        assertThat(service.verify("tok", null).emailVerified()).isFalse();
    }

    @Test
    void aTokenWithNoSubjectIsRejected() {
        stub(Map.of("email", "a@b.test", "email_verified", true));
        assertThatThrownBy(() -> service.verify("tok", null))
                .isInstanceOf(ApiException.class);
    }

    /** Apple returns the name once, on first authorization, out of band. */
    @Test
    void theDisplayNameComesFromTheParameterNotTheToken() {
        stub(Map.of("sub", "apple-5", "email_verified", true));
        assertThat(service.verify("tok", "  Sofiya K  ").displayName()).isEqualTo("Sofiya K");
        assertThat(service.verify("tok", "   ").displayName()).isNull();
    }

    @Test
    void blankAudienceDisablesTheProvider() {
        OAuthProperties blank = new OAuthProperties();
        assertThat(new AppleNativeIdentityService(blank).enabled()).isFalse();
    }

    private void stub(Map<String, Object> claims) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder();
        claims.forEach((k, v) -> {
            if ("sub".equals(k)) b.subject(String.valueOf(v)); else b.claim(k, v);
        });
        when(jwt.verify(anyString())).thenReturn(b.build());
    }
}
```

> `OidcJwtVerifier` is a concrete class with no interface; Mockito can mock it as long as it is not final. Confirm that before writing the test — if it is final, add `mockito-inline` or extract the `verify(String)` call behind a one-method functional interface.

- [ ] **Step 6: Add the endpoint**

In `BuyerAuthController.java`:

```java
    /**
     * Native Sign in with Apple. Required by App Store Guideline 4.8 wherever
     * Google sign-in is offered.
     *
     * <p>A buyer arriving on a Hide My Email relay address matches no past guest
     * order — deliberately, because identities match on subject and never on
     * email. They recover their history by adding and verifying their real
     * address under {@code /buyer/emails}, and the app should offer that
     * immediately after a first Apple sign-in.
     */
    @PostMapping("/api/v1/buyer/auth/apple/native")
    public ResponseEntity<BuyerMeResponse> appleNative(
            @Valid @RequestBody BuyerAuthRequests.NativeIdToken req,
            HttpServletRequest http) {
        if (!appleIdentities.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.OAUTH_PROVIDER_DISABLED,
                    "apple sign-in is not configured for buyers");
        }
        var info = appleIdentities.verify(req.idToken(), req.fullName());
        var signedIn = buyerIdentityResolver.resolve(info, userAgent(http));
        return signedInResponse(signedIn.account(), signedIn.session(), http);
    }
```

Inject `AppleNativeIdentityService appleIdentities`. (`buyerIdentityResolver` is the `BuyerOAuthService` field renamed in Task 4.)

- [ ] **Step 7: Permit the endpoint**

Add `"/api/v1/buyer/auth/apple/native"` to the permitAll POST list in `SecurityConfig.java`.

- [ ] **Step 8: Run the tests**

Run: `./mvnw test -Dtest=BuyerNativeAppleSignInTest`
Expected: PASS (3 tests)

- [ ] **Step 9: Prove the organizer Apple flow is untouched**

Run: `./mvnw test -Dtest='*Apple*,*OAuth*'`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/imin/iminapi/oauth/AppleNativeIdentityService.java \
        src/main/java/com/imin/iminapi/oauth/OAuthProperties.java \
        src/main/java/com/imin/iminapi/buyer/model/BuyerIdentity.java \
        src/main/java/com/imin/iminapi/buyer/controller/BuyerAuthController.java \
        src/main/java/com/imin/iminapi/config/SecurityConfig.java \
        src/test/java/com/imin/iminapi/buyer/BuyerNativeAppleSignInTest.java
git commit -m "feat(buyer): native Sign in with Apple for the mobile app"
```

---

## Task 6: Push device registry

**Files:**
- Create: `src/main/resources/db/migration/V92__buyer_push_devices.sql`
- Create: `src/main/java/com/imin/iminapi/buyer/model/BuyerPushDevice.java`
- Create: `src/main/java/com/imin/iminapi/buyer/repository/BuyerPushDeviceRepository.java`
- Create: `src/main/java/com/imin/iminapi/buyer/dto/BuyerPushDeviceRequests.java`
- Create: `src/main/java/com/imin/iminapi/buyer/service/BuyerPushDeviceService.java`
- Create: `src/main/java/com/imin/iminapi/buyer/controller/BuyerPushDeviceController.java`
- Modify: `src/main/java/com/imin/iminapi/buyer/model/BuyerNotificationPreference.java`
- Modify: `src/main/java/com/imin/iminapi/buyer/dto/BuyerPreferencesResponse.java`
- Modify: `src/main/java/com/imin/iminapi/buyer/service/BuyerPreferencesService.java`
- Test: `src/test/java/com/imin/iminapi/buyer/BuyerPushDeviceTest.java`

**Interfaces:**
- Produces: `BuyerPushDevice` entity; `BuyerPushDeviceRepository.findByExpoToken(String)`, `.findLiveTokensForAccounts(Collection<UUID>)`, `.revokeByTokens(Collection<String>, Instant)`; `BuyerPushDeviceService.register(UUID, String, String, String, String)`, `.revoke(UUID, String)`; `POST/DELETE /api/v1/buyer/push-devices`.
- Consumes: `@CurrentBuyer BuyerPrincipal` (`buyer.accountId()`); the bearer lane from Task 1.

---

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V92__buyer_push_devices.sql`:

```sql
-- V92__buyer_push_devices.sql
-- Push notification devices for the native fan app (mobile Phase 0).
--
-- One row per installed app that has been granted OS notification permission
-- AND has a signed-in buyer. Guests never register: notify_subscriptions is
-- email-keyed and stays the guest path, which keeps this table free of an
-- unauthenticated write endpoint.
--
-- The token is a delivery ADDRESS, not a credential, so it is stored raw --
-- unlike buyer_sessions.token_hash, nothing here authenticates anybody, and a
-- hash would make sending impossible.
CREATE TABLE buyer_push_devices (
    id                UUID PRIMARY KEY,
    buyer_account_id  UUID NOT NULL REFERENCES buyer_accounts(id) ON DELETE CASCADE,
    expo_token        VARCHAR(255) NOT NULL,
    platform          VARCHAR(16)  NOT NULL,
    locale            VARCHAR(8),
    app_version       VARCHAR(32),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at        TIMESTAMP WITH TIME ZONE NULL,

    -- Globally unique, NOT unique per account. One physical device is one
    -- delivery address: when a second buyer signs in on it, the row must be
    -- RE-POINTED, not duplicated, or the first buyer keeps receiving alerts on
    -- a phone that is no longer theirs.
    CONSTRAINT uq_buyer_push_devices_token UNIQUE (expo_token)
);

-- The fan-out scan is "live devices for these accounts". Leading with the
-- revoked marker clusters the NULLs so the scan touches only live rows and
-- reads buyer_account_id straight off the index -- the portable substitute for
-- a Postgres partial index, per the V76/V87 house pattern (H2 backs the tests).
CREATE INDEX ix_buyer_push_devices_live ON buyer_push_devices (revoked_at, buyer_account_id);

-- The in-app switch for drop-alert pushes. The OS permission is the primary
-- gate; this is what the Notifications screen renders, and what lets someone
-- keep the permission while silencing imin. Default true: registering a device
-- at all is an affirmative act that already required an OS prompt.
ALTER TABLE buyer_notification_preferences
    ADD COLUMN push_drop_alerts BOOLEAN NOT NULL DEFAULT TRUE;
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/imin/iminapi/buyer/BuyerPushDeviceTest.java`:

```java
package com.imin.iminapi.buyer;

import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The push device registry. The property that matters most: a device that
 * changes hands must change owners, not accumulate them — otherwise the
 * previous buyer keeps getting alerts on a phone that is no longer theirs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class BuyerPushDeviceTest extends NativeBuyerTestBase {

    private static final String TOKEN = "ExponentPushToken[aaaaaaaaaaaaaaaaaaaaaa]";

    @Autowired MockMvc mvc;
    @Autowired BuyerPushDeviceRepository devices;
    @MockitoBean EmailService email;

    @Test
    void registrationIsIdempotent() throws Exception {
        String bearer = signUpAndSignInNative();

        register(bearer, TOKEN).andExpect(status().isNoContent());
        register(bearer, TOKEN).andExpect(status().isNoContent());

        assertThat(devices.findByExpoToken(TOKEN)).isPresent();
        assertThat(devices.count()).isEqualTo(1);
    }

    @Test
    void aDeviceThatChangesHandsChangesOwners() throws Exception {
        String first = signUpAndSignInNative();
        String second = signUpAndSignInNative();

        register(first, TOKEN).andExpect(status().isNoContent());
        register(second, TOKEN).andExpect(status().isNoContent());

        assertThat(devices.count()).isEqualTo(1);
        var row = devices.findByExpoToken(TOKEN).orElseThrow();
        assertThat(row.getBuyerAccountId()).isEqualTo(accountIdOf(second));
        assertThat(row.getRevokedAt()).isNull();
    }

    @Test
    void deleteRevokesAndStopsCountingAsLive() throws Exception {
        String bearer = signUpAndSignInNative();
        register(bearer, TOKEN).andExpect(status().isNoContent());

        revoke(bearer, TOKEN).andExpect(status().isNoContent());

        assertThat(devices.findByExpoToken(TOKEN).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(devices.findLiveTokensForAccounts(
                java.util.List.of(accountIdOf(bearer)))).isEmpty();
    }

    @Test
    void oneBuyerCannotRevokeAnothersDevice() throws Exception {
        String owner = signUpAndSignInNative();
        String stranger = signUpAndSignInNative();
        register(owner, TOKEN).andExpect(status().isNoContent());

        revoke(stranger, TOKEN).andExpect(status().isNoContent());   // idempotent, leaks nothing

        assertThat(devices.findByExpoToken(TOKEN).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void preferencesExposeThePushSwitch() throws Exception {
        String bearer = signUpAndSignInNative();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/buyer/preferences")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushDropAlerts").value(true));
    }

    private org.springframework.test.web.servlet.ResultActions register(String bearer, String token)
            throws Exception {
        return mvc.perform(post("/api/v1/buyer/push-devices")
                .header("Authorization", "Bearer " + bearer)
                .header("X-Imin-Client", "native")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expoToken\":\"" + token + "\",\"platform\":\"ios\",\"locale\":\"en\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions revoke(String bearer, String token)
            throws Exception {
        return mvc.perform(post("/api/v1/buyer/push-devices/revoke")
                .header("Authorization", "Bearer " + bearer)
                .header("X-Imin-Client", "native")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expoToken\":\"" + token + "\"}"));
    }
}
```

> **Implementer note:** extract the signup/verify/native-login helper from `BuyerNativeSessionTest` (Task 1) into `src/test/java/com/imin/iminapi/buyer/NativeBuyerTestBase.java` with `signUpAndSignInNative() : String` (returns the bearer token) and `accountIdOf(String bearer) : UUID`, and have both tests extend it. Do this as part of this step — two copies of that helper will drift.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=BuyerPushDeviceTest`
Expected: FAIL — `BuyerPushDeviceRepository` does not exist.

- [ ] **Step 4: Write the entity and repository**

Create `src/main/java/com/imin/iminapi/buyer/model/BuyerPushDevice.java`:

```java
package com.imin.iminapi.buyer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One installed app instance that can receive push notifications (V92).
 *
 * <p>{@code expoToken} is globally unique, not unique per account: a physical
 * device is one delivery address, so a second buyer signing in on it re-points
 * this row rather than adding another. Without that, the previous owner keeps
 * receiving alerts on a phone that is no longer theirs.
 */
@Entity
@Table(name = "buyer_push_devices")
@Getter @Setter @NoArgsConstructor
public class BuyerPushDevice {

    @Id
    // Strategy is explicit, matching all four sibling buyer entities. A bare
    // @GeneratedValue lets the provider pick, and on Hibernate 6 that is a
    // sequence — wrong for a UUID column.
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.UUID)
    private UUID id;

    @Column(name = "buyer_account_id", nullable = false)
    private UUID buyerAccountId;

    @Column(name = "expo_token", nullable = false, length = 255)
    private String expoToken;

    /** {@code ios} or {@code android}. Display and diagnostics only. */
    @Column(name = "platform", nullable = false, length = 16)
    private String platform;

    @Column(name = "locale", length = 8)
    private String locale;

    @Column(name = "app_version", length = 32)
    private String appVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Set on sign-out, or when Expo reports the token as DeviceNotRegistered. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @PrePersist
    void stampMicros() {
        if (createdAt == null) createdAt = Instant.now();
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        if (lastSeenAt == null) lastSeenAt = createdAt;
        lastSeenAt = lastSeenAt.truncatedTo(ChronoUnit.MICROS);
    }
}
```

Create `src/main/java/com/imin/iminapi/buyer/repository/BuyerPushDeviceRepository.java`:

```java
package com.imin.iminapi.buyer.repository;

import com.imin.iminapi.buyer.model.BuyerPushDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code exported = false} is mandatory: Spring Data REST auto-exposes every
 * repository in this project, and an exported repository would publish every
 * buyer's push tokens on an unauthenticated CRUD surface.
 */
@RepositoryRestResource(exported = false)
public interface BuyerPushDeviceRepository extends JpaRepository<BuyerPushDevice, UUID> {

    Optional<BuyerPushDevice> findByExpoToken(String expoToken);

    /** Live delivery addresses for a batch of accounts. Used by the drop-alert fan-out. */
    @Query("SELECT d.expoToken FROM BuyerPushDevice d "
         + "WHERE d.revokedAt IS NULL AND d.buyerAccountId IN :accountIds")
    List<String> findLiveTokensForAccounts(@Param("accountIds") Collection<UUID> accountIds);

    @Modifying
    @Query("UPDATE BuyerPushDevice d SET d.revokedAt = :now "
         + "WHERE d.revokedAt IS NULL AND d.expoToken IN :tokens")
    int revokeByTokens(@Param("tokens") Collection<String> tokens, @Param("now") Instant now);
}
```

- [ ] **Step 5: Write the DTO, service and controller**

Create `src/main/java/com/imin/iminapi/buyer/dto/BuyerPushDeviceRequests.java`:

```java
package com.imin.iminapi.buyer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class BuyerPushDeviceRequests {

    private BuyerPushDeviceRequests() {}

    /**
     * @param expoToken an {@code ExponentPushToken[…]} value from
     *                  {@code expo-notifications}. Length-capped to the column
     *                  so an over-long value is a 400, not a truncated insert.
     */
    public record Register(@NotBlank @Size(max = 255) String expoToken,
                           @NotBlank @Pattern(regexp = "ios|android") String platform,
                           @Size(max = 8) String locale,
                           @Size(max = 32) String appVersion) {}

    /** Sign-out. The token is a body field because it contains `[` and `]` — see the controller. */
    public record Revoke(@NotBlank @Size(max = 255) String expoToken) {}
}
```

Create `src/main/java/com/imin/iminapi/buyer/service/BuyerPushDeviceService.java`:

```java
package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.model.BuyerPushDevice;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.util.Times;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Registers and revokes push delivery addresses. */
@Service
public class BuyerPushDeviceService {

    private final BuyerPushDeviceRepository devices;

    public BuyerPushDeviceService(BuyerPushDeviceRepository devices) {
        this.devices = devices;
    }

    /**
     * Idempotent upsert keyed on the token. A token already known to another
     * account is <b>re-pointed</b>, not rejected and not duplicated: the device
     * changed hands, and the previous owner must stop receiving alerts on it.
     * Re-pointing also clears {@code revoked_at}, so signing back in on a device
     * that was signed out works without a second row.
     */
    @Transactional
    public void register(UUID accountId, String expoToken, String platform,
                         String locale, String appVersion) {
        BuyerPushDevice device = devices.findByExpoToken(expoToken)
                .orElseGet(BuyerPushDevice::new);
        device.setBuyerAccountId(accountId);
        device.setExpoToken(expoToken);
        device.setPlatform(platform);
        device.setLocale(locale);
        device.setAppVersion(appVersion);
        device.setLastSeenAt(Times.nowMicros());
        device.setRevokedAt(null);
        devices.save(device);
    }

    /**
     * Revokes a device the caller owns. Silent when the token is unknown, already
     * revoked, or belongs to someone else — the caller learns nothing either way,
     * and sign-out must never fail.
     */
    @Transactional
    public void revoke(UUID accountId, String expoToken) {
        devices.findByExpoToken(expoToken)
                .filter(d -> accountId.equals(d.getBuyerAccountId()))
                .filter(d -> d.getRevokedAt() == null)
                .ifPresent(d -> {
                    d.setRevokedAt(Times.nowMicros());
                    devices.save(d);
                });
    }
}
```

Create `src/main/java/com/imin/iminapi/buyer/controller/BuyerPushDeviceController.java`:

```java
package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.dto.BuyerPushDeviceRequests;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.buyer.service.BuyerPushDeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Push device registration for the native app. Authenticated as a buyer, which
 * is why guests never appear here — they keep the email drop-alert path.
 */
@RestController
public class BuyerPushDeviceController {

    private static final String NO_STORE = "private, no-store";

    private final BuyerPushDeviceService devices;

    public BuyerPushDeviceController(BuyerPushDeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/api/v1/buyer/push-devices")
    public ResponseEntity<Void> register(@CurrentBuyer BuyerPrincipal buyer,
                                          @Valid @RequestBody BuyerPushDeviceRequests.Register req) {
        devices.register(buyer.accountId(), req.expoToken(), req.platform(),
                req.locale(), req.appVersion());
        return noContent();
    }

    /**
     * Always 204 — sign-out must never fail on a bookkeeping call.
     *
     * <p><b>The token goes in the body, not the path.</b> An Expo token is
     * literally {@code ExponentPushToken[…]}, and {@code [} / {@code ]} are
     * gen-delims that are illegal unencoded in a path segment. Tomcat rejects
     * them with a 400 before Spring sees the request unless
     * {@code server.tomcat.relaxed-path-chars} is set, which it is not here —
     * and the WHATWG percent-encode set for paths does not include brackets, so
     * a URL built by string concatenation in the app sends them raw. MockMvc
     * builds its request object directly and never runs the container's URI
     * parser, so a {@code @DeleteMapping("/{expoToken}")} would test green here
     * and 400 on every real sign-out, leaving the device receiving pushes.
     *
     * <p>A body also keeps the token out of access logs.
     */
    @PostMapping("/api/v1/buyer/push-devices/revoke")
    public ResponseEntity<Void> revoke(@CurrentBuyer BuyerPrincipal buyer,
                                        @Valid @RequestBody BuyerPushDeviceRequests.Revoke req) {
        devices.revoke(buyer.accountId(), req.expoToken());
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }
}
```

- [ ] **Step 6: Wire the preference column**

In `BuyerNotificationPreference.java` add:

```java
    /**
     * The in-app switch for drop-alert pushes (V92). The OS permission is the
     * primary gate; this is what lets someone keep the permission and still
     * silence imin. Default true — registering a device already required an
     * affirmative OS prompt.
     */
    @Column(name = "push_drop_alerts", nullable = false)
    private boolean pushDropAlerts = true;
```

Add `pushDropAlerts` to `BuyerPreferencesResponse` and populate it in `BuyerPreferencesService`, following exactly how `eventReminders` is read and written there — including the absent-row default (`true`).

- [ ] **Step 7: Run the tests**

Run: `./mvnw test -Dtest=BuyerPushDeviceTest`
Expected: PASS (5 tests)

- [ ] **Step 8: Run the buyer suite**

Run: `./mvnw test -Dtest='Buyer*'`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V92__buyer_push_devices.sql \
        src/main/java/com/imin/iminapi/buyer/model/BuyerPushDevice.java \
        src/main/java/com/imin/iminapi/buyer/model/BuyerNotificationPreference.java \
        src/main/java/com/imin/iminapi/buyer/repository/BuyerPushDeviceRepository.java \
        src/main/java/com/imin/iminapi/buyer/dto/BuyerPushDeviceRequests.java \
        src/main/java/com/imin/iminapi/buyer/dto/BuyerPreferencesResponse.java \
        src/main/java/com/imin/iminapi/buyer/service/BuyerPushDeviceService.java \
        src/main/java/com/imin/iminapi/buyer/service/BuyerPreferencesService.java \
        src/main/java/com/imin/iminapi/buyer/controller/BuyerPushDeviceController.java \
        src/test/java/com/imin/iminapi/buyer/BuyerPushDeviceTest.java \
        src/test/java/com/imin/iminapi/buyer/NativeBuyerTestBase.java
git commit -m "feat(buyer): push device registry and drop-alert push preference"
```

---

## Task 7: Push delivery on drop alerts

**Files:**
- Create: `src/main/java/com/imin/iminapi/push/PushProperties.java`
- Create: `src/main/java/com/imin/iminapi/push/PushMessage.java`
- Create: `src/main/java/com/imin/iminapi/push/ExpoPushSender.java`
- Create: `src/main/java/com/imin/iminapi/push/PushConfig.java`
- Modify: `src/main/java/com/imin/iminapi/service/event/NotifyReleaseSender.java:127-170`
- Modify: `src/main/java/com/imin/iminapi/buyer/repository/BuyerPushDeviceRepository.java` — `@Transactional` on `revokeByTokens`
- Modify: `src/test/java/com/imin/iminapi/service/event/NotifyReleaseSenderTest.java:84-85` — **compile-breaking**, see Step 7b
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/com/imin/iminapi/push/DropAlertPushTest.java`
- Test: `src/test/java/com/imin/iminapi/push/ExpoPushSenderTest.java`
- Test: `src/test/java/com/imin/iminapi/push/DropAlertFanOutTest.java`

**Interfaces:**
- Produces: `PushMessage(String to, String title, String body, Map<String,Object> data)`; `ExpoPushSender.Result(int accepted, Set<String> deadTokens)` with `Result.NONE`; `ExpoPushSender.send(List<PushMessage>) : Result`; `ExpoPushSender.batch(List<PushMessage>) : List<List<PushMessage>>` (package-private, tested directly).
- Consumes: `BuyerPushDeviceRepository.findLiveTokensForAccounts`, `.revokeByTokens`; `BuyerAccountEmailRepository.findByVerifiedKey(String)`; `BuyerNotificationPreferenceRepository`.

---

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/imin/iminapi/push/DropAlertPushTest.java`:

```java
package com.imin.iminapi.push;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Expo payload contract and the batch boundary.
 *
 * <p>Expo accepts at most 100 messages per request. A fan-out that silently
 * sends only the first 100 is the exact failure this covers — a sold-out
 * headliner is precisely when the list is long and precisely when nobody would
 * notice the tail was dropped.
 */
class DropAlertPushTest {

    @Test
    void batchesOfMoreThanOneHundredAreSplit() {
        List<PushMessage> messages = java.util.stream.IntStream.range(0, 250)
                .mapToObj(i -> new PushMessage("ExponentPushToken[t" + i + "]",
                        "Tickets are live", "Vechirka", PushMessage.CHANNEL_DROP_ALERTS,
                        Map.of("eventId", "e1")))
                .toList();

        List<List<PushMessage>> batches = ExpoPushSender.batch(messages);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).hasSize(100);
        assertThat(batches.get(2)).hasSize(50);
        assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(250);
    }

    @Test
    void emptyInputProducesNoBatches() {
        assertThat(ExpoPushSender.batch(List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=DropAlertPushTest`
Expected: FAIL — compilation error, `PushMessage` and `ExpoPushSender` do not exist.

- [ ] **Step 3: Write the push package**

Create `src/main/java/com/imin/iminapi/push/PushProperties.java`:

```java
package com.imin.iminapi.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for outbound push ({@code imin.push.*}). */
@ConfigurationProperties(prefix = "imin.push")
public class PushProperties {

    /**
     * Master switch ({@code IMIN_PUSH_ENABLED}). Default false: the feature is
     * dark until the app ships and real tokens exist, and a dark sender is
     * better than one firing at an empty registry.
     */
    private boolean enabled = false;

    private String baseUrl = "https://exp.host/--/api/v2/push/send";

    /**
     * Optional Expo access token ({@code EXPO_ACCESS_TOKEN}). Expo accepts
     * unauthenticated sends; with enhanced security enabled on the Expo project
     * this becomes required, and setting it is the safer default.
     */
    private String accessToken = "";

    private int timeoutSeconds = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
```

Create `src/main/java/com/imin/iminapi/push/PushMessage.java`:

```java
package com.imin.iminapi.push;

import java.util.Map;

/**
 * One push, addressed to one device.
 *
 * @param to   an {@code ExponentPushToken[…]} value.
 * @param data payload the app reads to deep-link. Keep it small — APNs caps the
 *             whole notification at 4 KB and Expo rejects oversized payloads
 *             for the entire batch, not just the offending message.
 */
public record PushMessage(String to, String title, String body, String channelId,
                          Map<String, Object> data) {

    /**
     * Android notification channels are created <b>by the app binary</b>, not by
     * the server. A channel the launch cohort never created is a notification
     * they never see; sending a second notification type down the drop-alerts
     * channel means it is mislabelled in the user's own system settings and is
     * silenced along with drop alerts. So the taxonomy is declared here, in full,
     * before v1.0.0 ships — the app creates all four at startup and later types
     * cost nothing.
     */
    public static final String CHANNEL_DROP_ALERTS = "drop-alerts";
    public static final String CHANNEL_TICKETS = "tickets";
    public static final String CHANNEL_REMINDERS = "reminders";
    public static final String CHANNEL_ORGANIZER_UPDATES = "organizer-updates";
}
```

Create `src/main/java/com/imin/iminapi/push/ExpoPushSender.java`:

```java
package com.imin.iminapi.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sends push notifications through Expo's push service, which fans out to both
 * APNs and FCM from one HTTP call with one credential.
 *
 * <p><b>Why Expo rather than Firebase Admin + APNs directly:</b> the client is
 * an Expo app, so its tokens are already Expo push tokens, and this avoids a
 * Firebase service account, an APNs {@code .p8} key, JWT minting, and a second
 * SDK — for a feature whose entire v1 scope is one notification type.
 *
 * <p><b>The ceiling, and the upgrade path:</b> this couples delivery to Expo's
 * availability and to their 100-messages-per-request limit. If volume or
 * independence ever demands it, replace the body of {@link #send} with Firebase
 * Admin plus an APNs client; {@link PushMessage} and the call site in
 * {@code NotifyReleaseSender} do not change.
 *
 * <p><b>Failure policy:</b> never throws. Push is an enhancement to an email
 * that is already being sent, and a push outage must not stop or duplicate that
 * email. Tokens Expo reports as {@code DeviceNotRegistered} are returned so the
 * caller can revoke them — that is the only way the registry stays clean, since
 * a deleted app never tells us it is gone.
 */
@Component
public class ExpoPushSender {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushSender.class);

    /** Expo's documented per-request maximum. */
    static final int MAX_BATCH = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PushProperties props;
    private final RestClient http;

    public ExpoPushSender(PushProperties props, RestClient.Builder builder) {
        this.props = props;
        // The timeout is not decorative. This POST runs inline on
        // NotifyReleaseSender.sweep(), a @Scheduled method on Spring's default
        // scheduler — which is POOL SIZE 1, because spring.task.scheduling.pool.size
        // is unset. A hung connection to exp.host would stall all 25 @Scheduled
        // jobs in this repo, including ReservationSweeper (the thing that
        // releases stale inventory holds), and outlive the ShedLock
        // lockAtMostFor, letting a second replica re-enter the sweep. There is
        // no global HTTP timeout default to fall back on.
        Duration timeout = Duration.ofSeconds(props.getTimeoutSeconds());
        this.http = builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .withConnectTimeout(timeout)
                        .withReadTimeout(timeout))
                .build();
    }

    /** What one fan-out achieved, plus the tokens that must be revoked. */
    public record Result(int accepted, Set<String> deadTokens) {
        public static final Result NONE = new Result(0, Set.of());
    }

    public Result send(List<PushMessage> messages) {
        if (!props.isEnabled() || messages == null || messages.isEmpty()) return Result.NONE;

        int accepted = 0;
        Set<String> dead = new LinkedHashSet<>();
        for (List<PushMessage> chunk : batch(messages)) {
            try {
                JsonNode response = post(chunk);
                accepted += readTickets(response, chunk, dead);
            } catch (Exception e) {
                // At-most-once for push, deliberately. The email is the promise;
                // this is the extra. Retrying risks double-notifying somebody.
                log.warn("[push] batch of {} failed — {}", chunk.size(), e.getMessage());
            }
        }
        log.info("[push] accepted={} dead={} of {}", accepted, dead.size(), messages.size());
        return new Result(accepted, dead);
    }

    /** Split into Expo-sized chunks. Package-private so the boundary is testable. */
    static List<List<PushMessage>> batch(List<PushMessage> messages) {
        List<List<PushMessage>> out = new ArrayList<>();
        for (int i = 0; i < messages.size(); i += MAX_BATCH) {
            out.add(List.copyOf(messages.subList(i, Math.min(i + MAX_BATCH, messages.size()))));
        }
        return out;
    }

    private JsonNode post(List<PushMessage> chunk) {
        List<Map<String, Object>> body = chunk.stream().map(m -> {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("to", m.to());
            msg.put("title", m.title());
            msg.put("body", m.body());
            msg.put("sound", "default");
            // Android requires a channel the app created at startup; without a
            // matching one the notification is delivered silently on Android 8+.
            msg.put("channelId", m.channelId());
            if (m.data() != null && !m.data().isEmpty()) msg.put("data", m.data());
            return msg;
        }).toList();

        RestClient.RequestBodySpec spec = http.post()
                .uri(props.getBaseUrl())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (props.getAccessToken() != null && !props.getAccessToken().isBlank()) {
            spec = spec.header("Authorization", "Bearer " + props.getAccessToken());
        }
        return spec.body(body).retrieve().body(JsonNode.class);
    }

    /**
     * Expo answers with one ticket per message, in order. A ticket with
     * {@code details.error = DeviceNotRegistered} means the app was uninstalled
     * or the token rotated; that token is dead and must never be sent to again.
     */
    private int readTickets(JsonNode response, List<PushMessage> chunk, Set<String> dead) {
        if (response == null) return 0;
        JsonNode tickets = response.path("data");
        if (!tickets.isArray()) return 0;
        int ok = 0;
        for (int i = 0; i < tickets.size() && i < chunk.size(); i++) {
            JsonNode ticket = tickets.get(i);
            if ("ok".equals(ticket.path("status").asText())) {
                ok++;
                continue;
            }
            String error = ticket.path("details").path("error").asText("");
            if ("DeviceNotRegistered".equals(error)) {
                dead.add(chunk.get(i).to());
            } else {
                log.warn("[push] ticket error {} — {}", error, ticket.path("message").asText(""));
            }
        }
        return ok;
    }
}
```

- [ ] **Step 4: Test the sender itself, not just the batching**

`DropAlertPushTest` above exercises only the static `batch()`, and Step 7's fan-out test mocks `ExpoPushSender` entirely — so between them **nothing ever runs `send()`**, and `readTickets` is where the dead-token logic lives. Changing `ticket.path("details").path("error")` to `ticket.path("error")`, or a casing mismatch on `DeviceNotRegistered`, would make `deadTokens` permanently empty and the registry would never prune — while every proposed test stayed green, because the fan-out test *stubs* the Result.

Create `src/test/java/com/imin/iminapi/push/ExpoPushSenderTest.java` using `MockRestServiceServer` against the `RestClient.Builder`. Cover:

- A canned Expo response mixing `{"status":"ok"}`, `details.error = DeviceNotRegistered`, and `details.error = MessageRateExceeded` → `accepted` counts only the ok, `deadTokens` contains **only** the DeviceNotRegistered token (a rate-limited device is alive and must not be revoked).
- `enabled=false` → `Result.NONE` and **zero** HTTP calls (`server.verify()` with no expectations).
- A non-2xx response → `Result.NONE`, no exception thrown.
- A `data` array shorter than the chunk → no `IndexOutOfBoundsException`.
- The access token, when set, arrives as `Authorization: Bearer …`; when blank, no such header.

Run: `./mvnw test -Dtest='DropAlertPushTest,ExpoPushSenderTest'`
Expected: PASS

- [ ] **Step 5: Add the config block**

In `src/main/resources/application.yaml`, under `imin:`:

```yaml
  push:
    enabled: ${IMIN_PUSH_ENABLED:false}
    base-url: ${EXPO_PUSH_BASE_URL:https://exp.host/--/api/v2/push/send}
    access-token: ${EXPO_ACCESS_TOKEN:}
    timeout-seconds: ${IMIN_PUSH_TIMEOUT_SECONDS:10}
```

`IminApiApplication` is a bare `@SpringBootApplication` with **no** `@ConfigurationPropertiesScan`, so the properties class will not bind on its own. Create `src/main/java/com/imin/iminapi/push/PushConfig.java`, matching `oauth/OAuthConfig.java:14`:

```java
package com.imin.iminapi.push;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {}
```

- [ ] **Step 6: Fan out from NotifyReleaseSender**

In `src/main/java/com/imin/iminapi/service/event/NotifyReleaseSender.java`, at the end of `notifySubscribers(Event event)` — **after** the existing email loop and its `mark(sub)` calls — add:

```java
        pushToAccountHolders(event, pending);
```

and add the method:

```java
    /**
     * Best-effort push alongside the drop-alert email.
     *
     * <p><b>Deliberately after the email loop and outside its try/catch.</b>
     * {@code mark(sub)} is the one-shot delivery marker and it is driven by the
     * email alone; letting a push failure influence it would either re-send a
     * mail somebody already has or suppress one they are owed. Push is the
     * enhancement, email is the promise.
     *
     * <p>Only account holders are reachable: {@code notify_subscriptions} is
     * keyed by email and works for guests, while a device belongs to a signed-in
     * buyer. The join goes through <b>verified</b> addresses only — the same
     * boundary {@code GET /buyer/orders} uses, because an unverified row is a
     * claim anybody can make about any address.
     */
    private void pushToAccountHolders(Event event, List<NotifySubscription> pending) {
        if (!pushProps.isEnabled()) return;
        try {
            List<UUID> accountIds = pending.stream()
                    .map(s -> normalize(s.getEmail()))
                    .distinct()
                    .map(buyerEmails::findByVerifiedKey)
                    .flatMap(Optional::stream)
                    .map(BuyerAccountEmail::getBuyerAccountId)
                    .distinct()
                    .filter(this::pushOptedIn)
                    .toList();
            if (accountIds.isEmpty()) return;

            List<String> tokens = pushDevices.findLiveTokensForAccounts(accountIds);
            if (tokens.isEmpty()) return;

            String title = "Tickets are live";
            String body = eventName(event);
            List<PushMessage> messages = tokens.stream()
                    .map(t -> new PushMessage(t, title, body, PushMessage.CHANNEL_DROP_ALERTS,
                            Map.of("type", "drop-alert", "eventId", event.getId().toString())))
                    .toList();

            ExpoPushSender.Result result = push.send(messages);
            if (!result.deadTokens().isEmpty()) {
                // Uninstalled apps never tell us they are gone; this is the only
                // signal, so acting on it is what keeps the registry from rotting.
                pushDevices.revokeByTokens(result.deadTokens(), clock.instant());
            }
        } catch (Exception e) {
            log.warn("NotifyReleaseSender: push fan-out failed for event {} — {}",
                    event.getId(), e.getMessage());
        }
    }

    /** Absent preference row means defaults, and the default is on. */
    private boolean pushOptedIn(UUID accountId) {
        return pushPrefs.findById(accountId)
                .map(BuyerNotificationPreference::isPushDropAlerts)
                .orElse(true);
    }
```

Inject `PushProperties pushProps`, `ExpoPushSender push`, `BuyerPushDeviceRepository pushDevices`, `BuyerAccountEmailRepository buyerEmails`, `BuyerNotificationPreferenceRepository pushPrefs` into the constructor, and add the corresponding imports.

> **Do NOT put `@Transactional` on `pushToAccountHolders`.** It is private and reached by self-invocation, which proxy-based AOP never intercepts — `NotifyReleaseSender` has no transaction boundary anywhere, so the `@Modifying` UPDATE would throw `TransactionRequiredException`, and the blanket `catch (Exception e) { log.warn(...) }` above would swallow it, leaving dead tokens in the registry forever with only a warn line to show for it.
>
> Put the boundary on the repository method instead — annotate `BuyerPushDeviceRepository.revokeByTokens` with `@Transactional`, which is the pattern `PromoCodeRepository:29-34` documents for exactly this trap. Alternatively move the revoke into `BuyerPushDeviceService` as a `@Transactional public void`.

- [ ] **Step 7: Add the fan-out integration test**

Create `src/test/java/com/imin/iminapi/push/DropAlertFanOutTest.java`:

```java
package com.imin.iminapi.push;

import com.imin.iminapi.buyer.service.BuyerPushDeviceService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.service.event.NotifyReleaseSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Push rides the drop-alert release alongside the email, and never instead of
 * it. The failing case this exists to catch: a buyer with a device gets the
 * push but silently loses the email, or gets it twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = "imin.push.enabled=true")
@org.springframework.transaction.annotation.Transactional   // fixtures must not leak
class DropAlertFanOutTest extends com.imin.iminapi.buyer.NativeBuyerTestBase {

    private static final String TOKEN = "ExponentPushToken[fanout0000000000000000]";

    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired BuyerPushDeviceService devices;
    @MockitoBean ExpoPushSender push;
    // NOTE: mvc and the EmailService mock are inherited from NativeBuyerTestBase.
    // Re-declaring @MockitoBean EmailService here breaks context startup —
    // Spring collects @BeanOverride fields across the hierarchy, and two
    // by-type handlers with the same type and field name compare equal, which
    // trips the registry's Assert.state.

    @Test
    void anAccountHolderWithADeviceGetsBothAPushAndTheEmail() throws Exception {
        when(push.send(anyList())).thenReturn(new ExpoPushSender.Result(1, Set.of()));

        String bearer = signUpAndSignInNative();
        UUID accountId = accountIdOf(bearer);
        String address = primaryAddressOf(bearer);
        devices.register(accountId, TOKEN, "ios", "en", "1.0.0");

        Event event = releasableEventWatchedBy(address);
        releaseSender.sweep();

        ArgumentCaptor<List<PushMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(push).send(sent.capture());
        assertThat(sent.getValue()).hasSize(1);
        assertThat(sent.getValue().get(0).to()).isEqualTo(TOKEN);
        assertThat(sent.getValue().get(0).data())
                .containsEntry("eventId", event.getId().toString());

        // The email is the promise; push must not have replaced it.
        verify(email).send(org.mockito.ArgumentMatchers.eq(address),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());

        // And the one-shot marker was set exactly once.
        NotifySubscription row = subscriptions.findByEmailIn(List.of(address)).get(0);
        assertThat(row.getNotifiedAt()).isNotNull();
    }

    @Test
    void aGuestWatcherGetsTheEmailAndNoPushIsAttempted() throws Exception {
        String guest = "guest-" + UUID.randomUUID() + "@example.test";
        releasableEventWatchedBy(guest);

        releaseSender.sweep();

        verify(email).send(org.mockito.ArgumentMatchers.eq(guest),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(push, never()).send(anyList());
    }

    @Test
    void aDeadTokenIsRevokedSoItIsNeverSentToAgain() throws Exception {
        when(push.send(anyList())).thenReturn(new ExpoPushSender.Result(0, Set.of(TOKEN)));

        String bearer = signUpAndSignInNative();
        devices.register(accountIdOf(bearer), TOKEN, "ios", "en", "1.0.0");
        releasableEventWatchedBy(primaryAddressOf(bearer));

        releaseSender.sweep();

        assertThat(pushDevices.findLiveTokensForAccounts(List.of(accountIdOf(bearer)))).isEmpty();
    }

    @Autowired com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository pushDevices;
}
```

> **Implementer notes — read before writing this test; three hazards.**
>
> 1. **Do not `@Autowired` the sender.** `NotifyReleaseSender.sweep()` is proxied by `@SchedulerLock(lockAtLeastFor = "PT10S")` (`SchedulingConfig:27-29`), so the second and third sweeps inside one 10-second window are *skipped* and `verify(push).send(...)` fails with zero interactions. Construct the bean explicitly with a fixed `Clock` and mocked collaborators and call `sweep()` directly, exactly as the existing `NotifyReleaseSenderTest:44-65` does — read that file first; its Javadoc documents both this and the next hazard.
> 2. **`@EnableScheduling` is active in tests** with no `@Profile` guard and no override in the test yaml, so the background dispatcher can fire `sweep()` on the same rows and steal the `notifiedAt` marks out from under the assertions.
> 3. **Add `@Transactional`** or the fixtures leak into sibling test classes sharing the context.
>
> Also: the entry point is **`sweep()`**, not `run()` — fix that in the code block, not only here. And add `primaryAddressOf(String bearer)` to `NativeBuyerTestBase` (reads `emails[0].email` off `GET /buyer/me`), plus a `releasableEventWatchedBy(String email)` helper built from whatever fixture `BuyerDropAlertsTest` already uses rather than a new one.
>
> Add a fourth case in a second class annotated `@TestPropertySource(properties = "imin.push.enabled=false")` asserting `verify(push, never()).send(anyList())` even with a device registered — the disabled path must be dark, not merely quiet.

- [ ] **Step 7b: Repair `NotifyReleaseSenderTest`, which this task breaks**

`src/test/java/com/imin/iminapi/service/event/NotifyReleaseSenderTest.java:84-85` constructs the bean by hand with the current **eight** collaborators:

```java
new NotifyReleaseSender(subscriptions, events, tiers, suppressions, emailService, renderer, emailProps, CLOCK)
```

Step 6 adds five more. That is a **compile error**, and a compile error fails the whole test module — so Steps 8, 9 and 10 below cannot run at all, and the eight tests protecting the email promise go offline silently. The file appears in neither this task's Files list nor its `git add`.

Update `setUp()` to pass the five new collaborators — Mockito mocks for `ExpoPushSender`, `BuyerPushDeviceRepository`, `BuyerAccountEmailRepository` and `BuyerNotificationPreferenceRepository`, plus a real `PushProperties` with `enabled = false`. Then add one assertion to an existing case:

```java
        // Push must be dark unless explicitly enabled, and must never be a
        // precondition for the email these tests exist to protect.
        verify(push, never()).send(anyList());
```

Add the file to this task's Files list and to the Step 11 `git add`.

- [ ] **Step 8: Run the tests**

Run: `./mvnw test -Dtest=DropAlertPushTest`
Expected: PASS

- [ ] **Step 9: Run the notify suite**

Run: `./mvnw test -Dtest='*Notify*,*DropAlert*'`
Expected: PASS — the email path must be byte-identical.

- [ ] **Step 10: Full suite**

Run: `./mvnw test`
Expected: PASS, all ~1200 tests.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/imin/iminapi/push/ \
        src/main/java/com/imin/iminapi/service/event/NotifyReleaseSender.java \
        src/main/java/com/imin/iminapi/buyer/repository/BuyerPushDeviceRepository.java \
        src/main/resources/application.yaml \
        src/test/java/com/imin/iminapi/push/DropAlertPushTest.java \
        src/test/java/com/imin/iminapi/push/ExpoPushSenderTest.java \
        src/test/java/com/imin/iminapi/push/DropAlertFanOutTest.java \
        src/test/java/com/imin/iminapi/service/event/NotifyReleaseSenderTest.java \
        src/test/java/com/imin/iminapi/buyer/NativeBuyerTestBase.java
git commit -m "feat(push): drop-alert push notifications via Expo"
```

---

---

## Task 8: One-way doors — things that cannot be added after v1.0.0 ships

Everything here is cheap now and either impossible or permanently half-broken later, because **a shipped app binary cannot be force-updated**. Each item states its own expiry.

**Files:**
- Create: `src/main/java/com/imin/iminapi/controller/publicapi/AppConfigController.java`
- Create: `src/main/java/com/imin/iminapi/app/AppReleaseProperties.java`, `src/main/java/com/imin/iminapi/app/AppVersions.java`, `src/main/java/com/imin/iminapi/app/AppConfig.java`
- Create: `src/main/resources/db/migration/V93__native_client_fields.sql`
- Modify: `src/main/java/com/imin/iminapi/stripe/StripePaymentIntentController.java` — accept `Idempotency-Key`
- Modify: `src/main/java/com/imin/iminapi/stripe/StripePaymentIntentService.java` — replay on a repeat key
- Modify: `src/main/java/com/imin/iminapi/model/TicketReservation.java`
- Modify: `src/main/java/com/imin/iminapi/buyer/controller/BuyerSavedController.java:45`, `BuyerNotifySubscriptionController.java:54`, `BuyerPreferencesController.java:54`
- Modify: `src/main/java/com/imin/iminapi/dto/publicapi/TrackRequest.java` + the funnel-beacon persistence
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/com/imin/iminapi/app/AppVersionsTest.java`, `.../AppConfigControllerTest.java`
- Test: `src/test/java/com/imin/iminapi/stripe/PaymentIntentIdempotencyTest.java`

**Interfaces:**
- Produces: `GET /api/v1/public/app-config`; `AppVersions.compare(String, String) : int`; `POST /public/events/{id}/payment-intent` honouring `Idempotency-Key`; `{items, nextCursor}` envelopes on three buyer endpoints; `TrackRequest.client`.
- Consumes: Task 3's `StripePaymentIntentService`, `TicketReservation`.

---

- [ ] **Step 1: Semver comparison, tested first**

Lexical comparison is the trap: `"1.10.0".compareTo("1.9.0")` is negative, so a `String`-compared gate would treat 1.10.0 as older than 1.9.0 and lock out the newest install. Write this before the endpoint.

Create `src/test/java/com/imin/iminapi/app/AppVersionsTest.java`:

```java
package com.imin.iminapi.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionsTest {

    @Test
    void comparesNumericallyNotLexically() {
        // The whole reason this class exists.
        assertThat(AppVersions.compare("1.10.0", "1.9.0")).isPositive();
        assertThat(AppVersions.compare("1.9.0", "1.10.0")).isNegative();
        assertThat(AppVersions.compare("2.0.0", "1.99.99")).isPositive();
    }

    @Test
    void equalVersionsCompareEqual() {
        assertThat(AppVersions.compare("1.2.3", "1.2.3")).isZero();
    }

    @Test
    void missingSegmentsReadAsZero() {
        assertThat(AppVersions.compare("1.2", "1.2.0")).isZero();
        assertThat(AppVersions.compare("1.3", "1.2.9")).isPositive();
    }

    @Test
    void junkNeverLocksAnybodyOut() {
        // An unparseable version must fail OPEN. A crash or a "too old" verdict
        // here bricks the app for everyone whose header we failed to read.
        assertThat(AppVersions.isAtLeast(null, "1.0.0")).isTrue();
        assertThat(AppVersions.isAtLeast("", "1.0.0")).isTrue();
        assertThat(AppVersions.isAtLeast("not-a-version", "1.0.0")).isTrue();
        assertThat(AppVersions.isAtLeast("1.2.3-beta.1", "1.2.3")).isTrue();
    }
}
```

Then `src/main/java/com/imin/iminapi/app/AppVersions.java`:

```java
package com.imin.iminapi.app;

/**
 * Dotted-numeric version comparison for the mobile force-upgrade gate.
 *
 * <p>Not {@code String.compareTo}: "1.10.0" sorts <i>below</i> "1.9.0"
 * lexically, which would lock out the newest build in the field.
 *
 * <p><b>Everything unparseable fails open.</b> A version we cannot read is a
 * version we must not block — the alternative is bricking an install over a
 * malformed header, and there is no way to push a fix to a blocked client.
 */
public final class AppVersions {

    private AppVersions() {}

    public static boolean isAtLeast(String actual, String required) {
        if (actual == null || actual.isBlank() || required == null || required.isBlank()) return true;
        try {
            return compare(actual, required) >= 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Negative / zero / positive, comparing dotted numeric segments left to right. */
    public static int compare(String a, String b) {
        String[] left = core(a);
        String[] right = core(b);
        int n = Math.max(left.length, right.length);
        for (int i = 0; i < n; i++) {
            int l = segment(left, i);
            int r = segment(right, i);
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    /** Drops any pre-release or build suffix: "1.2.3-beta.1" → "1.2.3". */
    private static String[] core(String v) {
        String s = v.trim();
        int cut = s.indexOf('-');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.indexOf('+');
        if (cut >= 0) s = s.substring(0, cut);
        return s.split("\\.");
    }

    private static int segment(String[] parts, int i) {
        if (i >= parts.length) return 0;      // "1.2" and "1.2.0" are the same version
        return Integer.parseInt(parts[i].trim());
    }
}
```

Run: `./mvnw test -Dtest=AppVersionsTest` → PASS (4 tests). Commit.

- [ ] **Step 2: The app-config endpoint**

*Why it cannot wait:* this is the only true one-way door in the plan. If v1.0.0 ships with no version check, **those installs can never be gated off** — and EAS Update cannot fix a native module or a breaking contract change. Everything else in this task is expensive-later; this one is impossible-later.

`AppReleaseProperties` binds `imin.app.*` so a version bump is a Railway env edit, not a deploy:

```yaml
  app:
    ios:
      min-supported-version: ${IMIN_APP_IOS_MIN_VERSION:0.0.0}
      latest-version: ${IMIN_APP_IOS_LATEST_VERSION:0.0.0}
      store-url: ${IMIN_APP_IOS_STORE_URL:}
    android:
      min-supported-version: ${IMIN_APP_ANDROID_MIN_VERSION:0.0.0}
      latest-version: ${IMIN_APP_ANDROID_LATEST_VERSION:0.0.0}
      store-url: ${IMIN_APP_ANDROID_STORE_URL:}
```

Defaults of `0.0.0` mean "gate off" — the safe state, since no build is ever below it.

`GET /api/v1/public/app-config?platform=ios&version=1.2.3` returns:

```json
{
  "status": "ok",
  "minSupportedVersion": "1.0.0",
  "latestVersion": "1.4.0",
  "storeUrl": "https://apps.apple.com/app/id...",
  "cities": [...],
  "genres": [...],
  "flags": {}
}
```

`status` is `ok` / `update_recommended` (below latest) / `update_required` (below min). **Fold in `cities` and `genres`** — both already served with `public, s-maxage=60` by `PublicEventController:50-62` — so a cold launch is one round trip instead of three.

`SecurityConfig:127` already permitAlls `GET /api/v1/public/**`, so there is no security rule to add.

**Specify a companion `X-Imin-App-Version` header** for every request, and do **not** overload `X-Imin-Client`: `BuyerClientKind.isNative` is an exact `equalsIgnoreCase("native")` match, so a value like `native/1.2.3` would silently stop being recognised and every native mutation would start 403ing.

Test `AppConfigControllerTest`: below-min → `update_required`; between min and latest → `update_recommended`; at latest → `ok`; unknown platform → 400; **absent version → `ok`** (fail open); and that `cities` is present.

- [ ] **Step 3: `Idempotency-Key` on the PaymentIntent endpoint**

*Why it cannot wait:* `InventoryService.reserve` writes a new hold on **every** call, before Stripe is touched. A native client on a flaky link that loses the response and retries mints a second reservation *and* a second PaymentIntent — holding real seats on the hot tier for 30 minutes. The web never needed this because a hosted-Checkout redirect is a navigation, not a retriable `fetch`. Added later, it protects only newer binaries; the launch cohort keeps the duplicate-hold behaviour forever.

The house already has the pattern — read `RefundService:76-87`, `CampaignService:328`, `PayoutController:72`, and `ErrorCode.MISSING_IDEMPOTENCY_KEY`.

`V93__native_client_fields.sql`:

```sql
-- V93__native_client_fields.sql
-- Idempotency for the native checkout, and a client label on the funnel beacon.

-- A native client retries on a dropped connection; the web never could, because
-- its checkout was a browser navigation. Without this, a retry mints a second
-- 30-minute hold on real inventory AND a second PaymentIntent.
ALTER TABLE ticket_reservations ADD COLUMN idempotency_key VARCHAR(128) NULL;
CREATE UNIQUE INDEX uq_ticket_reservations_idem ON ticket_reservations (idempotency_key);

-- Which client the funnel event came from. NULL = web, so every existing row
-- keeps its current meaning and no backfill is needed.
ALTER TABLE event_funnel_events ADD COLUMN client VARCHAR(16) NULL;
```

> A unique index over a nullable column permits many NULLs on both PostgreSQL and H2, which is exactly what is wanted — only keyed requests participate.

In the controller, read the optional `Idempotency-Key` header and pass it through. In the service, **before** pricing: if the key is present and a reservation already carries it, return the **stored** PaymentIntent id and the **stored** amount. Never recompute the amount on replay — a tier price change between the original call and the retry would otherwise charge a different total than the one the buyer confirmed.

Tests: same key twice → one reservation, one `paymentIntents().create` call, identical response; different keys → two of each; no key → current behaviour unchanged; and a replay after a price change returns the **original** amount.

- [ ] **Step 4: Envelope the three bare-array buyer endpoints**

*Why it cannot wait:* a top-level JSON **array** cannot grow a cursor later without becoming an object — a breaking change for every shipped binary. Today the only consumer is a Vercel deploy that redeploys alongside the API, so the change is free exactly once.

`GET /buyer/saved` (`BuyerSavedController:45`), `GET /buyer/notify-subscriptions` (`BuyerNotifySubscriptionController:54`) and `GET /buyer/organizers` (`BuyerPreferencesController:54`) each return a bare array. Wrap them as `{items, nextCursor}`, copying `BuyerOrdersResponse`'s existing contract rather than inventing a second one. `nextCursor` may be null for now — the shape is the point.

**Leave `/buyer/emails` and `/buyer/identities` as arrays.** Both are bounded by human behaviour and will never need a cursor.

Ship the matching `imin-public` PR in the same pair — this is a contract change, and per the standing rule the two repos move together.

- [ ] **Step 5: Label the funnel beacon with its client**

*Why it cannot wait:* `/api/v1/analytics/attribution` and the funnel are **live in production** and organizer-facing. Without a label the app either sends no beacons (a silent under-count that grows with app adoption) or merges indistinguishably into web "direct" — quietly making shipped organizer conversion numbers wrong. Under the no-fabricated-data rule that is not an acceptable interim state.

Add a nullable `client` to `TrackRequest` (`"web"` / `"ios"` / `"android"`; null reads as web), persist it to the new column, and add an optional `client` filter to `/api/v1/analytics/attribution`.

**Do not overload `utm_source`** — the shipped auto-tag feature already writes that field, and colliding with it would corrupt live campaign attribution.

- [ ] **Step 6: Full suite, then commit**

Run: `./mvnw test`
Expected: PASS.

```bash
git add src/main/java/com/imin/iminapi/app/ \
        src/main/java/com/imin/iminapi/controller/publicapi/AppConfigController.java \
        src/main/resources/db/migration/V93__native_client_fields.sql \
        src/main/java/com/imin/iminapi/stripe/ \
        src/main/java/com/imin/iminapi/model/TicketReservation.java \
        src/main/java/com/imin/iminapi/buyer/controller/ \
        src/main/java/com/imin/iminapi/dto/publicapi/TrackRequest.java \
        src/main/resources/application.yaml \
        src/test/java/com/imin/iminapi/app/ \
        src/test/java/com/imin/iminapi/stripe/PaymentIntentIdempotencyTest.java
git commit -m "feat(app): version gate, checkout idempotency, list envelopes and client-labelled beacons"
```

## Deployment and follow-through

- [ ] **Merge to `master` and let Railway deploy.** Direct push to the default branch needs explicit user OK.
- [ ] **Set the new production env vars** before or with the deploy:
  - `GOOGLE_OAUTH_NATIVE_AUDIENCE` — **must be set**, and to the Google **web** client id, which is what both native apps pass as `serverClientId`. Unlike the other gates this one has no safe inherit: `nativeEnabled()` is false while it is blank, so native Google sign-in 404s until it is configured
  - `APPLE_OAUTH_NATIVE_AUDIENCE` — the iOS bundle identifier (**not** the web Services ID in `apple.client-id`). Blank ⇒ native Apple sign-in 404s and the app cannot pass App Store review (Guideline 4.8)
  - `IMIN_APP_IOS_MIN_VERSION` / `IMIN_APP_IOS_LATEST_VERSION` / `IMIN_APP_IOS_STORE_URL` and the Android trio — leave at the `0.0.0` default (gate off) until v1.0.0 is in review
  - `IMIN_PUSH_ENABLED` — leave `false` until the app has real tokens
  - `EXPO_ACCESS_TOKEN` — set if Expo enhanced security is on
  - `SPRING_FLYWAY_OUT_OF_ORDER=true` is already permanent on Railway; V92 is in order anyway
- [ ] **After the deploy is live**, run `npm run api:sync` in `imin-webapp` — `api:fetch` curls the production OpenAPI URL, so FE type regeneration only reflects this once Railway has it. Reconcile `src/shared/api/types.ts` against the regenerated `generated-types.ts`.
- [ ] **Update `imin-public/docs/PUBLIC_PAGE_API.md`** with the `kind`/`sessionId` fields on the checkout response and the new `/payment-intent` endpoint. That doc is authoritative for the buyer contract and must move with the controller. (Note: the copy under `imin-api` is a stale fork — edit the `imin-public` one.)

## Known gaps, recorded rather than silently skipped

- **Push has no rate limit.** The fan-out is triggered by an organizer putting tickets on sale, not by buyer input, so there is no user-facing abuse surface — but a mis-scheduled tier flapping in and out of sale would re-notify. `NotifySubscriptionService` already re-arms rows on re-release, which is the existing behaviour for email; push inherits it.
- **Wallet passes are not in this plan.** Separate plan; nothing here blocks them, and they are genuinely parallelisable — no shared code, credentials or data model.
- **The identity registry is on the critical path, not after it.** Tasks 4, 5 and 6 cannot be configured or end-to-end tested without the iOS bundle id, the Android package, the Google web client id and an EAS `projectId`. Apple Developer enrolment can take a week. **Start that today, in parallel with Task 1.**
- **The app still cannot build several handoff screens after this plan.** The review lists them in full; the ones that need net-new backend are: `GET /buyer/tickets` (the Tickets tab and QR overlay — `BuyerOrdersResponse` deliberately omits `qrPayload` and ticket tokens), saved-event hydration (the Saved tab would be one request per card on a cell network), and the Notifications screen's SMS column, which can never light up because no SMS dispatcher exists. Each is v1-app scope, not Phase 0.
- **`getting_home`, `sober_friendly` and the refund-protection toggle have no backing field anywhere.** They are prototype-only. Under the no-fabricated-data rule they must be cut from the app or specced as real products — not rendered as decoration.
- **Push fan-out runs inline on the scheduler.** `NotifyReleaseSender.sweep()` is one of 25 `@Scheduled` methods sharing a **pool of 1**. The timeout added in Task 7 bounds the damage; moving the fan-out to its own executor is the follow-up if push volume grows.
- **DSAR export omits push devices.** Erasure is covered by `ON DELETE CASCADE`; export is not. A device identifier is personal data.
- **Expo receipt polling is not implemented.** Only immediate ticket errors prune dead tokens.
- **`IMIN_REMINDERS_ENABLED` is still `false`.** T-24h/T-3h door reminders stay dark on both channels. ~90% of that feature is already written, including four locales of template — enabling it is v1-app scope, and the first sweep after the flag flips mails everyone with a ticket in the next 24 hours, so pick the hour deliberately.
