# P3 gap: Stripe webhook HTTP seam

## Goal

Prove over HTTP (MockMvc, real Spring Security, real Postgres) that the two Stripe webhook
endpoints verify the signature over the exact request bytes, reject bad input without side
effects, and sit outside auth. Event-type business logic stays with `StripeWebhookServiceTest`
and `SettlementIngestWebhookTest`; this class owns only the HTTP contract and signature/perimeter.

Code read:
- `StripeWebhookController.java:62-80` (V1) and `:83-99` (V2) take `HttpEntity<String>` and pass the body string unchanged.
- `StripeWebhookService.java:150-160` V1 entry: `requireSecret`, `requireSignature`, then `handleV1Transactional`.
- `StripeWebhookService.java:171-189` `constructV1Event`: V1 secret, then CONNECT secret fallback, else 400 `Invalid Stripe signature`.
- `StripeWebhookService.java:200-215` verification precedes `dedup.tryRecord`, which inserts into `processed_webhook_events` (`WebhookEventDedupService.java:49-61`).
- `StripeWebhookService.java:340-352` V2: `stripeClient.parseEventNotification(raw, sig, secretV2)`; 400 on `SignatureVerificationException`.
- `StripeWebhookService.java:438-443` missing/blank header gives 400.
- `SecurityConfig.java:170` `permitAll` for `POST /api/v1/stripe/webhook/**`.
- SDK 32.1.0 `Webhook.constructEvent` and `StripeClient.parseEventNotification` both use `DEFAULT_TOLERANCE = 300` s.

## Affected files

- `src/test/java/com/imin/iminapi/stripe/StripeWebhookHttpSeamTest.java` (new, `@IminIntegrationTest`)
- this plan

No `src/main`, no `pom.xml`.

## Test impact (red proofs)

Secrets flipped through `PropertyFlips` on `StripeProperties`. V1 uses the documented no-op
`checkout.session.completed`; its only effect is the `processed_webhook_events` row for the
test's own event id. The `StripeClient` is a shared mock, so for V2 `parseEventNotification`
is delegated to a real offline `StripeClient`; the SDK does the HMAC check.

| test | guard | mutant in scratch copy |
|---|---|---|
| V1 signed with V1 secret, no auth: 200 + dedup row | perimeter | drop `permitAll` at `SecurityConfig.java:170` |
| V1 signed with CONNECT secret: 200 + row | fallback | remove CONNECT fallback in `constructV1Event` |
| bad sig / wrong secret / tampered byte / missing header: 400, no row | verification | parse without verifying in `constructV1Event`; drop `requireSignature` |
| raw body with whitespace, odd key order, non-ASCII: 200 + row | raw bytes | controller re-serializes body (Gson) before handing it on |
| expired timestamp (>300 s): 400, no row | tolerance | `constructEvent(..., tolerance 0)` |
| replay same id: 200 twice, one row | dedup ack | none needed if green; a dup surfacing as 409/500 is the bug |
| V2 valid: 200; bad sig: 400, no fetch | V2 verification | swallow `SignatureVerificationException` and parse anyway |

## Risks

- Shared DB: event ids carry a per-test UUID; assertions read rows by id only.
- The V2 delegation stub is a test seam over a mock; mutants must live in the service, not the stub.

## Definition of done

- New class green; `SpringContextGuardTest` green.
- Each mutant above turns the named test red in a fresh scratch copy.
- Full `./mvnw test` run logged to the scratchpad, no skipped Testcontainers tests.

## Review rounds
round 1 → FIX_REQUIRED (HIGH: StripeWebhookServiceTest signature tests duplicated the HTTP rule) → fixed by orchestrator: both removed, missing-secret 503 kept.
Card: unsigned non-JSON body, "object":"v2.core.event" body, or malformed t= header on /webhook/v1 (and bad t= on v2) give 500 instead of 400 — stripe-java Webhook.constructEvent deserializes before verifyHeader; catch JsonSyntaxException/IllegalArgumentException/NumberFormatException in constructV1Event and handleV2Endpoint.
