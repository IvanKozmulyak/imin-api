# Review — Mobile Phase 0 backend plan
`/Users/ivan/imin/imin-api/docs/superpowers/plans/2026-08-15-mobile-phase0-backend.md` · reviewed 2026-08-15 · all claims verified against source

---

## 1. Verdict

**Execute after 18 specific fixes** — 2 blockers, 16 majors, plus 12 one-line minors. Do not start Task 3 or Task 7 as written.

The plan's architecture is right: additive-only, one header for client kind, shared prelude so hosted and native cannot drift on money or inventory, push deliberately scoped to drop alerts. Tasks 1, 2, 4, 5 and 6 are structurally sound and mostly need surgical corrections. The two blockers are both in Task 3 and both are *money* defects — a native buyer can be charged and never receive a ticket, and even when fulfilment works the app has no way to find the order it just paid for. Task 7's test design is the weakest section in the plan: every one of its three risky mechanisms (Expo transport, dead-token pruning, the fan-out itself) is mocked away or unreachable, and one existing test file it does not mention will fail compilation of the whole test module.

Nothing here requires rework of the approach. Fix the 18, add the 5 items in §5, and the plan is executable.

---

## 2. Must fix before executing

Ranked: production-correctness first, then things that stop the plan executing at all, then test integrity.

### 2.1 — BLOCKER · Buyer email is dropped on the native path → charged buyer, no ticket, webhook retries forever
**Where:** plan Task 3 Step 4 (lines 990–992) · `src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java:129-133, 205-245`

`PaidCheckoutService.resolveBuyerAndSession` resolves the buyer address from exactly two sources: `readChargeEmail(pi)` → `charge.getBillingDetails().getEmail()` (:233-245), and the Checkout Session listed by PaymentIntent id (:210-226). A natively-created PaymentIntent has **no Checkout Session**, so the second source is structurally empty; Stripe's PaymentSheet does not populate `billing_details.email` by default for card or Apple Pay, so the first is usually empty too. The plan's only email handling is `builder.setReceiptEmail(...)`, and `grep -rn "ReceiptEmail\|receipt_email" src/main/java` returns **zero hits** — nothing reads that field. When both come up empty, line 130 throws `IllegalStateException("Could not resolve buyer email for PI … — webhook will be retried by Stripe")` and every retry fails identically.

This is the exact "paid buyer with no ticket" the plan's own Critical-invariant paragraph (line 712) claims to guard against. The invariant misses it because it audits only metadata *keys*, and the hosted path carries the address on the Session (`StripeCheckoutService.java:383-385` `setCustomerEmail`) rather than in metadata.

**Edit:** in Task 3 Step 1, add `metadata.put("buyer_email", email)` and `metadata.put("client", "native")` to the shared metadata map inside `preparePaid` (additive and harmless for hosted). In `PaidCheckoutService.resolveBuyerAndSession`, fall back to `meta.get("buyer_email")` then `pi.getReceiptEmail()` when charge and session lookups fail, and skip the `checkout().sessions().list(...)` round trip entirely when `metadata.client == "native"`. Add a test: a PI with no charge billing email and an empty session list still issues an Order.

### 2.2 — BLOCKER · No path from PaymentIntent to the order → the Success screen dead-ends for guests
**Where:** plan Task 3 `NativeIntent` (lines 950–951, 1019–1020) · `src/main/java/com/imin/iminapi/service/ticket/CheckoutStatusService.java:36`

After the PaymentSheet succeeds the app holds only `paymentIntentId`. `CheckoutStatusService.statusFor` contains exactly one resolver — `orders.findByStripeSessionId(sessionId)` — and the class has no other method. `PaidCheckoutService:146` sets `order.setStripeSessionId(resolved.sessionId)`, which is **null** for a natively-created PI. So the order token is unobtainable. The only other guest entry point is `PublicRecoveryController` (`POST /api/v1/public/orders/recover`, an email round trip). Guest checkout is the headline flow (handoff README:21) and the Success screen renders "View my tickets →" (`dc.html:354`).

`OrderRepository.java:21` already declares `findByStripePaymentIntentId` and nothing calls it.

**Edit:** add a step to Task 3 widening `CheckoutStatusService.statusFor` to try `findByStripePaymentIntentId` for `pi_`-prefixed ids (keep one endpoint, `GET /api/v1/public/checkout/{id}`, dispatching on prefix or simply trying both). While there, make the unused `Status.FAILED` arm real for terminally failed/canceled intents so the app stops polling. Document the polling contract beside Task 3 and in `imin-public/docs/PUBLIC_PAGE_API.md`.

### 2.3 — MAJOR · The native PaymentIntent has no deadline tied to the 30-minute hold → a swept reservation stays payable
**Where:** plan Task 3 Step 4 (lines 975–1017) · `StripeCheckoutService.java:253, :368` · `InventoryService.java:210-233` · `ReservationSweeper`

On the hosted path the hold and the payability window are literally the same instant: `Instant expiresAt = clock.instant().plus(Duration.ofMinutes(props.getCheckoutSessionTtlMinutes()))` (:253) feeds both the reservation row and `.setExpiresAt(...)` on the Session (:368), so once the sweeper returns the seats, the Session can no longer be paid. The plan's `PaymentIntentCreateParams` builder sets amount, currency, application fee, transfer data, transfer group, metadata and automatic payment methods — **and no deadline** — while inheriting the same 30-minute reservation, and nothing cancels the intent when the sweeper releases it. `InventoryService.confirmSold` on a `RELEASED` row deliberately credits `sold` anyway and logs `[OVERSOLD]`, so a late confirm becomes a real, fully-charged, fully-transferred oversold sale. Mint N intents on a hot tier, wait out the TTL, let the seats resell, then confirm.

Severity is major, not blocker: the attacker pays full price, and the outcome is an alertable `[OVERSOLD]` log rather than a silent loss. What is genuinely new versus the hosted path is the *unbounded* window.

**Edit:** the plan already calls `inventoryService.attachSessionId(p.reservationId(), intent.getId())`, so the reservation row carries the `pi_…` id. Have `ReservationSweeper` (and `StripeWebhookService.onPaymentIntentFailed`) call `stripeClient.paymentIntents().cancel(...)` for any released reservation whose stored id starts with `pi_`. Add a test that releases the reservation and then delivers `payment_intent.succeeded`; none of Step 2's four cases touches the late-confirm path.

