# Unsigned junk on the Stripe webhooks returns 400, not 500

## Goal

An unsigned or malformed delivery to `/api/v1/stripe/webhook/v1` or `/v2` gets the existing
400 `INVALID_REQUEST` "Invalid Stripe signature" and a WARN line, never a 500 logged at ERROR.

Cause (stripe-java 32.1.0):
- `Webhook.constructEvent` deserializes the payload before it verifies the signature. A non-JSON
  body throws `JsonSyntaxException`. A `{"object":"v2.core.event"}` body throws
  `IllegalArgumentException`. An empty or `null` body deserializes to null and then throws
  `NullPointerException` on `event.getObject()`.
- `Signature.getTimestamp` throws `NumberFormatException` for `t=abc` and
  `ArrayIndexOutOfBoundsException` for a `t` with no `=`. This happens on V1, and on V2 inside
  `StripeClient.parseEventNotification`.
- `StripeWebhookService.constructV1Event` and `handleV2Endpoint` catch only
  `SignatureVerificationException`, so these errors fall through to `GlobalExceptionHandler.handleAny`
  and come back as a 500.

Fix: every case still gets the same 400, but an unverified delivery and a verified one that we
fail to parse are logged differently. The second could be a real Stripe event, which is an
SDK/API-version regression and must alert.
- V1: `verifyV1Signature` runs `Webhook.Signature.verifyHeader(..., DEFAULT_TOLERANCE)` against
  the V1 secret, then the CONNECT secret (the same fallback and tolerance as before), and returns
  the secret that verified. A failure there is logged at WARN: a bad HMAC or a malformed `t=`
  header (`RuntimeException` from the SDK's header parse). Only then does
  `Webhook.constructEvent(..., verifiedSecret, 0)` parse. Tolerance 0 skips only the time window
  that was just checked, so a delivery right at the edge cannot fail the second check. A failure
  there means the body was signed but could not be parsed, and is logged at ERROR.
- V2: `stripeClient.parseEventNotification` stays the single call (it verifies, then
  `EventNotification.fromJson`). A `RuntimeException` from it is classified with
  `Webhook.Signature.verifyHeader` against the V2 secret: verified means ERROR, otherwise WARN. I
  did not split it into verify then `fromJson`, because `StripeWebhookServiceConnectTest` stubs
  `parseEventNotification` with a fake header (`"t=1,v1=fake"`). A verify-first step would reject
  those inputs, and the HTTP seam test pins that the real call receives the raw body.
- Gson is a `runtime`-scoped dependency of stripe-java, so the parse family is caught as
  `RuntimeException`, and only around the SDK calls. Event handling is outside every try.

## Affected files

- `src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java`: `constructV1Event` and
  `handleV2Endpoint`
- `src/test/java/com/imin/iminapi/stripe/StripeWebhookHttpSeamTest.java`

## Test impact

Integration, prod incident: one parameterized HTTP regression test in the existing
`@IminIntegrationTest` seam class. It adds no new context, fake or property source. The rows are:
- V1 non-JSON body
- V1 `{"object":"v2.core.event"}`
- V1 empty body
- V1 `t=abc`
- V1 `t` with no `=`
- V2 `t=abc`
- V2 `t` with no `=`
- V1 and V2 correctly signed non-JSON body (verified, then the parse fails)

The junk-body rows are signed with an unknown secret, so they are genuinely unsigned. Each row
asserts 400, `INVALID_REQUEST`, and an unchanged total row count of `processed_webhook_events`. The V2 rows also
assert that no event is fetched (`stripeClient.v2()` is never called). The test is proven red
(500) on the unfixed code before the fix exists. The WARN level is not asserted, because log-level
assertions are on the no-test list.

## Risks

- A broad exception type could hide a bug. The try block holds only the SDK call, which runs
  before any handler code, so a handler failure still surfaces as a 500 and Stripe retries it.
- A V2 body that is correctly signed but malformed would now get a 400 instead of a 500. Only the
  holder of the secret can produce one, and Stripe would never fix it on retry anyway.

## Definition of done

- The new rows are red (500) on the unfixed code and green after the fix.
- `StripeWebhookHttpSeamTest`, `StripeWebhookServiceTest` and `SpringContextGuardTest` are green.
- The full `./mvnw test` is green with no skipped Testcontainers tests.

## Review rounds
round 1 → PASS (MEDIUM fixed: verified-but-unparseable logs ERROR, unverified junk WARN; V2 classifies after parseEventNotification fails — accepted).
Live-test after deploy: unsigned "not json {" to /webhook/v1 and t=abc to /v2 → 400 INVALID_REQUEST, no new ERROR.