### 2.4 — MAJOR · The `X-Imin-Client: native` gate is page-controllable, so same-origin JS can mint a portable 180-day token
**Where:** plan Task 1 Steps 3/6/7 (lines 336–339, 452–479, 481–513) · `SecurityConfig.java:64-70` · `application.yaml:317` · `BuyerSessionCookie.java:79-85`

Emission is predicated on `BuyerClientKind.isNative(http)` — a bare header read. The buyer CORS config allows `https://app.imin.wtf` as an exact origin with `allowedHeaders("*")` and `allowCredentials(true)`, so a script on app.imin.wtf can set that header on any buyer request; hooking the page's own `fetch` on the sign-in screen turns a normal password login into a JSON response carrying the raw token. That token is the same opaque credential the cookie carries, valid **180 days absolute / 90 idle**, usable off-device with no cookie, Origin or SameSite constraint. The plan's own Javadoc for the new field asserts the opposite invariant ("A browser gets null here … handing the raw token to page JavaScript would give up the whole point of the cookie") — mechanism and stated intent disagree.

Not a blocker: the precondition is script execution on app.imin.wtf, under which permanent takeover is already reachable (`POST /buyer/emails` → verify with an attacker-controlled inbox → `forgot-password` to that now-verified address). The bearer token adds portability and silent persistence, and is still killed by `revoke-all` and by any password change.

**Edit:** gate emission on something a browser structurally cannot produce. app.imin.wtf → api.imin.wtf is cross-origin, so a browser always attaches `Origin` and a native HTTP client never does:
```java
BuyerClientKind.isNative(http)
    && http.getHeader(HttpHeaders.ORIGIN) == null
    && BuyerSessionCookie.read(http) == null
```
Drop the `googleCallback` change from Step 7 entirely — native Google goes through Task 4's `/buyer/auth/google/native`, so the browser callback has no native caller and emitting there is pure attack surface. Add a test: `X-Imin-Client: native` **plus** an `Origin` header gets no `sessionToken`.

### 2.5 — MAJOR · "A native client has no cookie jar" is false for React Native → every native mutation 403s
**Where:** plan Task 1 Step 3 (`isCookielessNative`), Step 4, Step 7 prose · `BuyerRequestGuardFilter.java:84-89` · `BuyerSessionCookie.java:80-84`

The CSRF exemption requires the native header **AND** no session cookie, while sign-in emits `Set-Cookie` unconditionally, including to native clients. React Native's `fetch` runs on NSURLSession / OkHttp with the platform cookie store enabled by default, so an Expo app that signs in also stores `imin_buyer_session` (host-only, `Path=/api/v1/buyer`, `Secure` — all satisfied in production) and replays it forever after. `isCookielessNative` then returns false, the guard demands an `Origin` the app does not send, and logout, push-device register/delete and preference updates all 403. The four proposed tests cover native-without-cookie and cookie-plus-header-without-bearer; none sends a bearer and a cookie together, and nothing pins the documented precedence.

**Edit:** stop emitting `Set-Cookie` on native sign-ins — that makes the plan's premise true by construction — and assert `header().doesNotExist("Set-Cookie")` on the native login. Add `bearerPlusCookieStillAuthenticatesAsTheBearerIdentity` (A's bearer + B's cookie → `GET /buyer/me` returns A) and a test pinning the intended behaviour of a native mutation carrying both.

### 2.6 — MAJOR · `DELETE /buyer/push-devices/{expoToken}` puts `[` and `]` in a path segment
**Where:** plan Task 6 Step 5 (line 2232) and its tests (lines 1898, 1934, 1950) · `pom.xml:73`

An Expo token is literally `ExponentPushToken[…]`. `[` and `]` are gen-delims, illegal unencoded in a path segment, and are exactly two of the characters `server.tomcat.relaxed-path-chars` exists to re-admit — `grep -rn "relaxed" src/main/resources/` matches only an unrelated comment in `V73__pacing_curves.sql`. `pom.xml:73` is `spring-boot-starter-webmvc` with no Jetty/Undertow substitution, so the connector rejects the request with 400 before Spring sees it. The WHATWG URL percent-encode set for paths does not include brackets either, so a `fetch()` built by concatenation sends them raw. MockMvc builds a `MockHttpServletRequest` directly and never runs the container's URI parser — the plan's tests go green while every production sign-out 400s and the device keeps receiving pushes.

**Edit:** move the token off the path — `POST /api/v1/buyer/push-devices/revoke` with `{"expoToken": "…"}` in the body. Same shape as register, and it keeps the credential out of access logs.

### 2.7 — MAJOR · Task 4's native Google endpoint is gated on a web-only redirect URI → 2 of its 3 tests 404
**Where:** plan Task 4 Step 6 (line 1391 `requireGoogleEnabled();`) · `BuyerAuthController.java:198-203` · `BuyerOAuthService.java:127-129` · `OAuthProperties.java:97-99`

`requireGoogleEnabled()` → `googleAuth.enabled()` → `props.getGoogle().isBuyerEnabled()` → `notBlank(clientId) && notBlank(clientSecret) && notBlank(buyerRedirectUri)`. Native Google sign-in has no redirect URI at all — the plan says so itself at line 1155. Semantically the wrong switch, and operationally fatal in tests: `src/test/resources/application.yaml` has no `imin.oauth` block and the new test sets no `@TestPropertySource`, so all three are blank and the endpoint answers 404 `OAUTH_PROVIDER_DISABLED`. `verifiedIdTokenSignsInAndReturnsASessionToken` (expects 200) and `unverifiedGoogleEmailIsRejected` (expects 409) both fail; only `blankIdTokenIs400` passes, because `@Valid` fires first. Step 8's "Expected: PASS (3 tests)" is impossible. It is also wrong in production: unset `GOOGLE_OAUTH_BUYER_REDIRECT_URI` would 404 native sign-in.

**Edit:** give the native lane its own gate — `notBlank(props.getGoogle().getNativeAudience())`, mirroring `AppleNativeIdentityService.enabled()` which the plan already designs that way at lines 1675-1678. Add `@TestPropertySource(properties = "imin.oauth.google.native-audience=test-native-aud")` to the test, plus a fourth case asserting a blank native-audience yields 404 so the gate itself is proven.

### 2.8 — MAJOR · Task 7's constructor widening breaks `NotifyReleaseSenderTest`, which the task never mentions
**Where:** plan Task 7 Step 6 (line 2654), Step 9, Step 11 (lines 2799–2805) · `src/test/java/com/imin/iminapi/service/event/NotifyReleaseSenderTest.java:84-85`

That test builds the bean by hand: `new NotifyReleaseSender(subscriptions, events, tiers, suppressions, emailService, renderer, emailProps, CLOCK)` — the current 8 parameters (`NotifyReleaseSender.java:73-80`). Adding five is a compile error, which fails the whole test module: Step 8 (`-Dtest=DropAlertPushTest`), Step 9 (`-Dtest='*Notify*,*DropAlert*'`, "Expected: PASS") and Step 10 (full suite) cannot run, and the 8 tests protecting the email promise go offline. The file appears in neither the Files list nor the Step 11 `git add`.

**Edit:** add an explicit step updating `setUp()` to pass the five new collaborators (Mockito mocks plus a `PushProperties` with `enabled=false`), assert `verify(push, never()).send(anyList())` in at least one existing case, and add the file to both the Files list and the Step 11 `git add`.

### 2.9 — MAJOR · `EventFixtures` and `StripeTestDoubles` do not exist, and both named fallbacks are unusable
**Where:** plan Task 3 Step 2 (`@Autowired EventFixtures fixtures;`, `@Import({TestRateLimitConfig.class, StripeTestDoubles.class})`) and the implementer note at line 862

Neither type exists (`ls src/test/java/com/imin/iminapi/support/` → `OrderFixtures.java` only; `grep -rn 'EventFixtures|StripeTestDoubles|publishedPaidEvent|firstTier' src/test/java` → nothing). The note redirects to `OrderFixtures`, a final class with a private constructor and only static methods — there is no analogue for `@Autowired fixtures.publishedPaidEvent(2500)` — and its `event(...)` creates an Organization with no `stripeAccountId` and an Event with **no tiers at all**, so `firstTier(event)` has nothing to return and the readiness gate at `StripeCheckoutService.java:231-239` would 404 every case. The note's other instruction — a `@TestConfiguration` providing a `StripeClient` `@Bean` — collides with `StripeConfig.java:26-27`, and `spring.main.allow-bean-definition-overriding` is set nowhere, so it throws `BeanDefinitionOverrideException`. The house pattern is `@MockitoBean StripeClient` (`PaidCheckoutServiceTest:58`, `SettlementIngestWebhookTest:59`, `PostEventPayoutServiceTest:152`).

**Edit:** write `NativePaymentIntentTest` as a plain Mockito test mirroring `StripeCheckoutServiceTest` (which also makes 2.11's captor assertions trivial). If a Spring test is wanted anyway, specify concretely: a new static `CheckoutFixtures` creating an org with `stripeAccountId` and a tier with `stripePriceId` and an open sale window, `@MockitoBean StripeClient`, and `@MockitoBean StripeConnectService` stubbed `readyToReceivePayments()`.

### 2.10 — MAJOR · `DropAlertFanOutTest` autowires the scheduled bean → ShedLock no-ops, the live dispatcher races it, fixtures leak
**Where:** plan Task 7 Step 7 · `SchedulingConfig.java:27-29` · `NotifyReleaseSender.java:91-93` · `NotifyReleaseSenderTest.java:44-65`

Three problems at once. (1) The bean is proxied by `@SchedulerLock(lockAtLeastFor = "PT10S")`, so the second and third tests' sweeps inside the same 10-second window are skipped and `verify(push).send(...)` fails with zero interactions. (2) `@EnableScheduling` is active in tests with no `@Profile` guard and no override in the test yaml, so the background dispatcher can fire `sweep()` on the same rows and steal the `notifiedAt` marks. (3) No `@Transactional`, so fixtures leak into sibling classes sharing the context. The existing `NotifyReleaseSenderTest` documents both hazards in its Javadoc and avoids them deliberately.

**Edit:** mirror `NotifyReleaseSenderTest` — add `@Transactional`, construct the sender explicitly with a fixed `Clock` and mocked collaborators, call `sweep()` directly (not through the proxy). Also fix `releaseSender.run()` → `sweep()` in the code block itself, not just the note.

### 2.11 — MAJOR · Task 3's tests never capture `PaymentIntentCreateParams`, so the plan's own "critical invariant" is unenforced
**Where:** plan Task 3 Step 2 (lines 757–859) vs `src/test/java/com/imin/iminapi/stripe/StripeCheckoutServiceTest.java:170-265`

All four proposed tests assert only the JSON response (clientSecret, paymentIntentId, amountMinor, feeMinor, currency). Nothing captures what is handed to the mocked `StripeClient`, so dropping `.putAllMetadata(p.metadata())`, `.setApplicationFeeAmount`, `.setTransferData(destination)` or `.setTransferGroup` leaves all four green. The hosted suite already does this properly: `ArgumentCaptor<SessionCreateParams>` with `containsEntry("reservation_id", …)`, `tier_id`, `qty`, `event_id`, mirrored onto `getPaymentIntentData().getMetadata()`. The prelude extraction protects metadata *construction*; nothing protects its *attachment* on the new path.

**Edit:** add `ArgumentCaptor<PaymentIntentCreateParams>` + `verify(...).create(captor.capture())` asserting the metadata map contains reservation_id, tier_id, qty, event_id, ads_consent, marketing_opt_in, buyer_locale, promo_id and (per 2.1) buyer_email, plus `getApplicationFeeAmount()`, `getTransferData().getDestination()` and `getTransferGroup()`.

### 2.12 — MAJOR · No promo/discount case anywhere in Task 3 — the one place native and hosted money math genuinely diverge
**Where:** plan Task 3 Step 2 and Step 4 (`long amount = p.netTotalMinor() + p.applicationFee();`) · `QuoteService.java:92-96` · `StripeCheckoutService.java:268-273`

The only tested case is the no-promo case, where the two paths trivially agree. The plan's own "two real differences" section says the hosted path expresses a discount as a Stripe Coupon scoped to the ticket product while the native path must subtract it from the amount and still compute the fee on the **undiscounted** subtotal (`StripeCheckoutService.java:268-273`, with that exact comment). Writing `p.subtotalMinor() + p.applicationFee()` or `computeFee(netTotal, …)` leaves every proposed test green. `grep -n promo StripeCheckoutServiceTest.java` → no matches, so the hosted suite does not cover it either.

**Edit:** add a promo case — 2 × €25.00 with 20% off → discount 1000, netTotal 4000, fee **448** (still on 5000), amountMinor 4448; assert all three plus `metadata.promo_id` on the captured params, and a second assertion pinning `feeMinor` identical with and without the promo. (Test config pins `application-fee-bps: 500`, `application-fee-fixed-minor: 99` at `src/test/resources/application.yaml:53-54`.)

### 2.13 — MAJOR · `ExpoPushSender.send`/`readTickets` are never executed — the dead-token pruning logic is entirely uncovered
**Where:** plan Task 7 Step 1 (`DropAlertPushTest` calls only the static `batch()`) and Step 7 (`@MockitoBean ExpoPushSender push;`)

Between the two, nothing runs `send()`. Untested: the `props.isEnabled()` short-circuit, the JSON payload shape, the `Authorization: Bearer` header, and `readTickets` — which is where the dead-token logic lives. Changing `ticket.path("details").path("error")` to `ticket.path("error")`, or a case mismatch on `DeviceNotRegistered`, makes `deadTokens` permanently empty and the registry never prunes, while `aDeadTokenIsRevokedSoItIsNeverSentToAgain` still passes because it *stubs* `new ExpoPushSender.Result(0, Set.of(TOKEN))`.

**Edit:** add an `ExpoPushSenderTest` on `MockRestServiceServer` with a canned Expo response mixing `{"status":"ok"}`, `details.error = DeviceNotRegistered` and `details.error = MessageRateExceeded`; assert accepted count, that only the DeviceNotRegistered token is revoked, plus `enabled=false` (no HTTP call), a non-2xx response (`Result.NONE`, no throw), and a `data` array shorter than the chunk.

### 2.14 — MAJOR · `ExpoPushSender` declares `timeoutSeconds` and never uses it — an unbounded blocking POST on the single `@Scheduled` thread
**Where:** plan Task 7 Step 3 (line 2477 `this.http = builder.build()`), `PushProperties.timeoutSeconds` (lines 2388–2400), yaml key at line 2579 · call site at line 2589

`getTimeoutSeconds()` appears nowhere in the class body — field, getter, setter and yaml key only, so an operator tuning it changes nothing. There is no global default to fall back on (`grep -rn "read-timeout|connect-timeout|requestFactory" src/main/resources/application*.yaml src/main/java` → nothing). The call runs inline on `NotifyReleaseSender.sweep()`, a `@Scheduled` method on Spring's default scheduler, which is **pool size 1** because `spring.task.scheduling.pool.size` is unset. A hung connection to exp.host therefore stalls all **25** `@Scheduled` methods in this repo — including `ReservationSweeper`, the thing that releases stale inventory holds — and outlives `lockAtMostFor = "PT5M"`, letting a second replica re-enter the sweep.

**Edit:** build the client from the property — `builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().withConnectTimeout(...).withReadTimeout(...))` — and add a `DropAlertPushTest` case proving a slow endpoint fails fast. If the fan-out stays inline on the scheduler, record in §Known gaps that it shares one thread with 24 other jobs.

### 2.15 — MAJOR · `@MockitoBean AppleNativeIdentityService` mocks away the exact assertion Task 5 exists to protect
**Where:** plan Task 5 Step 1 (line 1502) vs Step 5 (`AppleNativeIdentityService.verify`, lines ~1685–1716)

Task 5's stated reason to exist is that `AppleOAuthService` hardcodes `emailVerified = true`, which would make the buyer gate a permanent no-op. The test then replaces the whole service with a mock whose stubs return `emailVerified = true` — reproducing the shortcut the class was written to avoid. Implementing `verify` as `new OAuthUserInfo(PROVIDER_APPLE, subject, email, true, …)` keeps all three tests green. Nothing exercises boolean-vs-`"true"`-string parsing, missing-claim → false, null/blank subject → 401, or `email_verified=false` → 409 (`BuyerOAuthService.java:181-185`).

**Edit:** add a plain (non-Spring) `AppleNativeIdentityServiceTest` driving `verify(...)` over five claim sets (boolean true, string `"true"`, boolean false, claim absent, blank `sub`), with `OidcJwtVerifier` behind a constructor-injectable seam. Keep the MockMvc test for HTTP wiring only.

### 2.16 — MAJOR · The reset-password link imin-api emails is a 404 on the buyer site today
**Where:** `src/main/java/com/imin/iminapi/buyer/email/BuyerAccountEmailer.java:47-54` · sender at `BuyerCredentialService.java:280` · `/Users/ivan/imin/imin-public/app/auth/`

`resetUrl` builds `https://app.imin.wtf/auth/reset-password?token=…`, but imin-public serves that page at `/auth/reset` (`app/auth/reset/page.tsx:20` documents itself as `/auth/reset?token=…`). There is no `redirects`/`rewrites` in `next.config.ts`, no `middleware.ts`, no `vercel.json`. The buyer password-reset email lands on a 404 **in production right now**. `signInUrl()` has the same defect: `/auth/login` where the route is `/auth/sign-in`. The handoff's signed-out account screen offers "Forgot password" as the only recovery route (README:32), so the app inherits a broken flow, and this is backend-owned code.

**Edit:** fix both URLs in `BuyerAccountEmailer` as part of this plan (or add the redirects in imin-public and ship them lockstep). The in-app-completion question (universal links vs. a short code like verify-email) can stay a recorded gap.

---

## 3. Fix while you are in there

- **Task 3 Step 1 boundary is self-contradictory.** "Move lines 223-340" cannot produce `PaidPrelude`'s `event`/`tier`/`promo`/`subtotalMinor`/`discountMinor`/`netTotalMinor` (computed at 167–192) and *would* sweep in the hosted-only `SessionCreateParams` builder at 279–300. Restate by content: `preparePaid` = 156–192 + 223–276 + 302–340 minus `createOneShotCoupon`; the free branch (194–221), the session builder (278–300) and 342–431 stay in `createCheckout`.
- **The zero-net guard is unreachable and its test asserts the wrong status.** A free tier hits `notFound("Event")` at :223 (blank `stripePriceId`) or :231-239 (readiness gate) *inside* `preparePaid`, so `freeTotalIsRejectedAndPointedAtTheCheckoutEndpoint` gets 404, not 400. Split the extraction into a side-effect-free `priceIt(...)` and a `reserveAndBuildMetadata(...)`; throw the 400 after `priceIt`, before anything is reserved or any org gate runs. That also removes the phantom hold the plan's `releaseReservation("FREE_TOTAL_ON_NATIVE_PATH")` exists to clean up.
- **Google `email_verified` parsing regresses.** Plan line 1331 uses `claims.getBooleanClaim("email_verified")`, which throws `ParseException` on a string-typed claim and the plan's catch maps that to 401. Use `asBool(claims.getClaim("email_verified"))` — already private-static in the same class at `GoogleOAuthService.java:187-190` and used by the web path at :141. The plan's own Apple verifier does this correctly.
- **`@Transactional` on the private `pushToAccountHolders` cannot work** (plan line 2656). `NotifyReleaseSender` has no transaction boundary anywhere, and the method is private and reached by self-invocation, which proxy AOP never intercepts. Put `@Transactional` on `BuyerPushDeviceRepository.revokeByTokens` itself — the pattern `PromoCodeRepository.java:29-34` documents for exactly this trap — or move the revoke into `BuyerPushDeviceService` as a `@Transactional public void`. Otherwise the `@Modifying` UPDATE throws and the plan's blanket `catch (Exception e) { log.warn(...) }` (lines 2640–2643) swallows it, leaving dead tokens forever.
- **Task 5's prerequisite check greps for the wrong thing.** `PROVIDER` (`BuyerOAuthService.java:88`) is indeed absent from `resolve`, but line 205 files every new address as `ADDED_VIA_GOOGLE`, line 184's 409 tells an Apple user "Google has not verified this email address", and 196/211 log "google identity linked" / "google sign-up created buyer account". `added_via` is surfaced via `BuyerMeResponse.java:51`, so an Apple relay reports `addedVia: "google"` into the table that feeds DSAR export. Add `BuyerAccountEmail.ADDED_VIA_APPLE = "apple"` (`V83:51` is `VARCHAR(16) NOT NULL` with no CHECK — no migration needed), select from `info.provider()`, make the message and logs provider-neutral, and rewrite the prerequisite to name `ADDED_VIA_GOOGLE` and the 409 string.
- **Task 3's controller omits `@Valid`** (line 1079), so `PaymentIntentRequest`'s `@NotNull`/`@Min`/`@Max` (lines 1104–1115) never run — Spring only cascades into a `@RequestBody` when the parameter carries it. Add `@Valid`, and keep the service-side quantity guard.
- **Three `$.code` assertions must be `$.error.code`** (plan lines 857, 1257, 1564). `ApiError.java:7,17` wraps the body in `error`; `$.error.code` appears 106 times across the test suite and `$.code` zero times. Also assert `$.error.message` contains the discriminating substring.
- **Three factual references are wrong.** The method at `StripeCheckoutService.java:152` takes **10** arguments, not 11 (Task 2 line 646 vs Task 3 line 724 contradict each other); there are **five** existing overloads (108, 113, 119, 126, 134), not four; and `QuoteService.computeFee` is `(long, int, int, int)`, not `(long, int, int, long)`.
- **`@GeneratedValue` on `BuyerPushDevice.id`** (plan lines 2020–2022) omits the strategy that all four sibling buyer entities specify. Write `@GeneratedValue(strategy = GenerationType.UUID)`.
- **`NativeBuyerTestBase` + duplicate `@MockitoBean EmailService email`** in `BuyerPushDeviceTest` and `DropAlertFanOutTest` fails context startup — Spring collects `@BeanOverride` fields across the hierarchy and two by-type handlers with the same type and field name are equal, tripping the registry's `Assert.state`. Have the base own `protected MockMvc mvc` and the `@MockitoBean EmailService`, and delete both subclass declarations.
- **Make the `PushProperties` wiring unconditional.** Plan line 2582 says "check `IminApiApplication` for `@ConfigurationPropertiesScan`; if present, no wiring is needed" — it is not present (`IminApiApplication` is a bare `@SpringBootApplication`). State it outright: create `push/PushConfig.java` with `@EnableConfigurationProperties(PushProperties.class)`, matching `oauth/OAuthConfig.java:14`, and add it to Task 7's Files and `git add`.
- **Free checkout still makes the client slice a token out of a URL.** Task 2 adds `sessionId` for the stripe branch but leaves the order branch carrying its identifier only inside `https://app.imin.wtf/order/{token}` (`FreeCheckoutService.java:157-160`). Add a nullable `orderToken` to `CheckoutResult`/`CheckoutResponse`, populated from `order.getToken()` on the free path. Two of the six events in the handoff fixture data are free.

---

## 4. Gaps: what the app still cannot build after Phase 0

| Design surface (handoff) | What is missing | Needs |
|---|---|---|
| **Tickets tab / QR overlay** — "offline-cached note", per-ticket paging | `BuyerOrdersResponse` deliberately omits `qrPayload` and ticket tokens (its own Javadoc); `qrPayload` exists only on `PublicOrderResponse.Ticket` and `PublicTicketResponse`. The app must N+1 through `/public/orders/{token}` and persist an order bearer credential per order. | `GET /api/v1/buyer/tickets` (§6). Good news: `QrPayloadSigner` signs `HMAC(secret, "v1|"+token)` with **no expiry**, so a cached payload never goes stale. |
| **Success → "View my tickets"** for guests | No PI-keyed order lookup. | Fixed by §2.2. |
| **Free-ticket checkout** | Order token only inside a web URL. | Fixed by §3 (`orderToken` on `CheckoutResponse`). |
| **Saved tab** | `BuyerSavedResponse` is `(eventId, savedAt)` only, by explicit design note. One request per saved event on a cell network. | Batch `?ids=` on `GET /api/v1/public/events`, or inline name/slug/startsAt/posterUrl — `BuyerNotifySubscriptionController` already does exactly that for Drop alerts. |
| **Notifications screen** (Email/SMS matrix) | SMS column can never light up — `marketing/send/` has one sender, `MarketingChannelsService` states there is no SMS dispatcher; SMS billing is an open blocker. And the design has **no push column** at all. | Product decision + design: replace the SMS column with push, or ship the matrix with SMS visibly disabled. Backend push preference (`push_drop_alerts`) does land in Task 6. |
| **Event page: "How to get home"** | Hardcoded prose in the prototype (`dc.html:250`). No column, no field. `grep` for `getting_home` across `src/main/java` and all migrations → nothing. | Organizer-authored `getting_home` text + an updated-at stamp, or cut it. Under the no-fabricated-data rule it cannot ship as-is. |
| **Sober-friendly filter + proof chip** (`dc.html:156, :214`) | No `sober_friendly` column, no filter on `PublicEventListQuery`. | Boolean on `events` + filter + organizer form field in imin-webapp, or cut. |
| **Discover "Nearby — Nancy — 45 min" empty state** (`dc.html:963-965`) | `events.venue_latitude/longitude` exist (V80) and are on `PublicEventResponse`, but not on `PublicEventListItem`, there is no geo parameter, and `EventRepository` has no distance query. Geocoding is disabled. | Radius query + `IMIN_GEOCODING_ENABLED` + a backfill run (Nominatim ~1 req/s). High fabrication risk while coordinates are NULL. |
| **Checkout: "Refund protection · €X/ticket"** (`dc.html:308, :320`) | No backing field anywhere in imin-api — and imin-public does not implement it either. | Prototype-only concept. Cut it from the app, or spec it as a real product (it changes the amount math and the receipt). |
| **Account: password reset completion** | Backend emails a 404 URL (§2.16); no in-app completion path. | Fix the URL now; decide universal-link vs. six-digit-code completion for the app. |
| **Event page status pill: "Cancelled"** | `EventStatus.CANCELLED` is read in six places and **written in none** — `EventService` only sets LIVE (:261) and DRAFT (:300), and `unpublish` refuses once tickets are sold, pointing at a cancel-and-refund capability that does not exist. No cancellation email template exists. | Post-launch: cancel action + refund orchestration + notification. On a phone this is the highest-stakes notification there is. |
| **Wallet CTAs on the ticket card** | Deliberately a separate plan. `.pkpass` endpoint exists (`PublicTicketAssetController:52`) and 503s on unset `APPLE_WALLET_*`; Google Wallet pass class is net-new. | Ship the Wallet plan in parallel — it shares no code with this one. Note the per-ticket flag is `PublicTicketResponse.walletAvailable`. |

---

## 5. Add to Phase 0

Five items, each because it is materially cheaper now than after a binary is in a store.

1. **`GET /api/v1/public/app-config?platform=&version=`** → `{status: ok|update_recommended|update_required, minSupportedVersion, latestVersion, storeUrl, flags{…}}`, env-driven so a version bump is a Railway env edit. Fold in `cities` and `genres` (both already `public, s-maxage=60` at `PublicEventController:50-62`) to kill two cold-launch round trips. Spec a companion `X-Imin-App-Version` header rather than overloading `X-Imin-Client` (whose parser is an exact `equalsIgnoreCase("native")` match, so `native/1.2.3` would silently stop being recognised).
   *Why now:* this is the only truly one-way door in the lane — if v1.0.0 ships without the check, those installs can never be gated off, and EAS Update cannot fix a native module or a contract break. `SecurityConfig.java:127` already permitAlls `GET /api/v1/public/**`, so there is no security change. Make the comparison semver-aware, not `String.compareTo` — "1.10.0" sorts below "1.9.0" lexically; that is the one thing to unit-test.
2. **`Idempotency-Key` on `POST /payment-intent`.** `InventoryService.reserve` writes a new hold on every call, before Stripe is touched — a native client on a flaky link that loses the response and retries mints a second reservation *and* a second PaymentIntent, holding real seats for 30 minutes on the hot tier. The web never needed this because a hosted-Checkout redirect is a navigation, not a retriable fetch. The house already has the pattern (`ErrorCode.MISSING_IDEMPOTENCY_KEY`, `RefundService:76-87`, `CampaignService:328`, `PayoutController:72`). *Why now:* adding it later only protects newer binaries; the launch cohort keeps the duplicate-hold behaviour forever. Store the key on `ticket_reservations` (nullable column + unique index, no partial index), replay the stored PI id from `stripe_session_id`, and return the **stored** amount — never recompute.
3. **Envelope the three bare-array buyer list endpoints** — `GET /buyer/saved` (`BuyerSavedController:45`), `/buyer/notify-subscriptions` (`BuyerNotifySubscriptionController:54`), `/buyer/organizers` (`BuyerPreferencesController:54`) — into `{items, nextCursor}`, copying `BuyerOrdersResponse`'s contract. *Why now:* a top-level JSON **array** cannot grow a cursor later without becoming an object, which breaks every shipped binary. Today the only consumer is a Vercel deploy that redeploys with the API. Ship lockstep with the imin-public PR. Leave `/buyer/emails` and `/buyer/identities` as arrays — bounded by human behaviour.
4. **Make the push channel a field on `PushMessage`** instead of the hardcoded `"drop-alerts"` (plan line 2522), with the intended set declared as constants (`drop-alerts`, `tickets`, `reminders`, `organizer-updates`). *Why now:* Android notification channels are created **by the app binary**. Any second notification type shipped later either rides the drop-alerts channel — mislabelled in the user's own settings, and silenced with it — or targets a channel the launch cohort never created. Ten backend lines buys the whole future taxonomy.
5. **Add a nullable `client` component to `TrackRequest`** (`"web"|"ios"|"android"`, null = web) and the matching column on `event_funnel_events`, then teach `/api/v1/analytics/attribution` to filter on it. *Why now:* the funnel and attribution surfaces are live in prod. Without a label the app either sends nothing (silent under-count as app volume grows) or merges indistinguishably into web "direct" and quietly makes organizer-facing conversion numbers wrong. Do **not** overload `utm_source` — the shipped auto-tag feature writes that field.

---

## 6. v1 app scope (backend work that lands with the app, not before it)

**Tickets & offline**
- `GET /api/v1/buyer/tickets` — one authenticated, `private, no-store`, `hasRole("BUYER")` call returning per-ticket token, `qrPayload` (via `QrPayloadSigner`), state, tier name, plus event startsAt/endsAt/timezone and the **full** venue address (street/postalCode/country — `BuyerOrdersResponse.Event` carries only venueName+venueCity, so a calendar entry or maps deep link written today would be partial). Scope to upcoming events; this response is a bundle of admission credentials and must inherit `/buyer/orders`' rules exactly.
- Door-scan confirmation push + honest ticket state: `TicketRedeemService.java:67` already publishes `TicketRedeemedEvent` AFTER_COMMIT and `AudienceRedeemProjector` already listens — a second listener is nearly free. Add `redeemedAt` to `PublicOrderResponse.Ticket`. Copy must say "a ticket on your order was scanned", not "you're in".

**Discovery & sharing**
- Saved-tab hydration (batch `?ids=` or inline fields) — apply the feed's eligibility predicate and silently drop ineligible ids.
- Canonical `shareUrl` on `PublicEventResponse`/`PublicEventListItem` + a reserved `utm_source=imin-app&utm_medium=share`; the existing beacon and `/analytics/attribution` ingest it for free. Do not invent a referral credit.
- "Near me": `venueLatitude/Longitude` on the list item + `lat/lng/radiusKm` bounding-box + Haversine ordering. Gated on `IMIN_GEOCODING_ENABLED` and a real backfill; events with NULL coordinates must be excluded, never estimated.

**Notifications**
- Turn on the built-and-dark T-24h/T-3h reminders (`EventReminderSender`, gated at `application.yaml:194`) and add a push fan-out beside the email, with a `push_event_reminders` preference column. ~90% is already written, including four locales of template. Enable deliberately at a chosen hour — the first sweep after flipping the flag mails everyone with a ticket in the next 24 hours.

**Cold start**
- `GET /api/v1/buyer/bootstrap` aggregating `me` + `saved` + `preferences` + first page of `orders` and `notify-subscriptions`. Build it once the screens are real — it is purely additive and costs the same in month three.

---

## 7. Post-launch backlog

- Event cancellation end-to-end (cancel action → refund orchestration → email ×4 locales + push). Sequence the refund path first.
- Push as an organizer marketing channel, routed through `SendGateService`/`ConsentService`/`SuppressionService`/quiet hours — replaces the SMS column that can never light up. Requires Expo receipt polling first.
- Buyer "your nightlife" history from `MembershipProjector` data (spend, nights, first/last). Derive genres from the buyer's own orders — `Membership.genres` is declared and never written. Never surface `noShow`.
- Cursor pagination on the public event feed — additive later, offset drift is tolerable at current volume.
- Device-session listing + per-session revoke — explicitly ruled out by the handoff ("no device/session management"); `revoke-all` already ships; the schema and repository methods already exist, so it is an afternoon whenever the screen is drawn.
- Ticket-delivery push (the app is foregrounded after the in-app PaymentSheet; polling covers it) and Expo receipt polling.
- DSAR export to include push devices (erasure is already covered by cascade).
- Organizer-authored `getting_home` / `sober_friendly` fields, if the product wants those screens.
- **No work needed:** structured error codes are already complete — `ErrorCode` is a 50-value enum, `GlobalExceptionHandler` maps every path, and `SecurityConfig.java:197-217` installs JSON 401/403 handlers including `AUTH_TOKEN_EXPIRED`. Optional cosmetic follow-up: document the enum in OpenAPI so `api:sync` can generate a typed union.

---

## 8. App repo Phase 1 — starting brief for `imin-fan-app`

**Do these three before any app code, in this order:**

1. **Identity registry (blocks Phase 0 Tasks 4, 5, 6 — not the reverse).** Register and record in one ADR: iOS bundle id + Android package (propose `wtf.imin.fan`), an Apple Developer team + App ID with Sign in with Apple, Google Cloud OAuth clients (iOS, Android, **and the web client both natives pass as `serverClientId`**), the Expo org + EAS `projectId`, the Play Console entry. Hand the backend three values: `GOOGLE_OAUTH_NATIVE_AUDIENCE` (= the web client id — Tasks 4/5 each model audience as a *single* String, which is only correct under this configuration), `APPLE_OAUTH_NATIVE_AUDIENCE` (= the bundle id, **not** the web Services ID in `apple.client-id`), and the Expo access token. ~2 days, mostly waiting on Apple; enrolment can take a week.
2. **One-week de-risking spike, on real devices, then delete the branch.** Prove: (a) `@stripe/stripe-react-native` PaymentSheet renders a genuine Apple Pay sheet against a `client_secret` from the new endpoint, in TestFlight, with imin's destination-charge + `application_fee_amount` shape; (b) `expo-notifications` → token → `POST /buyer/push-devices` → a push arriving on both an iPhone and an Android device; (c) `new Date(iso).toLocaleTimeString('en-GB', {timeZone: 'Europe/Paris'})` on a physical Android Hermes build across a DST boundary; (d) `eas build --profile development` with pnpm. Deliverable is four written answers.
3. **Intl decision.** Default: `@formatjs/intl-locale` + `intl-pluralrules` + `intl-numberformat` + `intl-datetimeformat` with `add-all-tz.js`, imported at the top of the root layout before any app code, so `imin-public/lib/format.ts` (263 LOC, 14 `timeZone`-pinned call sites) ports **verbatim**. Hermes without full ICU silently *ignores* the `timeZone` option — the failure mode is a plausible wrong door time, not a crash.

**Then the scaffold:**
- Expo SDK pinned exactly, custom dev client (Expo Go is off the table once Stripe enters), `expo-router` with typed routes, TS `strict`, **pnpm with `node-linker=hoisted`** (Metro does not handle pnpm's symlinked store), `app.config.ts` so bundle ids and API base come from env per EAS profile. Config plugins from day one: secure-store, notifications, brightness, keep-awake, calendar, linking, screen-orientation, blur, linear-gradient, stripe-react-native, sentry. Write a `CLAUDE.md` shaped like `imin-tickets-gate/CLAUDE.md`.
- **i18n scope: EN/ES/FR only** (Ukrainian confirmed out). This deletes the RN font-fallback problem entirely — Barlow Condensed has no Cyrillic cut, which was the *only* reason a per-locale font map was required. Bundle all three families as one static `expo-font` map. Give the app its own dictionaries rather than porting `imin-public/lib/i18n/en.ts` (1,359 LOC keyed to 27 web routes the app will not have); keep `core.ts`'s primitives and key-shape discipline so `check:i18n` ports. The standing lockstep rule applies *within* the app.
- **Copy the portable logic with a provenance ledger — do not build a shared package.** There is no root git repo, no root package manager, and no `.github` in any existing repo; a shared package needs machinery that does not exist. Copy `lib/events/feed-query.ts`, `lib/events/sections.ts`, `lib/format.ts`, `lib/locale.ts`, `lib/api/types.ts`, and the *algorithm* of `lib/saved-events.ts` (~1,500 LOC) with a header naming origin file + upstream sha, a `docs/PORTED.md`, and a `pnpm check:ported` drift script. Adopt imin-webapp's `api:sync` (prod OpenAPI → `generated-types.ts` for drift detection only).
- **Generate the theme, don't transcribe it.** `scripts/sync-tokens.mjs` parses `imin-public/app/globals.css` `:root`, resolves `color-mix()` to literal hex at build time and **fails loudly** on anything it cannot resolve — there are 96 `color-mix()` calls and 21 `backdrop-filter` uses. This also protects the two traps documented in the CSS itself (`--text3` must stay `#8a82a6`, not the kit's `#5f5b70`; `--accent2` is a gradient stop, not a text colour). Budget the composition primitives RN does not get free (Surface, Hairline, Blur, GradientText, TicketStub, a `clamp()` replacement) and the four kit components absent from `imin-public/components/ui/` (LiveDot, Modal, Pillar, StatCard). Measure `expo-blur` on a mid-range Android before 21 blur surfaces ship.
- **Session storage and the one rule that matters.** Token in `expo-secure-store` with `WHEN_UNLOCKED_THIS_DEVICE_ONLY` — it is a 180-day non-refreshing bearer (`session-ttl-days: 180`, `session-idle-days: 90`, and `BuyerSessionService` documents "no rotation, because there is nothing to rotate"). One `lib/api/client.ts` chokepoint sets `X-Imin-Client: native` and `X-Imin-App-Version`, attaches the bearer, and splits `ApiError`/`NetworkError`. Port the three-state session model (`unknown`/`signed-in`/`signed-out`) from `components/buyer/buyer-session.tsx`. **A 401 clears the session token and must NOT clear the offline ticket vault** — `/public/orders/{token}` and `/public/tickets/{token}` are permitAll and keyed by their own tokens, so "signed out" must never mean "no ticket".
- **Offline vault as its own store, not a persisted query cache.** TanStack Query for network state with *no* global persistence; separately `react-native-mmkv` encrypted with a key in SecureStore, holding ticket token, `qrPayload`, state-as-of, tier, event name/startsAt/timezone/venue. QR rendered locally by `react-native-qrcode-svg` — never fetched as an image, or the QR screen requires network. This mirrors `imin-public/public/sw.js`, whose header forbids serving inventory from cache for exactly this reason.
- **Decide what a cached ticket may claim.** `QrPayloadSigner` has no timestamp and no state, so a cached payload for a refunded ticket scans and is correctly rejected at the door. A cached QR shows the payload plus "Checked 14:02" and never a green "Valid" badge. Get copy signed off by the designer — the handoff has no concept of this.
- **Route parity + universal links.** Mirror imin-public's URL space 1:1 in expo-router (`/e/[id]`, `/e/[id]/buy`, `/e/[id]/success`, `/order/[token]`, `/tickets/[token]`, `/events`, `/cities`, `/saved`, `/profile/*`, `/recover`, `/refund/[token]`), with `(tabs)` for Discover/Saved/Tickets/Profile. Then a PR **in imin-public** adding `app/.well-known/apple-app-site-association/route.ts` and `assetlinks.json/route.ts` — precedent exists at `app/.well-known/apple-developer-merchantid-domain-association/route.ts`. Android's fingerprint must be the EAS-managed **release** keystore's. Discard the prototype's navigation model outright (`dc.html:864` drives everything off `state.screen` + a one-level `backTo`, with no URL and no deep-link surface).
- **Push client:** never prompt on launch. Prompt once, immediately after a "Notify me when tickets drop" tap, and only when signed in. Register after grant, re-register on every sign-in, `DELETE` on sign-out. When a **signed-out** buyer taps notify-me, still create the email subscription and say so. The designer must draw the third (push) column of the Notifications matrix before this is coded.
- **Testing:** `jest-expo` + `@testing-library/react-native` (accept the divergence from imin-tickets-gate's vitest openly), weighted toward the ported pure logic and the quote state machine. Maestro for three flows: sign-in → tickets → QR; guest checkout with a test card; cold-start deep link. Two non-negotiables: a venue-local-time regression test, and a documented manual airplane-mode door test on real hardware before every release.
- **CI/CD:** GitHub Actions (the workspace's first) running lint/typecheck/test on PRs; `eas build --profile preview` on merge; `eas update --branch production` **manual dispatch only** — EAS Update has no review gate. `runtimeVersion: { policy: "appVersion" }`. Sentry with `sendDefaultPii: false`, replay off, and a `beforeSend` scrubbing `qrPayload`, `orderToken`, `sessionToken`, `clientSecret`, `expoToken` from bodies and breadcrumbs.
- **Store readiness:** declare Contact Info / Purchases / Identifiers / Diagnostics; ship **no** Meta SDK, no IDFA, no ATT prompt; mirror in Play Data Safety; EN/ES/FR assets. Write into CLAUDE.md that EAS Update is not a way to ship features around review.

---

## 9. Revised effort

The plan's ~2.5–3.5 weeks is the cost of typing what is written. It does not include the fixes, and it does not include the five Phase-0 additions.

| Component | Effort |
|---|---|
| Plan as written (7 tasks) | ~14–15 dev-days |
| §2 blockers + majors (mostly test rewrites: 2.9 and 2.10 are near-total rewrites of two test classes; 2.11–2.13 add three new suites) | ~6 dev-days |
| §3 minors | ~1 dev-day |
| §5 additions (app-config + version header 1d; idempotency 1.5d; list envelopes 1d incl. the imin-public lockstep PR; push channel field 0.25d; beacon `client` field 0.5d) | ~4.5 dev-days |
| Deploy, `api:sync` reconciliation, `PUBLIC_PAGE_API.md` | ~0.5 dev-day |
| **Total Phase 0** | **~26 dev-days ≈ 5 weeks** (range 4.5–6 weeks, one engineer, sequential) |

That lands squarely on `STACK-DECISION.md`'s original **4–6 weeks BE** estimate, which the plan's 2.5–3.5 undershot. Two structural notes on scheduling: Wallet passes are genuinely parallelisable (no shared code, credentials or data model), and the identity-registry work in §8 is on the critical path *for Phase 0*, not after it — Tasks 4, 5 and 6 cannot be configured or end-to-end tested without the bundle id, the Google web client id and an EAS `projectId`. Start it today.

---

## Unverified — do not plan against

- **Stripe PaymentSheet's default `billing_details.email` behaviour** for card and Apple Pay. Verified: nothing in this repo reads `receipt_email`, and the session lookup is structurally empty for a native PI. The §2.1 fix is correct regardless of what the SDK does.
- **Tomcat's rejection of unencoded `[`/`]`** is documented behaviour and `relaxed-path-chars` is confirmed unset here, but no request was actually executed against the connector. Prove it with a real-servlet test if the path form is kept anyway.
- **spring-test's `BeanOverrideHandler` equality semantics** (§3, duplicate `@MockitoBean EmailService`) were read from library sources, not executed.
- **React Native's cookie-store default** on NSURLSession/OkHttp (§2.5) — platform behaviour, unverifiable from this repo. The recommended fix (stop emitting Set-Cookie natively) makes the question moot either way.
- **Apple/Google native ID-token `aud` semantics** (bundle id vs. web client id) — external, and the §8 identity-registry ADR is where it gets pinned.
- **Expo's push-receipt JSON shape** (`details.error`, `DeviceNotRegistered` casing) — the §2.13 test should be written against Expo's documented shape and revisited if it changes.
- **Whether `imin-webapp` still needs the buyer booking-fee disclosure** — out of scope here, noted only because it touches the same fee arithmetic.