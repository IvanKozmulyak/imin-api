# Apple Wallet + Google Wallet ticket passes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an OS-durable ticket on both platforms. A pass lives in OS-owned storage that WebKit's 7-day script-storage eviction cannot touch, works on a locked screen with the radio off, survives app deletion, and installs from a plain web page. Apple's half exists and is half-finished; Google's half does not exist at all.

**Status:** written 2026-08-15. Sibling of `2026-08-15-mobile-phase0-backend.md` (merged to `master` at `05bb04c`), which explicitly scoped wallet passes out and recorded that nothing there blocks this. Nothing here blocks that either. Everything asserted about this repository was read from source; everything asserted about Apple's and Google's APIs was checked against current official documentation, and what could not be confirmed is listed in §Verification status rather than smoothed over.

**Task order:** 1 → **2** → (3 ‖ 4) → 5 → 6 → 7 → 8 → 9. Task 2 is the only hard prerequisite inside the Apple half; the Google half (5–7) is independent of the Apple half entirely and can run in a parallel lane. Task 8 needs both halves to exist.

**Architecture:** Every change is **additive on the wire**. `walletAvailable` keeps its name, type and meaning forever — `imin-public` reads it today at `lib/api/types.ts:243` and a shipped app binary cannot be force-updated. The two wallets sit behind two independent config gates that fail **closed and quietly**: unset ⇒ that wallet reports itself unavailable, the CTA is suppressed, and no other behaviour changes. Neither gate can break checkout, issuance, email or the door.

**Tech Stack:** Java 17 · Spring Boot 4.0.5 · jpasskit (Apple pkpass) · Nimbus JOSE+JWT 10.4 (Google save-link signing — already on the classpath, no new dependency) · BouncyCastle (transitive via jpasskit) · Spring `RestClient` · JUnit 5 + MockMvc + Mockito

---

## What already exists — verified against source, and it contradicts the brief

Read this before planning anything. **`AppleWalletPassService` is not a skeleton.** It is a real, working, tested pkpass generator.

| Claim in the brief | What the source says |
|---|---|
| "`AppleWalletPassService` exists — report what is real vs stubbed" | **Real.** `service/ticket/AppleWalletPassService.java:90-154` builds a `PKEventTicket`, attaches six artwork files, loads a PKCS#12 + WWDR intermediate, and returns a signed, zipped archive via jpasskit's `PKInMemorySigningUtil`. Nothing is a stub. |
| "what is actually missing beyond config (cert handling, `.p8`/`.p12` loading, pass signing, the manifest)" | **All four already work.** `pom.xml:146` declares `de.brendamour:jpasskit:0.4.1`; the library writes `manifest.json` (Guava `Hashing.sha1()`) and a detached CMS `signature` (`SHA1withRSA`) — both verified by unzipping the source jar, not assumed. There is no `.p8` anywhere and none is needed: Apple pass signing is `.p12`-based; `.p8` is for APNs and Sign in with Apple. |
| "there's no test because you can't sign without real certificates" | **There is a test, and it is the right one.** `src/test/java/com/imin/iminapi/service/ticket/WalletTestCerts.java` mints a WWDR-shaped self-signed root and a leaf signed by it at runtime with BouncyCastle; `AppleWalletPassServiceTest:45-104` drives the **real** signing path and asserts the archive contains `pass.json`, `manifest.json`, `signature` and all six images, and that `pass.json` carries the real `imin1.TKT_X.…` payload. No stub signer exists. |
| "Google Wallet does not exist at all" | **Confirmed.** `grep -rn 'GoogleWallet\|walletobjects\|save-to-google' src/main/java` returns nothing. |
| "`QrPayloadSigner` — check whether it carries an expiry or state" | **Neither.** `service/ticket/QrPayloadSigner.java:42-45` — the payload is `imin1.<ticketToken>.<first-16-bytes-of-HMAC-SHA256>`, a pure function of the token. No `iat`, no `exp`, no state. This is load-bearing for the update decision below. |
| "`APPLE_WALLET_*` values are unset, which is why the endpoint 503s" | **Confirmed**, `application.yaml:290-301`, five keys all `${…:}`. |
| "`PublicTicketResponse.walletAvailable` is the per-ticket flag the frontend gates on" | **Confirmed and already consumed.** `imin-public/components/buyer/ticket-view.tsx:161-165` and `components/buyer/order-view.tsx:63,169` render a real CTA through `components/buyer/wallet-cta.tsx`, double-gated on `walletAvailable` **and** a client-side Apple UA test. i18n keys `wallet.add` / `wallet.hint` ship in all four locales. |

**So the honest scope is not "build Apple Wallet".** It is: (1) finish the Apple pass — the field set, the artwork and the config gate are the parts that are actually thin; (2) build Google Wallet from zero; (3) decide pass updates deliberately; (4) widen one response field into a two-wallet contract without breaking the one client already reading it.

### Defects found while verifying, each fixed by a numbered step below

1. **A passwordless `.p12` fails closed forever, silently.** `AppleWalletProperties.fullyConfigured():33-39` requires `certPassword` to be non-blank. An empty export password is legal and common (`openssl pkcs12 -export -passout pass:`). Ops exports a valid cert, sets four env vars correctly, and the endpoint 503s with no log line and no way to tell it apart from "not configured yet". → Task 1.
2. **Nothing validates the certs until a buyer taps the button.** Bad base64, a wrong `certPassword`, or an expired cert are all indistinguishable from unconfigured until the first request, which then 500s. → Task 1.
3. **The 503 has an empty body.** `PublicTicketAssetController:56` returns `ResponseEntity.status(503).build()`. Every other error in the API is an `ApiError` envelope (`$.error.code`). → Task 1.
4. **A malformed `events.timezone` crashes the endpoint with a 500, not a handled error.** `AppleWalletPassService.formatWhen():226` calls `ZoneId.of(...)`, and it runs at line 111 — *outside* the `try` at line 129. Narrow trigger (the column is `NOT NULL DEFAULT 'UTC'`, `V6__events.sql:12`, and values are derived from a fixed country map), but the `ZoneId.systemDefault()` fallback on the null branch is unreachable dead code that reads like a safety net and is not one. → Task 3.
5. **The pass has no relevant date.** `relevantDates` is what makes a pass surface on the lock screen near door time — the single most valuable missing field, given that "works on a locked screen" is the entire justification for shipping passes. (The scalar `relevantDate` is deprecated on Apple's side, and jpasskit 0.4.1 cannot emit the array form at all — which is why the upgrade is a prerequisite and not a tidy-up.) → Tasks 2 and 3.
6. **The artwork is a black square with a white dot,** generated at runtime (`solidRect():192-212`). `imin-public/docs/BRANDING.md:92,94` already tells readers the files live at `imin-api/src/main/resources/wallet/*.png`. That directory **does not exist**. → Task 4.
7. **A refunded ticket still mints a pass.** `generatePass` never looks at `ticket.state`. → Task 3 / Task 7.
8. **pkpass generation is unmetered on an unauthenticated endpoint.** Three DB reads, an RSA signature and a ZIP per request, no rate limit anywhere on `/api/v1/public/tickets/**`. → Task 1.

---

## Global Constraints

- **Additive only, and `walletAvailable` is a one-way door.** It ships in `imin-webapp/docs/openapi.yaml:8865`, in `generated-types.ts:5309`, and in `imin-public/lib/api/types.ts:243`. It keeps its name and its `boolean` type permanently, and it keeps meaning **Apple specifically** — repurposing it to mean "either wallet" would light the Apple CTA on Android. New wallets get new fields.
- **Migrations: none.** Flyway is forward-only and **V92/V93 are already taken** by the merged Phase 0 branches, so a new one would start at **V94** — but this plan needs no schema. Everything it produces is derived from rows that already exist (`tickets`, `orders`, `events`, `organizations`). The only thing that would demand a table is an Apple pass-update web service (a device/registration registry), and §"Decision 1" declines to build one. If a later plan reverses that, it starts at V94.
- **Tests:** `./mvnw test` must stay fully green. Tests run on H2 in PG-compat mode. **Every external service is mocked — no test may reach Apple, Google or `pay.google.com`.**
- **`src/test/resources/application.yaml` REPLACES the main one, it does not merge.** Test resources come first on the classpath, so `classpath:/application.yaml` resolves to the 103-line test file — and it carries **no `imin.apple-wallet` block at all**. That is precisely why `AppleWalletPassServiceTest` constructs `new AppleWalletProperties()` by hand and why `PublicTicketAssetControllerTest:70-74` gets its 503. **Any new property must have a Java field default on the `@ConfigurationProperties` class**, or `@TestPropertySource`; a value that lives only in the main YAML is invisible to every test. Enumerating the key in `application.yaml` is still required — but that is about **production binding only**.
- **A permissive test double certifies the bug.** Two live instances of this in the area:
  - `TestRateLimitConfig.testRateLimiter()` does `buckets.computeIfAbsent(...)` — it **invents** a bucket for any name. `RateLimitConfig` is `@Profile("!test")` and throws `IllegalArgumentException("Unknown bucket …")`, which the global handler turns into a **500**. So an unregistered bucket is green in the suite and a guaranteed production error. `RateLimitBucketCoverageTest` guards *the YAML block only* — it does **not** check that `configs.put(...)` and the `@Value` pair exist in `RateLimitConfig`. Task 1 adds all three and says so.
  - A `@MockitoBean AppleWalletPassService` would answer `isConfigured() ⇒ false` regardless of any `@TestPropertySource`, and every wallet case would 503. Stub the gate in `@BeforeEach`.
- **Never sign with a stub.** The whole point of a pass is that a device Apple or Google controls accepts it. A test that "signs" with a fake proves nothing. Both halves therefore drive **real cryptography over synthetic key material**: `WalletTestCerts` for Apple (exists), and a runtime-generated RSA keypair for Google whose signature the test **verifies with the matching public key** before decoding the claims. See §Testing strategy.
- **Error envelope:** `ApiError` wraps the body in `error`, so assertions are `$.error.code`, never `$.code`.
- **Commits:** one per task, conventional-commit prefix, no `git add -A` (background agents are writing in sibling worktrees). Every commit carries the `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>` trailer.
- **Blocked on company registration.** No Apple Developer account exists, so no Pass Type ID certificate can be created — see `docs/decisions/ADR-0003-mobile-app-identity-registry.md`, step 6 of its account checklist. Google's issuer account is a *separate* blocker with a *different* lead time. §"What is gated on what" enumerates exactly which steps stop.
- **Deploy:** `imin-api` master → Railway auto-deploy. `imin-webapp`/`imin-public` run `api:sync` against **production**, so this must be merged and deployed before any FE type regeneration.
- **Out of scope:** ticket transfer (no such feature exists — `grep -rn 'TicketTransfer\|transferTicket' src/main/java` returns nothing), Apple pass-update web service, Google object patching, NFC/smart-tap, per-seat data, and any FE work in `imin-public`/`imin-webapp` (specced here in §Buyer-facing surface, built in those repos).

---

## Decisions taken in this plan

### Decision 1 — **We do not build pass updates. Deliberately, and here is the argument.**

Apple offers a web-service protocol (device registration + APNs push + a pass-fetch endpoint) and Google offers REST `patch` on a saved object. We build **neither**. This is the interesting case the brief asks about — a refunded ticket with a live pass on someone's phone — so the reasoning is written out rather than asserted.

**1. A stale pass cannot admit anyone.** `QrPayloadSigner` embeds no expiry and no state (verified above), so the QR never becomes *invalid* — it stays a correct, verifiable pointer to a ticket token. The authority is `TicketRedeemService.redeem()`, which re-reads state **inside a transaction** and does the transition as one guarded UPDATE:

```java
if (Ticket.STATE_REFUNDED.equals(t.getState())) return new Result(Outcome.REFUNDED, t);
if (Ticket.STATE_REVOKED.equals(t.getState()))  return new Result(Outcome.REVOKED, t);
```

and again after the atomic update, to close the race (`TicketRedeemService.java:57-77`). The gate PWA maps `refunded`, `revoked`, `wrong_event` and `invalid` to a red **"Do not admit"** and `already_redeemed` to amber (`imin-tickets-gate/components/scanner/ResultOverlay.tsx:15-32`), and fails safe to red on an unknown outcome. **A stale pass scans and is correctly rejected.** The door does not consult the pass; it consults the database.

**2. Not updating is not a new failure mode.** A refunded buyer who turns up and is turned away is *already* the behaviour for the QR PNG in their email inbox, for the screenshot the app's own copy tells them to take (`imin-public/lib/i18n/en.ts:408`: "Screenshot your QR before you head out"), and for a printed ticket. None of those can be recalled either. A wallet pass is not uniquely bad here; it is the fourth copy of a thing that was never revocable.

**3. The cost is a whole subsystem, for a display nicety.** Apple's side needs four authenticated endpoints, a device-registration table (a migration, GDPR-relevant device identifiers, a DSAR export obligation — the Phase 0 plan already logged that gap for push tokens), an APNs credential whose topic is the Pass Type ID, and a fan-out on every state change. Google's side needs the REST write path and its own credential scope. That is comparable to the entire Google Wallet half of this plan, spent on making a rejected ticket *look* rejected before it is scanned.

**4. Apple's own documentation tells you not to rely on it.** The guidance on `voided` is explicit: pass updates ride APNs, APNs delivery is not guaranteed, multiple pushes from one source are coalesced, and the correct design is to update your own database and **check at redemption**. That is exactly what `TicketRedeemService` does. Building the web service would not make the door safer; it would add a second, less reliable opinion beside the one that already decides.

**5. What we do instead, and it is not nothing.**
   - **Refuse to mint a pass for a ticket that is not live.** `state ∈ {refunded, revoked}` ⇒ `409`, both wallets, one shared rule (Tasks 3 and 7). This is strictly better than the status quo, which happily signs a pass for a refunded ticket.
   - **Let the OS age the pass out.** Apple `expirationDate` + `relevantDates`; Google object `state` and validity window. After the event the pass demotes itself with no server involved. This covers the overwhelmingly common case — a pass that is stale because the event is over, not because of a refund.
   - **`imin-public` already hides the CTA for non-live tickets** (`ticket-view.tsx:160`), so a refunded buyer is not *offered* a pass in the first place.

**6. The honest counter-argument, recorded rather than buried.** The two platforms are **not** symmetric in cost. Google's update is one authenticated `PATCH` on an object we already created — genuinely cheap, no registration table, no APNs, no device protocol. Apple's is the whole subsystem. So "do Google only" is a real option and it is the first thing to revisit. It is declined for v1 because a pass that self-corrects on Android and stays stale on iOS is a worse product than one that is uniformly stale-but-correctly-rejected: support has to explain two behaviours, and the platform with the *smaller* European nightlife share would be the one that works. Two further constraints if it is ever picked up: Google caps push-triggering updates at **3 per object per 24 hours**, and a change to the **class** propagates to every object referencing it immediately — so a careless class edit is a fan-out to every holder of every ticket for that event.

**7. Recorded, with triggers to revisit.** ADR-0004 (Task 9) states the decision and the three conditions that reverse it: refund volume on live events becoming material; the introduction of ticket transfer / name change (which does not exist today — `grep -rn 'TicketTransfer\|transferTicket' src/main/java` returns nothing — and would put a *different person's* name on a live pass, a genuinely different problem); or a decision to accept the platform asymmetry in §6.

### Decision 2 — Google classes **and objects** are created through the REST API, lazily, per event / per ticket. The JWT carries only ids.

An inline class-and-object JWT **is** supported — Google's Event-tickets JWT page says the class and object defined in the JWT are created when the user saves. It is nonetheless the wrong choice here, for a reason Google documents themselves:

> **The save URL is capped at 1800 characters** by browser URL limits, and Google's own FAQ entry for "my JWT link URL exceeds the 1800 character limit" answers: pre-create the classes and objects via the REST API and put only `{"id": "…"}` in the JWT.

An inline class carrying `eventName`, `issuerName`, `venue`, `dateTime`, `logo` and `heroImage`, plus an inline object with a barcode and its `alternateText`, blows through 1800 base64url characters immediately. So:

- **Class per event**, id `{issuerId}.evt_{eventId}`. A class carries `eventName`, `venue` and `dateTime`, so one global class would put a generic name on every pass.
- **Object per ticket**, id `{issuerId}.tkt_{ticketToken}`, `classId` pointing at the event's class.
- **The JWT payload is `{"eventTicketObjects": [{"id": "…"}]}`** — thin by construction, nowhere near the limit.
- **`reviewStatus` must be `UNDER_REVIEW` on insert, never `DRAFT`.** A `DRAFT` class *cannot be used to create any object*, and the transition off `draft` is one-way. `UNDER_REVIEW` is not a human queue — the platform promotes it to `APPROVED` automatically and it can be used immediately.
- **Not on boot.** N events, N grows; boot-time provisioning means network I/O in the startup path and a thundering herd on every Railway restart, creating classes for events nobody is buying.
- **Not by hand.** A human step per event is not a system.
- **Idempotent:** get-then-insert on both, tolerating `409` as success, so two concurrent buyers cannot both create and neither fails.
- **Ceiling, stated:** two synchronous outbound calls sit inside the request. They are wrapped so a Google outage degrades to `503 UPSTREAM_UNAVAILABLE` on *that button* and never touches the ticket read, the QR, or the Apple pass. They must not run inside the read transaction.

### Decision 2b — `origins` is set, not left blank.

Google's JWT docs warn that the Add-to-Google-Wallet **button will not render** when `origins` is undefined, with an `X-Frame-Options` / "Refused to display" failure. That warning is about the embeddable JS button rather than the plain redirect this plan uses (Decision 3), so it may not bite — but "may not bite" is not a reason to ship an undefined field. Set `GOOGLE_WALLET_ORIGINS` to the buyer origins (`https://app.imin.wtf`, plus the API origin the email links from) and keep the blank-means-unrestricted default only for local dev.

### Decision 3 — the save link is an **endpoint that redirects**, not a URL the client builds.

A Google save link is a signed JWT with an `iat`; it cannot be constructed client-side and it should not be minted on every ticket read (`GET /public/tickets/{token}` would acquire an RSA signature per call). So it mirrors the pkpass endpoint exactly:

```
GET /api/v1/public/tickets/{token}/google-wallet   →  302 to https://pay.google.com/gp/v/save/<jwt>
```

Same auth model (the 24-byte token is the credential), same `Cache-Control: private, no-store`, same 404/409/503 shapes.

### Decision 4 — the **server says what exists; the client says what the device can open.**

`imin-public/components/buyer/wallet-cta.tsx:8-16` already does a `navigator.userAgent` test for Apple platforms, with a hard-`false` server snapshot through `useSyncExternalStore`. Android gets the mirror image. **Do not move platform detection server-side via `User-Agent`** — it is the wrong seam (the same ticket URL is legitimately opened on a laptop and forwarded to a phone), and it would make the response body vary by a header that nothing else in this API varies on.

---

## What is gated on what

Three independent gates, and they are **not** the same gate. Getting this wrong is how the whole plan looks blocked when most of it is not.

| Gate | What it blocks | Lead time |
|---|---|---|
| **G-CERT — Apple Developer Program + Pass Type ID certificate** (ADR-0003 steps 2, 3, 6 — needs a D-U-N-S number and a legal entity) | Only the *production* Apple values: `APPLE_WALLET_PASS_TYPE_ID`, `APPLE_WALLET_TEAM_ID`, `APPLE_WALLET_CERT_P12_BASE64`, `APPLE_WALLET_CERT_PASSWORD`, `APPLE_WALLET_WWDR_PEM_BASE64`. **And one on-device verification step.** | 1–2 wks after D-U-N-S |
| **G-ISSUER — Google Pay & Wallet Console issuer account + a Google Cloud service account, *and* publishing access** | The production Google values and the on-device verification. Independent of Apple. **Two stages, and the second is a real review.** | see below |
| **The imin brand mark as PNG files** | Task 4 only. Not blocked on any account — blocked on someone handing over the asset. Every logo mark in the workspace is PNG, not the SVG `BRANDING.md` claims. | hours |

**G-ISSUER is two gates wearing one name, and the ordering is counter-intuitive.** Verified against Google's issuer-onboarding and request-publishing-access docs:

1. **Every new issuer account starts in demo mode.** You can create classes and objects and save passes, but they only reach accounts holding Admin/Developer on the issuer account or explicitly added as test accounts in the console, and every pass is prefixed `[TEST ONLY]`.
2. **Publishing access requires a completed Business Profile (including a payment profile) and at least one Passes Class already created**, then a "Request publishing access" click that the Google Wallet team reviews and responds to.

So the sequence is **register → deploy this code with a real issuer id → let it create the first class → *then* request publishing access**. Requesting first is not possible. That means **demo mode is the correct state for the whole of development and internal testing**, and G-ISSUER's real lead time is "days for the account, unknown for the review" — plan for the review to be the long pole and start it the moment the first class exists. This also makes the on-device verification checkbox in §Deployment cheap: a demo-mode pass added to an Admin's own phone proves the pipeline end to end, `[TEST ONLY]` and all.

**Everything else in this plan is executable today**, because `WalletTestCerts` already proves the Apple path end-to-end with synthetic certs and Task 5 does the same for Google. Concretely: **Tasks 1, 2, 3, 4, 5, 6, 7, 8 and 9 all run to green with no account.** What is gated is exactly two things, called out again in each task and in §Deployment:

- **G-CERT** — setting the five real `APPLE_WALLET_*` values and confirming a real device adds the pass.
- **G-ISSUER** — setting `GOOGLE_WALLET_ISSUER_ID` + the service-account JSON and confirming a real Android device adds the pass.

A synthetic-cert test proves the archive is well-formed and correctly signed *by the key we gave it*. It cannot prove Apple's Wallet app accepts the chain — `WalletTestCerts`'s own Javadoc says so. **Neither wallet is "done" until one real phone has added one real pass.** That step is written into §Deployment as a checkbox, not left implied.

---

## File Structure

**Task 1 — Apple config truth and endpoint hygiene**
- Modify `service/ticket/AppleWalletProperties.java` — drop `certPassword` from the required set; add `enabled`
- Create `service/ticket/WalletCredentialCheck.java` — one-shot startup validation of the P12 + WWDR
- Modify `controller/publicapi/PublicTicketAssetController.java` — `ApiError` envelope, `Content-Disposition`, rate limit
- Modify `config/RateLimitConfig.java` + `application.yaml` — the `wallet-pass` bucket (**all three places**)
- Test `src/test/java/com/imin/iminapi/service/ticket/WalletConfigGateTest.java`
- Test `src/test/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetControllerTest.java` — widen

**Task 2 — jpasskit 0.4.1 → 0.5.8** *(prerequisite for Tasks 3 and 4)*
- Modify `pom.xml:146-149`

**Task 3 — Apple pass.json: the fields that make an offline ticket work** *(after Task 2)*
- Modify `service/ticket/AppleWalletPassService.java` — the whole `generatePass` body
- Create `service/ticket/WalletEligibility.java` — the shared live-ticket rule
- Test `src/test/java/com/imin/iminapi/service/ticket/ApplePassContentTest.java`

**Task 4 — Real pass artwork + the iOS 18 poster event ticket** *(after Task 2)*
- Create `src/main/resources/wallet/{icon,icon@2x,icon@3x,logo,logo@2x,logo@3x}.png` — **38pt icon, not the stale 29**
- Create `src/main/resources/wallet/{primaryLogo,secondaryLogo}{,@2x,@3x}.png` — poster style only
- Create `service/ticket/WalletArtwork.java` — classpath loader with the generated square as fallback
- Modify `service/ticket/AppleWalletPassService.java` — consume it; `preferredStyleSchemes`; `artwork.png` from `events.poster_url`
- Test `src/test/java/com/imin/iminapi/service/ticket/WalletArtworkTest.java`

**Task 5 — Google Wallet foundations: properties, credential, signer**
- Create `service/ticket/google/GoogleWalletProperties.java`
- Create `service/ticket/google/GoogleServiceAccountKey.java` — parse + hold the RSA key
- Create `service/ticket/google/GoogleWalletJwtSigner.java`
- Modify `service/ticket/TicketConfig.java` — register the properties
- Modify `application.yaml`
- Test `src/test/java/com/imin/iminapi/service/ticket/google/GoogleWalletJwtSignerTest.java`
- Test helper `src/test/java/com/imin/iminapi/service/ticket/google/GoogleTestKeys.java`

**Task 6 — Event Ticket class + object**
- Create `service/ticket/google/GoogleWalletModels.java` — the class/object payload records
- Create `service/ticket/google/GoogleWalletApiClient.java` — RestClient wrapper + OAuth token cache
- Create `service/ticket/google/GoogleWalletProvisioner.java` — idempotent class-then-object insert
- Test `src/test/java/com/imin/iminapi/service/ticket/google/GoogleWalletModelsTest.java`
- Test `src/test/java/com/imin/iminapi/service/ticket/google/GoogleWalletProvisionerTest.java`

**Task 7 — The save-link endpoint**
- Create `service/ticket/google/GoogleWalletPassService.java`
- Modify `controller/publicapi/PublicTicketAssetController.java`
- Modify `config/SecurityConfig.java` — only if the GET blanket rule does not already cover it (**it does**, `SecurityConfig:127`; verify, do not add)
- Test `src/test/java/com/imin/iminapi/controller/publicapi/GoogleWalletEndpointTest.java`

**Task 8 — One wallet contract for two wallets and two platforms**
- Modify `dto/publicapi/PublicTicketResponse.java` — add the `wallet` block, keep `walletAvailable`
- Modify `controller/publicapi/PublicOrderController.java`
- Modify `service/ticket/TicketIssuanceEmailer.java` — the email CTA pair
- Test `src/test/java/com/imin/iminapi/controller/publicapi/WalletContractTest.java`

**Task 9 — ADR and the docs that are already wrong**
- Create `docs/decisions/ADR-0004-wallet-passes-are-not-updated.md`
- Modify `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md` — §14 (**in the imin-public repo**)

---

## Task 1: Apple config truth and endpoint hygiene

Size: **S**. Gated on nothing.

Four defects, all in the "is this configured, and what happens when it isn't" seam. None of them need a certificate to fix or to test, because `WalletTestCerts` supplies real key material.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/ticket/AppleWalletProperties.java:16-44`
- Create: `src/main/java/com/imin/iminapi/service/ticket/WalletCredentialCheck.java`
- Modify: `src/main/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetController.java:52-63`
- Modify: `src/main/java/com/imin/iminapi/config/RateLimitConfig.java`, `src/main/resources/application.yaml`
- Test: `src/test/java/com/imin/iminapi/service/ticket/WalletConfigGateTest.java`
- Test: `src/test/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetControllerTest.java`

**Interfaces:**
- Produces: `AppleWalletProperties.fullyConfigured()` (semantics changed: password no longer required), `AppleWalletProperties.isEnabled()`, `WalletCredentialCheck.validate(AppleWalletProperties) : Optional<String>` (the failure reason, or empty)
- Consumes: `RateLimiter.consume(String, String)`, `ApiException`, `ErrorCode.UPSTREAM_UNAVAILABLE`

---

- [x] **Step 1: Write the failing test**

Create `src/test/java/com/imin/iminapi/service/ticket/WalletConfigGateTest.java`:

```java
package com.imin.iminapi.service.ticket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What "configured" means, pinned.
 *
 * <p>The bug this exists to stop: a .p12 exported with an EMPTY password is
 * legal and common (`openssl pkcs12 -export -passout pass:`). The original
 * fullyConfigured() required certPassword to be non-blank, so an operator could
 * set four env vars correctly, hold a perfectly good certificate, and get a
 * permanent 503 with no log line distinguishing it from "not set up yet".
 */
class WalletConfigGateTest {

    @Test
    void blankPasswordDoesNotDisqualifyAnOtherwiseCompleteConfig() {
        AppleWalletProperties p = configured();
        p.setCertPassword("");
        assertThat(p.fullyConfigured()).isTrue();
    }

    @Test
    void aMissingCertIsStillNotConfigured() {
        AppleWalletProperties p = configured();
        p.setCertP12Base64("");
        assertThat(p.fullyConfigured()).isFalse();
    }

    @Test
    void allBlankIsNotConfigured() {
        assertThat(new AppleWalletProperties().fullyConfigured()).isFalse();
    }

    /**
     * The kill switch. Present so a production incident can turn passes off
     * without deleting a certificate out of Railway's env and losing it.
     */
    @Test
    void disabledOverridesAFullConfig() {
        AppleWalletProperties p = configured();
        p.setEnabled(false);
        assertThat(p.fullyConfigured()).isFalse();
    }

    // ── credential validation ────────────────────────────────────────────────

    @Test
    void realKeyMaterialValidates() {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate();
        AppleWalletProperties p = new AppleWalletProperties();
        p.setPassTypeId("pass.test.imin");
        p.setTeamId("TESTTEAMID");
        p.setCertP12Base64(bundle.p12Base64());
        p.setCertPassword(bundle.password());
        p.setWwdrPemBase64(bundle.wwdrPemBase64());

        assertThat(WalletCredentialCheck.validate(p)).isEmpty();
    }

    /**
     * The case that currently 500s on the first buyer's tap instead of failing
     * at deploy time: syntactically present, cryptographically useless.
     */
    @Test
    void garbageBase64FailsWithAReason() {
        AppleWalletProperties p = configured();
        p.setCertP12Base64("bm90LWEtcDEy"); // "not-a-p12"
        assertThat(WalletCredentialCheck.validate(p))
                .isPresent()
                .get().asString().containsIgnoringCase("p12");
    }

    @Test
    void wrongPasswordFailsWithAReason() {
        WalletTestCerts.Bundle bundle = WalletTestCerts.generate();
        AppleWalletProperties p = new AppleWalletProperties();
        p.setPassTypeId("pass.test.imin");
        p.setTeamId("TESTTEAMID");
        p.setCertP12Base64(bundle.p12Base64());
        p.setCertPassword("definitely-not-the-password");
        p.setWwdrPemBase64(bundle.wwdrPemBase64());

        assertThat(WalletCredentialCheck.validate(p)).isPresent();
    }

    @Test
    void unconfiguredValidatesTriviallyRatherThanReportingAFalseFault() {
        // Nothing set is not a fault — it is the default state of the system.
        assertThat(WalletCredentialCheck.validate(new AppleWalletProperties())).isEmpty();
    }

    private static AppleWalletProperties configured() {
        AppleWalletProperties p = new AppleWalletProperties();
        p.setPassTypeId("pass.com.imin.ticket");
        p.setTeamId("ABCDE12345");
        p.setCertP12Base64("Zm9v");
        p.setCertPassword("pw");
        p.setWwdrPemBase64("YmFy");
        return p;
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=WalletConfigGateTest`
Expected: **FAIL** — compilation error, `WalletCredentialCheck` does not exist and `setEnabled` is not on the properties. Once those compile, `blankPasswordDoesNotDisqualifyAnOtherwiseCompleteConfig` is the one that fails on logic.

- [x] **Step 3: Fix the properties gate**

In `AppleWalletProperties.java`, add the switch and remove the password from the required set:

```java
    /**
     * Master switch. Defaults true so setting the five credential values is
     * sufficient to turn passes on; exists so an incident can disable pass
     * generation without deleting a certificate out of the environment (and
     * losing the only copy of it).
     */
    private boolean enabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    /**
     * True when a pass can be signed.
     *
     * <p><b>{@code certPassword} is deliberately NOT required.</b> A PKCS#12
     * exported with an empty password is legal and common
     * ({@code openssl pkcs12 -export -passout pass:}), and demanding one here
     * meant a correct certificate produced a permanent, undiagnosable 503:
     * indistinguishable from "not configured yet", with no log line either way.
     * A blank password is passed to the keystore as an empty char[], which is
     * what an empty-password P12 actually wants.
     */
    public boolean fullyConfigured() {
        return enabled
                && !isBlank(passTypeId)
                && !isBlank(teamId)
                && !isBlank(certP12Base64)
                && !isBlank(wwdrPemBase64);
    }
```

Add the YAML line beside the other five (`application.yaml`, in the `imin.apple-wallet` block):

```yaml
    # Kill switch. true + the five values below = passes on. Set false to stop
    # signing without removing the certificate from the environment.
    enabled: ${APPLE_WALLET_ENABLED:true}
```

- [x] **Step 4: Add the credential check**

Create `src/main/java/com/imin/iminapi/service/ticket/WalletCredentialCheck.java`:

```java
package com.imin.iminapi.service.ticket;

import de.brendamour.jpasskit.signing.PKSigningInformationUtil;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Optional;

/**
 * Loads the configured Apple credentials once and reports why they are
 * unusable, if they are.
 *
 * <p><b>Why this is separate from {@link AppleWalletProperties#fullyConfigured()}.</b>
 * That method answers "did someone set the env vars", which is a string check.
 * This one answers "will jpasskit be able to sign", which requires actually
 * decoding the base64, opening the keystore with the password, and parsing the
 * WWDR certificate. Before this existed, the difference between those two
 * questions surfaced as a 500 on the first buyer who tapped the button, hours
 * or days after the bad deploy.
 *
 * <p>Returns the failure reason rather than throwing: an unusable certificate
 * must not stop the application from booting. Ticket issuance, email, checkout
 * and the door all work fine without wallet passes, and taking the whole API
 * down over a decoration would be a far worse outage than the one being
 * diagnosed.
 */
public final class WalletCredentialCheck {

    private WalletCredentialCheck() {}

    /** Empty when there is nothing wrong — including when nothing is configured at all. */
    public static Optional<String> validate(AppleWalletProperties props) {
        if (!props.fullyConfigured()) {
            // Not configured is not a fault. It is the default state.
            return Optional.empty();
        }
        byte[] p12;
        byte[] wwdr;
        try {
            p12 = Base64.getDecoder().decode(props.getCertP12Base64());
        } catch (IllegalArgumentException e) {
            return Optional.of("APPLE_WALLET_CERT_P12_BASE64 is not valid base64");
        }
        try {
            wwdr = Base64.getDecoder().decode(props.getWwdrPemBase64());
        } catch (IllegalArgumentException e) {
            return Optional.of("APPLE_WALLET_WWDR_PEM_BASE64 is not valid base64");
        }
        try {
            new PKSigningInformationUtil()
                    .loadSigningInformationFromPKCS12AndIntermediateCertificate(
                            new ByteArrayInputStream(p12),
                            props.getCertPassword() == null ? "" : props.getCertPassword(),
                            new ByteArrayInputStream(wwdr));
            return Optional.empty();
        } catch (Exception e) {
            // The message deliberately names the p12 first: a wrong password and
            // a corrupt archive both surface here, and "p12" is the substring an
            // operator greps for.
            return Optional.of("Apple Wallet p12/WWDR could not be loaded — "
                    + "wrong APPLE_WALLET_CERT_PASSWORD, corrupt archive, or a mismatched "
                    + "WWDR intermediate: " + e.getMessage());
        }
    }
}
```

Then call it once at startup, from `AppleWalletPassService`. Add to the constructor's tail:

```java
        WalletCredentialCheck.validate(props).ifPresentOrElse(
                reason -> log.error("[wallet] Apple Wallet is configured but UNUSABLE: {} "
                        + "— /apple-wallet.pkpass will fail. Fix the credentials or set "
                        + "APPLE_WALLET_ENABLED=false.", reason),
                () -> log.info("[wallet] Apple Wallet {}",
                        props.fullyConfigured() ? "configured and credentials load OK" : "not configured"));
```

**Do not** make this throw. A bad certificate must not take the API down; see the Javadoc above.

- [x] **Step 5: Give the 503 a body, a filename and a bucket**

Replace `PublicTicketAssetController.applePass` (`:52-63`):

```java
    @GetMapping("/api/v1/public/tickets/{token}/apple-wallet.pkpass")
    public ResponseEntity<byte[]> applePass(@PathVariable String token, HttpServletRequest http) {
        // Signing an archive is three DB reads, an RSA signature and a ZIP —
        // two orders of magnitude more expensive than the QR PNG next door, on
        // an endpoint whose only credential is a URL. Meter it.
        rateLimiter.consume("wallet-pass", "ip:" + http.getRemoteAddr());

        Ticket t = tickets.findByToken(token).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (!wallet.isConfigured()) {
            // Was an empty 503 body. Every other error in this API is an
            // ApiError envelope, and imin-public's error handling reads
            // $.error.code.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Apple Wallet passes are not available");
        }
        byte[] pkpass = wallet.generatePass(t.getToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.apple.pkpass")
                // iOS Safari keys off the MIME type alone, but Android Chrome and
                // desktop browsers save the response under the last path segment
                // unless told otherwise — without this the file lands as
                // "apple-wallet.pkpass" with no relation to the ticket.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"imin-ticket-" + t.getToken() + ".pkpass\"")
                .body(pkpass);
    }
```

Inject `RateLimiter` into the constructor.

- [x] **Step 6: Register the bucket in all three places**

`RateLimitBucketCoverageTest` checks **only** that `application.yaml` has an `imin.ratelimit.wallet-pass:` block. It does **not** check the `@Value` pair or the `configs.put(...)`, and the test double invents buckets on demand — so missing either of the other two is green in the suite and a **500 on every request** in production. Do all three.

`application.yaml`, inside `imin.ratelimit`:

```yaml
    wallet-pass:
      # Signed .pkpass / Google save-link minting, keyed per client IP
      # (forward-headers-strategy=framework makes getRemoteAddr the real client
      # IP in prod). Generous for a real buyer with several tickets on one order
      # who taps each one, tight enough that a loop cannot spin RSA signatures.
      capacity: 30
      window-minutes: 5
```

`RateLimitConfig.java` — the `@Value` pair:

```java
    @Value("${imin.ratelimit.wallet-pass.capacity}")
    private int walletPassCapacity;
    @Value("${imin.ratelimit.wallet-pass.window-minutes}")
    private int walletPassWindow;
```

and the registration, beside the others:

```java
        configs.put("wallet-pass", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(walletPassCapacity, Duration.ofMinutes(walletPassWindow)))
                .build());
```

- [x] **Step 7: Widen the controller test**

In `PublicTicketAssetControllerTest`, replace the bare-status 503 assertion so the envelope is actually pinned:

```java
    @Test
    void applePass_returns503WithAnErrorEnvelopeWhenWalletNotConfigured() throws Exception {
        Ticket t = persistTicket();
        mvc.perform(get("/api/v1/public/tickets/" + t.getToken() + "/apple-wallet.pkpass"))
                .andExpect(status().isServiceUnavailable())
                // $.error.code, never $.code — ApiError wraps the body.
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));
    }
```

Note this test is green for the *right* reason under the test profile: `src/test/resources/application.yaml` carries no `imin.apple-wallet` block at all, so every field binds from its Java default and `fullyConfigured()` is false.

- [x] **Step 8: Run the tests**

Run: `./mvnw test -Dtest='WalletConfigGateTest,PublicTicketAssetControllerTest,RateLimitBucketCoverageTest,AppleWalletPassServiceTest'`
Expected: PASS.

- [x] **Step 9: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/ticket/AppleWalletProperties.java \
        src/main/java/com/imin/iminapi/service/ticket/WalletCredentialCheck.java \
        src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java \
        src/main/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetController.java \
        src/main/java/com/imin/iminapi/config/RateLimitConfig.java \
        src/main/resources/application.yaml \
        src/test/java/com/imin/iminapi/service/ticket/WalletConfigGateTest.java \
        src/test/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetControllerTest.java
git commit -m "fix(wallet): make the Apple config gate mean what it says"
```

---

## Task 2: Upgrade jpasskit 0.4.1 → 0.5.8

Size: **S**. Gated on nothing. **Hard prerequisite for Tasks 3 and 4** — not an optional tidy-up.

**Why it is mandatory, not nice-to-have.** Two fields Task 3 needs do not exist in 0.4.1:

- **`relevantDate` is deprecated on Apple's side in favour of `relevantDates`** (an array of `Pass.RelevantDates`). jpasskit gained `PKRelevantDate` / `relevantDates` in the 0.5.x line, with the list serialization fixed in **0.5.5**. 0.4.1 can only emit the deprecated scalar.
- **`preferredStyleSchemes` exists in 0.5.0+ and is absent from 0.4.1/0.4.2.** That is the opt-in for the iOS 18 poster event ticket in Task 4.
- ~~0.5.x also carries the full event semantic set — `venueName`, `venueLocation`, `venueEntrance`, `venueRoom`, `performerNames`, `seats` — where 0.4.1's `PKSemantics` has only `eventName`, `eventType`, `eventStartDate`, `eventEndDate` (verified by diffing both source jars: `PKSemantics.java:130-146` vs `:170-190`).~~ **Wrong — corrected while executing Task 2.** `0.4.1/PKSemantics.java:130-152` already declares `eventName`, `venueName`, `venueLocation`, `venueEntrance`, `venuePhoneNumber`, `venueRoom`, `eventType`, `eventStartDate`, `eventEndDate`, `artistIDs`, `performerNames`, `genre`, and `seats` at `:42`. The cited line range is the `eventName…eventEndDate` slice, not the whole set. **The semantic venue fields are not a reason to upgrade** — `relevantDates` and `preferredStyleSchemes` are, and they are sufficient. (What 0.5.8 does add to `PKSemanticsBuilder` is aviation/transit: `boardingZone`, airport locations and timezones, security programs, SSRs, `ticketFareClass`, `membershipProgramStatus`. Nothing an event ticket uses. `eventStartDate`/`eventEndDate` still take `java.util.Date`, not `Instant`, in 0.5.8.)

**Why the bump is low-risk.** Verified by unzipping both source jars, not assumed: the signing behaviour is **byte-identical in intent**. Both use Guava `Hashing.sha1()` for `manifest.json` (`PKInMemorySigningUtil.java:130` in 0.4.1, `:145` in 0.5.8) and `SHA1withRSA` for the detached CMS signature (`PKAbstractSigningUtil.java:86` in both). That is not a defect to fix — **Apple's current *Building a Pass* still specifies SHA-1 manifest hashes**. (The SHA-256 you may remember belongs to Apple **Wallet Orders**' `order.json`, a different product. Do not conflate them.)

**Two operational notes that fall out of the library:**
- **jpasskit does not bundle the WWDR certificate** — we must supply it, which is what `APPLE_WALLET_WWDR_PEM_BASE64` is for — and `loadSigningInformationFromPKCS12AndIntermediateCertificate(...)` calls `checkValidity()`, so **an expired WWDR intermediate fails hard at load**. That is precisely the failure `WalletCredentialCheck` (Task 1) turns into a startup ERROR instead of a 500 at the door.
- **The correct intermediate is WWDR G4.** Apple's WWDR reference table maps Pass Type ID certificates (along with APNs SSL and Order Type ID) to **G4**, required for certificates issued after 2022-01-27, expiring 2030. G3 is software signing; G5/G6/G7 are unrelated to passes. Record the generation when the certificate is created.

**Watch for:** 0.5.8's POM pulls `jackson-databind` 2.22.1, `guava` 33.6.0-jre, `bcpkix-jdk18on` 1.85 and ~~adds~~ **already carried** `pushy` (an APNs client, for the pass-update service we are **not** building — it is dead weight, not a signal to build one). Spring Boot's `dependencyManagement` pins Jackson via `jackson-bom`, so Boot's version wins; confirm that rather than assume it. jpasskit targets Java 11, which is fine for Java 17.

**What the bump actually did — measured, 2026-08-16.**

- **Zero API breaks.** A `javap` diff of every public type in both jars removes **nothing**: 0.4.1's entire public surface is present in 0.5.8. The only altered declaration is `PKPassTemplateInMemory`, which additionally implements `Serializable` and gains a `Map<String,byte[]>` constructor plus `getFilesMap()`. `AppleWalletPassService` compiled and its tests passed with **no source change**.
- **Jackson: Boot wins, confirmed not assumed.** `dependency:tree -Dverbose` prints `com.fasterxml.jackson.core:jackson-databind:2.21.2 — version managed from 2.22.1`; exactly one copy on the classpath. The Jackson 3 (`tools.jackson.core:jackson-databind:3.1.0`) that Boot 4 uses for HTTP is a **different groupId and package** and does not collide. Note the direction: jpasskit is compiled against 2.22.1 and runs on 2.21.2 — a downgrade, which would surface as `NoSuchMethodError` inside the signing `catch`. It does not: the serialization path is exercised for real by `AppleWalletPassServiceTest` and `JpasskitCapabilityTest`.
- **The transitive set did not change shape.** Same artifacts, newer versions: `pushy` 0.15.4→0.15.6, `guava` 33.1.0→33.6.0-jre, `commons-io` 2.16.1→2.22.0, BouncyCastle 1.78.1→1.85 (the only BC on the classpath — `WalletTestCerts` uses it). **`pushy` and its netty transitives were already there under 0.4.1**, so no exclusions are warranted and none were added; netty is independently on the classpath via reactor-netty and the AWS SDK at the same 4.2.12.Final.
- **One wire-format change, and it is benign.** jpasskit builds `relevantDates` as `Collections.emptyList()` when unset and the signing `ObjectMapper` is `Include.NON_NULL`, so **every pass now carries `"relevantDates":[]`** until Task 3 populates it. Byte-diffed a generated `pass.json` under both versions: that empty array is the *only* difference — same QR message, same field order, same `"voided":false,"sharingProhibited":false`. It is the same shape 0.4.1 already emitted for `beacons`, `locations`, `associatedApps` and `associatedStoreIdentifiers`.
- **For Task 3:** `PKRelevantDateBuilder` implements `IPKBuilder` only — **not `IPKValidateable`**. It will happily emit a `startDate` with no `endDate`, which Apple documents as invalid ("Required when providing startDate"). Enforce the pairing in `AppleWalletPassService`; the library will not.

**Files:** Modify `pom.xml:146-149`. Plus `src/test/java/com/imin/iminapi/service/ticket/JpasskitCapabilityTest.java` — see Step 3.

---

- [x] **Step 1: Bump the version**

```xml
        <dependency>
            <groupId>de.brendamour</groupId>
            <artifactId>jpasskit</artifactId>
            <version>0.5.8</version>
        </dependency>
```

- [x] **Step 2: Confirm Boot still wins on Jackson**

Run: `./mvnw dependency:tree -Dincludes='com.fasterxml.jackson.core:jackson-databind'`
Expected: exactly one `jackson-databind`, at the Spring Boot 4.0.5 managed version — **not** 2.22.1. If two appear, or if the version is jpasskit's, add an explicit `<exclusions>` on the jpasskit dependency rather than letting a transitive Jackson float into the app.

Note `-Dincludes` filters the tree but `-q` suppresses it entirely, and the plain form hides the mediation. Use `-Dverbose` to see `version managed from 2.22.1` rather than inferring it.

- [x] **Step 3: Run the wallet tests, then everything — and prove the capability**

Run: `./mvnw test -Dtest='AppleWalletPassServiceTest,WalletConfigGateTest,PublicTicketAssetControllerTest'`
Expected: PASS with no source changes. If `PKPass.builder()`, `PKEventTicket.builder()`, `PKSigningInformationUtil` or `PKInMemorySigningUtil` moved, fix the call sites here — that is the whole point of doing the bump as its own commit. *(Nothing moved. See "What the bump actually did" above.)*

**Added beyond the plan:** `src/test/java/com/imin/iminapi/service/ticket/JpasskitCapabilityTest.java`. A version string in `pom.xml` cannot say why it is there, and an upgrade justified entirely by "0.4.1 cannot emit `relevantDates`" with nothing asserting that claim is an unverified upgrade. The test builds a real event ticket, signs it with `WalletTestCerts`, and reads `relevantDates` and `preferredStyleSchemes` back out of the archive's `pass.json`; a third case pins the manifest digest to **SHA-1** so nobody "modernises" it to SHA-256 and ships passes Apple rejects. Verified to fail on a downgrade to 0.4.1 — `NoClassDefFound de/brendamour/jpasskit/PKRelevantDate` and `NoSuchMethod …preferredStyleSchemes(java.util.List)`.

Run: `./mvnw test`
Expected: fully green.

- [x] **Step 4: Commit**

```bash
git add pom.xml src/test/java/com/imin/iminapi/service/ticket/JpasskitCapabilityTest.java
git commit -m "chore(wallet): upgrade jpasskit 0.4.1 -> 0.5.8 for relevantDates"
```

---

## Task 3: Apple pass.json — the fields that make an offline ticket work

Size: **M**. Gated on nothing (drives real signing via `WalletTestCerts`).

The pass generates and signs correctly today. What it does not do is **behave like an event ticket on the device**: it never surfaces on the lock screen, it never expires, it renders in Apple's default white, it carries no venue address, and it will happily mint a pass for a refunded ticket.

**Files:**
- Modify: `src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java:90-154, 224-243`
- Create: `src/main/java/com/imin/iminapi/service/ticket/WalletEligibility.java`
- Test: `src/test/java/com/imin/iminapi/service/ticket/ApplePassContentTest.java`

**Interfaces:**
- Produces: `WalletEligibility.assertLive(Ticket)` — throws `409` for `refunded` (`TICKET_ALREADY_REFUNDED`) and `revoked` (`INVALID_STATE`); `WalletEligibility.isLive(Ticket) : boolean` for the Task 8 contract; `AppleWalletPassService.generatePass(String)` unchanged in signature
- Consumes: `Event.getVenueLatitude()/getVenueLongitude()` (V80 — **nullable**, geocoding is off by default), `Organization.getBrandName()`, `EmailProperties.getBuyerSiteBaseUrl()`

---

- [x] **Step 1: Write the failing test**

This is a plain unit test, not a Spring test — mirror `AppleWalletPassServiceTest`, which is the established pattern for this service. It unzips the signed archive and reads `pass.json`, so it asserts on **what Apple will actually see**, not on an intermediate object we could get wrong in the same direction twice.

Create `src/test/java/com/imin/iminapi/service/ticket/ApplePassContentTest.java`:

```java
package com.imin.iminapi.service.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.security.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What ends up in pass.json, asserted from the signed archive rather than from
 * the builder — the archive is the only artifact Apple ever sees.
 */
class ApplePassContentTest {

    /**
     * The field the whole feature turns on. Without a relevant date iOS never
     * surfaces the pass on the lock screen, which is the entire reason a pass
     * beats a screenshot at a door with no signal.
     *
     * <p>It is the ARRAY form: Apple deprecated the scalar `relevantDate` in
     * favour of `relevantDates`, and jpasskit only emits the array correctly
     * from 0.5.5 — hence Task 2 being a prerequisite rather than a tidy-up.
     */
    @Test
    void relevantDatesCarriesTheEventStart() throws Exception {
        JsonNode pass = passJson(fixture());
        assertThat(pass.path("relevantDates")).hasSize(1);
        JsonNode window = pass.path("relevantDates").get(0);
        assertThat(window.path("startDate").asText()).startsWith("2026-06-15T20:00:00");
        assertThat(window.path("endDate").asText()).isGreaterThan(window.path("startDate").asText());
        assertThat(pass.has("relevantDate"))
                .as("the scalar form is deprecated and PKPassBuilder still offers "
                        + "it at :348 — do not emit both")
                .isFalse();
    }

    /**
     * A pass that never expires is still in the wallet a year later. Apple
     * demotes an expired pass instead of leaving it in rotation, which is the
     * closest thing to an update we get without a web service — see ADR-0004.
     */
    @Test
    void expirationDateIsSetAndAfterTheEventEnds() throws Exception {
        JsonNode pass = passJson(fixture());
        assertThat(pass.hasNonNull("expirationDate")).isTrue();
        assertThat(pass.path("expirationDate").asText())
                .isGreaterThan(pass.path("relevantDates").get(0).path("startDate").asText());
    }

    /**
     * A door time rendered in the DEVICE's timezone is a wrong door time. A
     * buyer who flies in the night before must see 22:00 Paris, not 22:00
     * wherever their phone thinks it is.
     */
    @Test
    void theDoorTimeIgnoresTheDeviceTimezone() throws Exception {
        JsonNode pass = passJson(fixture());
        JsonNode when = fieldByKey(pass, "secondaryFields", "when");
        assertThat(when.path("ignoresTimeZone").asBoolean())
                .as("without ignoresTimeZone iOS converts the instant to the device zone")
                .isTrue();
    }

    @Test
    void venueAddressIsOnTheBack() throws Exception {
        JsonNode pass = passJson(fixture());
        JsonNode venue = fieldByKey(pass, "backFields", "address");
        assertThat(venue.path("value").asText())
                .contains("Le Petit Bain").contains("Paris");
    }

    @Test
    void brandColoursAreSetSoThePassIsNotDefaultWhite() throws Exception {
        JsonNode pass = passJson(fixture());
        assertThat(pass.path("backgroundColor").asText()).isNotBlank();
        assertThat(pass.path("foregroundColor").asText()).isNotBlank();
        assertThat(pass.path("labelColor").asText()).isNotBlank();
    }

    /**
     * Semantics drive the richer iOS event-ticket presentation. venueName only
     * exists on jpasskit >= 0.5.x — Task 2 is a prerequisite for this assertion.
     */
    @Test
    void semanticsCarryTheEventAndVenue() throws Exception {
        JsonNode semantics = passJson(fixture()).path("semantics");
        assertThat(semantics.path("eventName").asText()).isEqualTo("Saturn Night");
        assertThat(semantics.path("venueName").asText()).isEqualTo("Le Petit Bain");
    }

    /**
     * Location is opt-in data: IMIN_GEOCODING_ENABLED is false by default and
     * events.venue_latitude/longitude stay NULL. Emitting a locations array with
     * nulls in it produces a pass Apple rejects, so the absence must be handled,
     * not assumed away.
     */
    @Test
    void locationsAreOmittedEntirelyWhenTheVenueHasNoCoordinates() throws Exception {
        Fixture f = fixture();
        f.event.setVenueLatitude(null);
        f.event.setVenueLongitude(null);
        JsonNode pass = passJson(f);
        assertThat(pass.has("locations")).isFalse();
    }

    @Test
    void locationsArePresentWhenTheVenueHasCoordinates() throws Exception {
        Fixture f = fixture();
        f.event.setVenueLatitude(48.8330);
        f.event.setVenueLongitude(2.3760);
        JsonNode pass = passJson(f);
        assertThat(pass.path("locations")).hasSize(1);
    }

    /**
     * The one thing we do about revocation without a web service: never mint a
     * pass for a ticket that is already dead. See ADR-0004.
     */
    @Test
    void aRefundedTicketIsRefusedRatherThanSigned() {
        Fixture f = fixture();
        f.ticket.setState(Ticket.STATE_REFUNDED);
        assertThatThrownBy(() -> f.generate())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void aRevokedTicketIsRefused() {
        Fixture f = fixture();
        f.ticket.setState(Ticket.STATE_REVOKED);
        assertThatThrownBy(f::generate).isInstanceOf(ApiException.class);
    }

    /**
     * A redeemed ticket is NOT refused. Re-adding the pass after the door is
     * harmless, and a buyer whose phone died mid-queue must not be locked out of
     * their own ticket record.
     */
    @Test
    void aRedeemedTicketStillMintsAPass() throws Exception {
        Fixture f = fixture();
        f.ticket.setState(Ticket.STATE_REDEEMED);
        assertThat(passJson(f).path("serialNumber").asText()).isEqualTo("TKT_X");
    }

    /**
     * Latent 500: formatWhen() runs OUTSIDE the try block, so ZoneId.of() on a
     * bad stored zone escapes as a ZoneRulesException and the controller answers
     * 500 with no envelope. events.timezone is NOT NULL DEFAULT 'UTC'
     * (V6__events.sql:12) so the trigger is narrow, but "narrow" is not "handled".
     */
    @Test
    void aMalformedTimezoneDoesNotEscapeAsAnUnhandledException() throws Exception {
        Fixture f = fixture();
        f.event.setTimezone("Not/AZone");
        // Degrades to UTC rendering; it must not throw.
        assertThat(passJson(f).path("serialNumber").asText()).isEqualTo("TKT_X");
    }

    // ── fixture + helpers ────────────────────────────────────────────────────
    // Build a Fixture holding mutable Ticket/Order/Event plus wired mocks, and a
    // generate() that returns the signed bytes. Copy the mock wiring verbatim
    // from AppleWalletPassServiceTest:54-83 — including WalletTestCerts.generate()
    // for real key material. Do NOT substitute a stub signer: a pass that a stub
    // "signed" proves nothing about a pass Apple will accept.

    private static JsonNode passJson(Fixture f) throws Exception {
        byte[] pkpass = f.generate();
        String json = readZipEntry(pkpass, "pass.json"); // as in AppleWalletPassServiceTest:123
        return new ObjectMapper().readTree(json);
    }

    private static JsonNode fieldByKey(JsonNode pass, String group, String key) {
        for (JsonNode n : pass.path("eventTicket").path(group)) {
            if (key.equals(n.path("key").asText())) return n;
        }
        throw new AssertionError("no " + group + " field with key " + key);
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ApplePassContentTest`
Expected: **FAIL on every assertion except the two "still mints" cases** — none of these fields are emitted today. `aRefundedTicketIsRefused` fails by *not* throwing, which is the point.

- [x] **Step 3: Add the shared eligibility rule**

Create `src/main/java/com/imin/iminapi/service/ticket/WalletEligibility.java`:

```java
package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Whether a ticket may be turned into a wallet pass. One rule, both wallets.
 *
 * <h2>Why this is the only revocation control we have</h2>
 *
 * <p>We do not run an Apple pass-update web service and we do not patch Google
 * objects (ADR-0004). A pass already on a device therefore never changes. The
 * one thing entirely within our control is refusing to hand out a NEW pass for a
 * ticket that is already dead, and that is what this does.
 *
 * <p>It is not a security control. The door is: {@code TicketRedeemService}
 * re-reads state inside a transaction and rejects refunded and revoked tickets
 * regardless of what the buyer is holding. This exists so we do not actively
 * hand someone a fresh, official-looking artifact for a ticket we already
 * refunded.
 *
 * <p><b>Redeemed is deliberately allowed.</b> Re-adding a pass after the door is
 * harmless, and a buyer whose phone died in the queue must not be locked out of
 * their own ticket record.
 */
public final class WalletEligibility {

    private WalletEligibility() {}

    public static void assertLive(Ticket t) {
        if (Ticket.STATE_REFUNDED.equals(t.getState())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_ALREADY_REFUNDED,
                    "This ticket was refunded and cannot be added to a wallet");
        }
        if (Ticket.STATE_REVOKED.equals(t.getState())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "This ticket was revoked and cannot be added to a wallet");
        }
    }

    /** The same rule as a predicate, for the response contract in Task 8. */
    public static boolean isLive(Ticket t) {
        return !Ticket.STATE_REFUNDED.equals(t.getState())
                && !Ticket.STATE_REVOKED.equals(t.getState());
    }
}
```

- [x] **Step 4: Rewrite the pass body**

In `AppleWalletPassService.generatePass`, after loading `Ticket`/`Order`/`Event`, call `WalletEligibility.assertLive(t)`. Then replace the builder block (`:109-127`) with the full field set. The important parts, with the reasoning that must survive into the code:

```java
        ZoneId zone = zoneOf(event);   // never throws — see zoneOf below

        PKGenericPassBuilder eventTicket = PKEventTicket.builder()
                .headerField(PKField.builder().key("tier").label("Ticket").value(nullSafe(t.getTierName())).build())
                .primaryField(PKField.builder().key("event").label("Event").value(nullSafe(event.getName())).build())
                // value(Instant) + dateStyle/timeStyle hands iOS a real date so it
                // renders in the DEVICE's locale — but ignoresTimeZone(true) stops
                // it converting the instant into the device's zone. A buyer who
                // flew in last night must read the venue's door time, not theirs.
                .secondaryField(PKField.builder().key("when").label("Doors")
                        .value(event.getStartsAt())
                        .dateStyle(PKDateStyle.PKDateStyleMedium)
                        .timeStyle(PKDateStyle.PKDateStyleShort)
                        .ignoresTimeZone(true)
                        .build())
                .secondaryField(PKField.builder().key("where").label("Venue")
                        .value(formatWhere(event)).build())
                .backField(PKField.builder().key("address").label("Address")
                        .value(formatFullAddress(event))
                        // Lets iOS turn the address into a Maps tap on the back
                        // of the pass — the "Get directions" affordance the app
                        // design asks for, for free.
                        .dataDetectorType(PKDataDetectorType.PKDataDetectorTypeAddress)
                        .build())
                .backField(PKField.builder().key("order").label("Order")
                        .value(order.getToken()).build())
                .backField(PKField.builder().key("manage").label("Manage this ticket")
                        .value(buyerSiteBase + "/tickets/" + t.getToken())
                        .dataDetectorType(PKDataDetectorType.PKDataDetectorTypeLink)
                        .build());

        PKPassBuilder pass = PKPass.builder()
                .passTypeIdentifier(props.getPassTypeId())
                .teamIdentifier(props.getTeamId())
                .serialNumber(t.getToken())
                .organizationName("imin")
                .logoText(brandOrImin(org))
                .description(nullSafe(event.getName()) + " — " + nullSafe(t.getTierName()))
                // THE field. Without it iOS never surfaces the pass on the lock
                // screen, and a pass that does not appear when you reach the door
                // is no better than the screenshot it was meant to replace.
                //
                // relevantDates (plural), NOT the deprecated scalar
                // PKPassBuilder.relevantDate(Instant) at :348 — that one still
                // exists in 0.5.8 and is the easy mistake. Emit one or the other,
                // never both. jpasskit only serialises the array correctly from
                // 0.5.5, which is why Task 2 is a prerequisite.
                //
                // An interval, not an instant: PKRelevantDate carries
                // date/startDate/endDate (PKRelevantDate.java:29-31), and a club
                // night is a window. iOS keeps the pass surfaced across it
                // instead of around one moment.
                .relevantDatesBuilder(PKRelevantDate.builder()
                        .startDate(event.getStartsAt())
                        .endDate(expiryOf(event)))
                // Apple demotes an expired pass instead of leaving it in rotation.
                // This is the only self-cleaning we get without a web service
                // (ADR-0004). End-of-event + 12h so an overrunning club night and
                // a late scan both still work.
                .expirationDate(expiryOf(event))
                // Groups every ticket on one order under one stack in Wallet
                // instead of N loose cards. Order id, not event id: a buyer at the
                // same event on two separate orders genuinely has two stacks.
                .groupingIdentifier("order-" + order.getId())
                .foregroundColor("rgb(244,242,251)")
                .backgroundColor("rgb(8,7,13)")
                .labelColor("rgb(154,150,173)")
                .barcodeBuilder(PKBarcode.builder()
                        .format(PKBarcodeFormat.PKBarcodeFormatQR)
                        .message(qrPayload)
                        // Apple's documented recommendation for QR. The payload is
                        // base64url + dots — pure ASCII, so this is lossless.
                        .messageEncoding("iso-8859-1")
                        .altText(t.getToken()))
                .semantics(PKSemantics.builder()
                        .eventName(nullSafe(event.getName()))
                        .eventType(PKEventType.PKEventTypeGeneric)
                        .eventStartDate(Date.from(event.getStartsAt()))
                        .venueName(nullSafe(event.getVenueName()))   // jpasskit >= 0.5.x, Task 2
                        .build())
                .pass(eventTicket);

        // Locations are opt-in data. IMIN_GEOCODING_ENABLED defaults to FALSE, so
        // venue_latitude/longitude are NULL on most rows. Emitting a locations
        // array containing nulls produces a pass Apple rejects, so this is a
        // conditional, not a mapping.
        if (event.getVenueLatitude() != null && event.getVenueLongitude() != null) {
            pass.location(PKLocation.builder()
                    .latitude(event.getVenueLatitude())
                    .longitude(event.getVenueLongitude())
                    .relevantText(nullSafe(event.getVenueName()))
                    .build());
        }
```

Colour values are the Night Kit tokens `--bg #08070d`, `--text #f4f2fb`, `--text2 #9a96ad`, which `imin-public/app/globals.css` and the design kit agree on token-for-token. **`--text2`, not the kit's `--text3` `#5f5b70`** — that value failed AA and was fixed in the 2026-06-10 critique; do not re-import it here.

Replace `formatWhen` with a non-throwing zone resolver:

```java
    /**
     * The event's zone, or UTC.
     *
     * <p>{@code events.timezone} is {@code NOT NULL DEFAULT 'UTC'}
     * ({@code V6__events.sql:12}) and values are derived from a fixed country
     * map, so a malformed one is unlikely — but {@link ZoneId#of} throws, and
     * before this the call sat OUTSIDE the try/catch in {@code generatePass},
     * so an unlikely value meant an unhandled 500 with no error envelope.
     * The old {@code ZoneId.systemDefault()} fallback was unreachable dead code
     * that read like a safety net: on Railway it would have silently rendered
     * UTC anyway, and on a developer's Mac it would have rendered their local
     * time — a wrong door time on a ticket.
     */
    private static ZoneId zoneOf(Event e) {
        try {
            return ZoneId.of(e.getTimezone());
        } catch (Exception ex) {
            log.warn("[wallet] event {} has an unusable timezone {} — rendering UTC",
                    e.getId(), e.getTimezone());
            return ZoneOffset.UTC;
        }
    }
```

`expiryOf(event)` = `endsAt` when set, else `startsAt + 12h`, plus 12h of slack either way. `brandOrImin(org)` = `org.getBrandName()` when non-blank, else `"imin"` — the organizer's name belongs on the ticket; `organizationName` stays `"imin"` because that is the merchant of record.

- [x] **Step 5: Run the tests**

Run: `./mvnw test -Dtest='ApplePassContentTest,AppleWalletPassServiceTest'`
Expected: PASS. The old test's assertions on `pass.json` containing `imin1.TKT_X.`, the pass type id, the event name, venue and tier all still hold — none of those fields moved.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java \
        src/main/java/com/imin/iminapi/service/ticket/WalletEligibility.java \
        src/test/java/com/imin/iminapi/service/ticket/ApplePassContentTest.java
git commit -m "feat(wallet): complete the Apple event ticket — relevantDates, expiry, venue, colours"
```

---

## Task 4: Real pass artwork, and the iOS 18 poster event ticket

> ### AS BUILT (2026-08-16) — artwork shipped, **poster event ticket cut**
>
> **The poster event ticket is not adoptable by this product, and the reason is a single sentence in Apple's own minimum-requirements list.** From [*Creating a poster event pass using semantic tags*](https://developer.apple.com/documentation/walletpasses/creating-an-event-pass-using-semantic-tags), verbatim:
>
> > "Poster event tickets aren't compatible with tickets that require a QR code or barcode for entry."
>
> Every imin ticket is redeemed by scanning its QR at the door (`imin-tickets-gate` is a QR scanner; `TicketRedeemService` is driven by the signed payload). The poster layout is an NFC-entry layout. This is a product-level exclusion, not a missing field, and no amount of semantic-tag work reaches it. `preferredStyleSchemes` was therefore **not** added — `WalletArtworkTest.thePassDoesNotClaimAPosterEventTicketItCannotRender` pins its absence so a later reader does not "fix" the gap.
>
> Three further corrections to what this section asserts, each verified rather than assumed:
>
> 1. **It is an iOS 26 feature, not iOS 18.** Apple's current doc opens "In iOS 26 and later and watchOS 26 and later you can provide an engaging event ticket experience by creating a poster event tickets using semantic tags in Wallet." The iOS 18 framing dates from the WWDC24 announcement.
> 2. **The four required tags are an AND, not a shortfall we could absorb.** Apple lists `eventName`, `venueName`, `venueRegionName`, `venueRoom` as "Required tags for all event passes" and states: *"If you omit any of these tags, your pass falls back to the legacy event pass style."* We can supply two. `venueRoom` has no column on `Event` (confirmed: `grep -in "room\|performer\|lineup" Event.java` → nothing). **`venueRegionName` does not exist in jpasskit 0.5.8 at all** — `grep -rn "venueRegionName\|RegionName"` over the 0.5.8 sources returns nothing; `PKSemantics` carries only `venueName`, `venueLocation`, `venueEntrance`, `venuePhoneNumber`, `venueRoom`, and there is no `@JsonAnySetter` or map escape hatch to inject it. The plan's claim that it *"maps cleanly to `event.venueCity`"* is true of the data and false of the library. `performerNames` **is** in jpasskit but is required only for `PKEventTypeLivePerformance`, which we do not set. So the layout would not have triggered even with a certificate and a device.
> 3. **`primaryLogo.png` is not a Wallet asset**, and none of the sizes this section gives for the new assets are documented. The WWDC24 session introduces exactly two new assets, `artwork` and `secondaryLogo`, and names no dimensions; `primaryLogo` appears in neither the session nor the current doc. Apple has never published pixel specs for `artwork.png` (the developer-forum thread asking for them, 757181, has no Apple reply). **`358×448` in this section is unsourced.** No `artwork.png` was shipped: it would have meant an R2 round trip and a re-encode on the door path, at an invented size, for a layout that cannot render.
>
> **What did ship:** the six real PNGs, `WalletArtwork` with the missing-file fallback, and the icon size correction from the archived guide's 29pt to the current 38pt.
>
> | file | pixels | source |
> |---|---|---|
> | `icon.png` | 38×38 | `imin-public/public/logo-mark-light.png`, luminance-keyed, mark at 60% over opaque `#08070d` |
> | `icon@2x.png` | 76×76 | same |
> | `icon@3x.png` | 114×114 | same |
> | `logo.png` | 42×50 | same source, ink `#f4f2fb` on transparency, aspect-fit |
> | `logo@2x.png` | 84×100 | same |
> | `logo@3x.png` | 126×150 | same |
>
> The mark is 11:13, so Apple's 160×50pt cap binds on height and the width falls out of it — 42pt, nowhere near the 160 limit. `strip.png` was correctly identified as not an event-ticket asset and is absent; `background.png` and `thumbnail.png` are also absent, because the only image we hold that could fill them is the event's own poster, and that is the fetch this task declined to put on the door path.
>
> **The source file's trap is real and is documented in `WalletArtwork`'s javadoc:** `logo-mark-light.png` is a black mark on an *opaque white* field. Its alpha is 255 across the interior (only a ~4px transparent frame at the edge) and carries no shape whatsoever. Keying a mask off alpha yields a solid white rectangle, which on an `rgb(8,7,13)` pass is a white box that looks deliberate. `theLogoIsAMarkOnTransparency_notAnOpaqueBoxOfBackgroundColour` fails on exactly that mistake.
>
> Every assertion reads the image back out of a generated, signed archive, compares it byte-for-byte against the committed file, and checks the manifest digest covers it — proven by deleting `src/main/resources/wallet/` *and* `target/classes/wallet/` and watching two tests go red. Deleting only the source directory leaves a stale `target/classes` copy and the suite stays green: a size assertion alone cannot tell the real art from the same-sized placeholder, which is why the byte-identity test exists.
>
> **Still gated on G-CERT:** nothing in this task, now that the poster style is cut. Step 6 as written is void. The one device check that remains worth doing when a real Pass Type ID certificate exists is that the 38pt icon and the transparent logo render as intended on the near-black pass — cosmetic, not a blocker.
>
> `imin-public/docs/BRANDING.md:92-95` still describes this as unbuilt ("generates icon/logo PNGs procedurally … (placeholder). Drop real PNGs at …"). It is now stale in the opposite direction and needs a one-line correction in that repo.

Size: **M**. Not gated on any account — gated on someone handing over the brand mark. **Depends on Task 2.**

Today the icon and logo are drawn at runtime: a near-black rectangle with a white circle in the middle (`AppleWalletPassService.solidRect():192-212`). It satisfies Apple's schema and nothing else. Meanwhile `imin-public/docs/BRANDING.md:92,94` already tells readers the real files live at `imin-api/src/main/resources/wallet/*.png` — **that directory does not exist**, which is a doc describing a thing nobody built.

**Every logo mark in the workspace is a PNG, not the SVG `BRANDING.md` claims** — convenient, because pkpass requires PNG and would reject an SVG outright.

**The sizes currently in the code are stale.** `solidSquare(29)` matches Apple's *archived* Wallet guide; the current HIG specifies **icon at 38×38 points**. Ship @1x/@2x/@3x for everything. `strip.png` is **not** an event-ticket asset at all (it belongs to coupons and store cards) — the non-poster event ticket's optional artwork is `background.png` (343×503, rendered blurred behind the content) and `thumbnail.png` (60–90 wide × 90 high).

**Only `icon.png` is genuinely required.** Everything else is style-dependent, so the fallback in Step 3 is what keeps this task from being a hard blocker.

### The poster event ticket is nearly free, and we should take it

iOS 18 added a poster-style event ticket, opted into with a top-level array:

```json
"preferredStyleSchemes": ["posterEventTicket", "eventTicket"]
```

The second value is the legacy fallback, and Apple keeps it backward compatible — you still supply `primaryFields`/`secondaryFields`/`auxiliaryFields`, so an older device renders the classic pass from the same archive. jpasskit exposes it as `PKPassBuilder.preferredStyleSchemes(List<String>)` from 0.5.0 (absent in 0.4.1 — Task 2 again).

**The reason to care: we already have the artwork.** `artwork.png` wants **358×448**, which is 4:5 — and every imin poster is generated at 4:5 and stored in R2 at `events.poster_url`. `PosterImageStorage.download(url)` and `BrandLogoCompositor`'s decode-and-cache pattern are the exact seam. A buyer's lock screen showing the event's own poster instead of a grey card is the single largest perceived-quality jump available in this whole plan.

**What it costs, honestly:** the poster style requires semantic tags `eventName`, `venueName`, `venueRegionName` and `venueRoom`, plus `performerNames` for live-performance tickets. We have the first two. `venueRegionName` maps cleanly to `event.venueCity`. **`venueRoom` and `performerNames` have no backing field** — `grep -i 'lineup\|performer\|room' src/main/java` finds nothing on `Event` — and under the no-fabricated-data rule they must be omitted, not invented. So the poster style ships with two of its documented tags absent, and **Step 6 is a real-device check of what Apple actually does about that**, not an assumption that it degrades gracefully. If it refuses to render the poster layout, `preferredStyleSchemes` falls back to `eventTicket` by construction and we have lost nothing.

**Files:**
- Create: `src/main/resources/wallet/icon.png` (38×38), `icon@2x.png` (76×76), `icon@3x.png` (114×114), `logo.png` (≤160×50), `logo@2x.png`, `logo@3x.png`
- Create (poster style): `primaryLogo.png` (30–126 wide × 30 high) + @2x/@3x, `secondaryLogo.png` (12–135 wide × 12 high) + @2x/@3x
- Create: `src/main/java/com/imin/iminapi/service/ticket/WalletArtwork.java`
- Modify: `src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java:66-212`
- Test: `src/test/java/com/imin/iminapi/service/ticket/WalletArtworkTest.java`

---

- [x] **Step 1: Write the failing test** *(built differently — see the as-built note: the sketch below asserts on the loader, and the shipped test asserts on the signed archive)*

```java
package com.imin.iminapi.service.ticket;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The artwork actually shipped, at the dimensions Apple expects. A pass whose
 * icon is the wrong size is not rejected — it is rendered badly, forever, on
 * every buyer's phone, which is worse than a hard failure.
 */
class WalletArtworkTest {

    @Test
    void iconsAreCommittedAtTheThreeAppleScales() throws Exception {
        // 38pt, per the current HIG. The 29x29 in the code today comes from
        // Apple's ARCHIVED guide and is stale — an icon at the old size is not
        // rejected, it is just rendered wrong on every buyer's phone forever,
        // which is worse than a hard failure.
        assertSize("icon.png", 38, 38);
        assertSize("icon@2x.png", 76, 76);
        assertSize("icon@3x.png", 114, 114);
    }

    @Test
    void logosAreCommittedAndWithinApplesMaximum() throws Exception {
        // Apple caps the logo at 160x50 points; the retina files are the same
        // in points, twice/three times in pixels.
        BufferedImage logo = read("logo.png");
        assertThat(logo.getWidth()).isLessThanOrEqualTo(160);
        assertThat(logo.getHeight()).isLessThanOrEqualTo(50);
        assertThat(read("logo@2x.png").getHeight()).isEqualTo(logo.getHeight() * 2);
        assertThat(read("logo@3x.png").getHeight()).isEqualTo(logo.getHeight() * 3);
    }

    /**
     * Transparency is not decoration here: an opaque white box around the mark
     * would sit on the pass's near-black background as a visible rectangle.
     */
    @Test
    void artworkHasAnAlphaChannel() throws Exception {
        assertThat(read("logo.png").getColorModel().hasAlpha()).isTrue();
    }

    /**
     * The fallback must survive: if a file is ever deleted, passes must keep
     * generating with the placeholder rather than 500ing at the door.
     */
    @Test
    void aMissingFileFallsBackToTheGeneratedPlaceholderInsteadOfThrowing() {
        assertThat(WalletArtwork.load("does-not-exist.png", 29, 29)).isNotEmpty();
    }

    private static BufferedImage read(String name) throws Exception {
        byte[] bytes = WalletArtwork.load(name, 1, 1);
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static void assertSize(String name, int w, int h) throws Exception {
        BufferedImage img = read(name);
        assertThat(img.getWidth()).as(name + " width").isEqualTo(w);
        assertThat(img.getHeight()).as(name + " height").isEqualTo(h);
    }
}
```

(Fix the typo in the method name when you write it — it is there to force a read, not a copy-paste.)

- [x] **Step 2: Obtain and commit the six PNGs**

**This is the asset dependency, and it is the one thing in this task that is not code.** The mark must be a transparent PNG. If no suitable mark is available, the fallback in Step 3 keeps everything working and this step can land later — but do **not** ship the generated black-square placeholder to production and call the feature done; a buyer's lock screen showing a black square with a dot is worse than not shipping.

- [x] **Step 3: Add the loader with the fallback**

`WalletArtwork.load(name, w, h)` reads `classpath:/wallet/{name}`, and on absence or a decode failure returns the existing `solidRect(w, h)` output — moved here verbatim from `AppleWalletPassService`. Cache decoded bytes in a `ConcurrentHashMap` keyed by name; they never change at runtime. Log **once** at WARN per missing file, not per request.

- [x] **Step 4: Consume it and delete the six lazily-cached fields**

Replace `iconArt()` … `logo3xArt()` and the six `volatile byte[]` fields in `AppleWalletPassService` with calls to `WalletArtwork`.

- [x] ~~**Step 5: Opt into the poster style**~~ **CUT** — Apple: "Poster event tickets aren't compatible with tickets that require a QR code or barcode for entry." See the as-built note.

Add to the `PKPass.builder()` chain:

```java
                // iOS 18+ poster layout, with the classic layout as the declared
                // fallback in the SAME archive — Apple keeps this backward
                // compatible, and primaryFields/secondaryFields above are what an
                // older device renders. Adding this can therefore only improve
                // the pass, never break it.
                .preferredStyleSchemes(List.of("posterEventTicket", "eventTicket"))
```

and extend the semantics with what we actually hold:

```java
                        .venueName(nullSafe(event.getVenueName()))
                        .venueRegionName(nullSafe(event.getVenueCity()))
                        // venueRoom and performerNames are documented as required
                        // for this style and have NO backing field on Event.
                        // Omitted, not invented — see the no-fabricated-data rule.
                        // Step 6 is where we find out what Apple does about it.
```

Then attach the event's own poster as `artwork.png`, resized to 358×448 (4:5, which is exactly the ratio every imin poster is generated at). Fetch via `PosterImageStorage.download(event.getPosterUrl())`, decode/resize/encode with the Java2D toolkit `BrandLogoCompositor` already uses, and **cache by URL** the way that class does — a per-request R2 fetch and re-encode on the door path is not acceptable. **Wrap the whole thing in try/catch and degrade to no artwork**: this follows ADR-0002's precedent exactly — generation never fails over a decoration. A null `posterUrl` is normal and must be a clean skip, not a caught exception.

- [x] ~~**Step 6: Verify the poster style on a real iOS 18+ device** *(gated on **G-CERT**)*~~ **VOID** — there is no poster style to verify.

The only step in this task that a test cannot cover. Confirm: the pass adds; the poster layout renders rather than silently falling back; and if it does fall back, that the classic layout is intact. **Missing `venueRoom` / `performerNames` is the specific thing to look for** — the plan assumes graceful omission and has not verified it.

- [x] **Step 7: Run the tests, commit**

```bash
git add src/main/resources/wallet/ \
        src/main/java/com/imin/iminapi/service/ticket/WalletArtwork.java \
        src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java \
        src/test/java/com/imin/iminapi/service/ticket/WalletArtworkTest.java
git commit -m "feat(wallet): real pass artwork and the iOS 18 poster event ticket"
```

---

## Task 5: Google Wallet foundations — properties, credential, JWT signer

> ### AS BUILT (2026-08-16) — foundations shipped; eight plan defects, one of which made `origins` dead config
>
> Built as specified with four additions the plan needed and did not have. Full suite **2457** green (2426 baseline + 31 new). Nothing here is wired to an endpoint yet — that is Task 7.
>
> **The JWT is provably the one Google accepts, not merely a string.** Decoded from a real signing run (`GoogleWalletJwtSignerTest`, real 2048-bit RSA minted by `GoogleTestKeys`):
>
> ```
> HEADER {"typ":"JWT","alg":"RS256"}
> CLAIMS {"aud":"google","payload":{"eventTicketObjects":[{"id":"3388000000000000000.tkt_abc"}]},
>         "iss":"imin-wallet@imin-test.iam.gserviceaccount.com","origins":["https://app.imin.wtf"],
>         "typ":"savetowallet","iat":1786842450}
> SIGLEN 256
> ```
>
> `aud` is a bare string (not an array), `iat` is unix **seconds** (not millis), the signature is 256 bytes = RSA-2048 PKCS#1 v1.5. The test verifies it with `RSASSAVerifier` against the matching public key, rejects a signature from a *different* key, and rejects a token whose ticket id was swapped after signing — so the signature is proven to actually cover the payload. What no test here can prove, stated plainly: that `pay.google.com` accepts it. Google verifies against the public half of a key **it** issued. Only **G-ISSUER** closes that.
>
> **Four additions beyond the plan:**
> 1. `GoogleWalletProperties.gateReason() : Optional<String>` — see defect 3.
> 2. `GoogleWalletJwtSigner` is a `@Component` whose constructor parses the credential and logs the outcome — see defect 2. It never throws; a broken key must not stop the app booting.
> 3. `GoogleServiceAccountKey.parseBase64(...)` also accepts raw JSON and MIME-decodes wrapped base64 — a pasted key file and a line-wrapped blob are both reasonable, and neither should look like a corrupt credential.
> 4. `GoogleServiceAccountKeyTest` — 12 cases, over half of them about the key not escaping. See defect 7.
>
> **Production YAML binding verified, not assumed.** Bound `src/main/resources/application.yaml` through the real Boot `Binder` with the env vars set: all four keys bind, `GOOGLE_WALLET_ORIGINS=a,b` → a 2-element list. With nothing set: `enabled=false, fullyConfigured=false`, reason `GOOGLE_WALLET_ISSUER_ID is blank; GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64 is blank; GOOGLE_WALLET_ENABLED is false`. Worth doing: loading `classpath:/application.yaml` binds **nothing**, because the test resource replaces it — the trap §Global Constraints describes, hit live.
>
> ### Plan defects found while executing Task 5
>
> 1. **`origins` is configured, argued for at length, and never reaches a token.** Decision 2b spends a paragraph on it — *"shipping the field undefined in production is betting on a distinction the docs do not make for us"* — Step 3 makes it a property, and then **nothing in the file list reads both it and the key.** §Interfaces gives `GoogleWalletJwtSigner.sign(Map<String,Object>) : String` and shows the constructor taking only a parsed `GoogleServiceAccountKey`. Built as written, `GOOGLE_WALLET_ORIGINS` is dead config. Fixed by giving the signer a `(key, origins)` constructor and a properties constructor that supplies both.
> 2. **Boot-time credential validation is specified for Apple and silently dropped for Google.** §Defects opens with *"Nothing validates the certs until a buyer taps the button"* and Task 1 builds `WalletCredentialCheck` to fix it. Task 5's file list has no equivalent, no `@Component`, and nothing that constructs any of the three classes until Task 7 — so a garbage `GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64` reproduces the exact Apple bug the plan opened by fixing. Fixed in the signer's Spring constructor: parse once, log `ERROR` with the reason, keep serving everything else.
> 3. **A boolean gate repeats the `certPassword` mistake in a new place.** §Defects 1: a passwordless `.p12` gave *"no log line and no way to tell it apart from 'not configured yet'"*. Google has **three** closed states, and one of them — credentials complete, `enabled` still false — is the *expected* state for the entire development period per §"What is gated on what". `fullyConfigured()` cannot distinguish "nobody set this up" from "we are holding for Google's publishing review", and those want opposite responses from whoever reads the log. Fixed with `gateReason()`, which names the env var that closed the gate and says so explicitly in the demo-mode case.
> 4. **The JOSE header is unspecified, and Nimbus's default is wrong for it.** The plan pins the claim set — *"`iss` = the service-account email, `aud` = `"google"`, `typ` = `"savetowallet"`, `iat` = unix seconds"* — and says *"Signing is **RS256**"*, but never mentions the header's own `typ`. `new JWSHeader.Builder(JWSAlgorithm.RS256).build()` leaves it **null**; Google's reference sample sets `typ: JWT`. Two different fields named `typ`, one in the header and one in the claims, and the plan names only one of them.
> 5. **The plan's `aud` assertion cannot fail.** Step 1 writes `assertThat(claims.getAudience()).containsExactly("google")`. Nimbus normalises **both** `"aud":"google"` and `"aud":["google"]` to a one-element list on parse, so that assertion passes either way — while Google documents the bare string. Only decoding the claims segment can tell them apart; added `theAudienceIsABareStringNotAnArray` to do that.
> 6. **The one place in the plan that must parse JSON by hand is the one place it does not say which Jackson to use.** Task 2's as-built records that Jackson 2 (`com.fasterxml`, via jpasskit) and Jackson 3 (`tools.jackson`, Boot 4's HTTP converters) are both on the classpath, and the task brief flags picking wrong as a runtime failure a `catch` swallows. Step 4 says only that `parse(json)` *"reads `client_email` and `private_key`"*. Resolved by using neither: Nimbus's shaded `JSONObjectUtils`, already a hard dependency of the signer.
> 7. **The private key's own exposure surface is never mentioned.** The plan's only secrecy note is *"**Never log the serialised JWT**: it is a bearer artifact that mints a pass"* — nothing about the key that signs it. Two concrete holes in the sketch as given: (a) `GoogleServiceAccountKey` holds an `RSAPrivateKey` and is specified with no `toString()`, and both sibling helpers in this plan (`WalletTestCerts.Bundle`, `GoogleTestKeys.Bundle`) are **records**, whose generated `toString()` calls `RSAPrivateKey.toString()` — an identity hash on JDK 17, the modulus and private exponent on JDKs where `RSAPrivateCrtKeyImpl` printed them; (b) `parse` is specified to throw *"with a message naming the missing field"* with nothing forbidding it from naming that field's **value**, which for `private_key` is the secret. Fixed: explicit redacting `toString()`, every failure message written by hand with no cause attached and no echo of the input. Proven by mutation — re-introducing both leaks turns `GoogleServiceAccountKeyTest` red in 3 places; reverting turns it green.
> 8. **What blank `origins` *binds* to is asserted but never pinned.** *"Blank is the local dev default"* — verified true (an unset env var binds to an empty list), but nothing stopped `GOOGLE_WALLET_ORIGINS=""` or `"a,,b"` from shipping an empty-string origin Google can never match. The setter now drops blank elements, and the signer **omits** the claim rather than sending `"origins": []` — an empty array is a defined restriction to nothing, which is worse than an absent field, not equivalent to it.

Size: **M**. Gated on nothing to build and test; gated on **G-ISSUER** to switch on.

**No new dependency.** `com.nimbusds:nimbus-jose-jwt:10.4` is already on the classpath via `spring-security-oauth2-jose`, and `oauth/OidcJwtVerifier.java` and `oauth/AppleNativeIdentityService.java` already use it. `RSASSASigner` is what we need and it is right there. Do **not** add `google-api-client` or `google-auth-library`: they pull a large transitive tree to do RS256 over a JSON body and one HTTPS call, both of which Nimbus and `RestClient` already do.

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/ticket/google/GoogleWalletProperties.java`
- Create: `src/main/java/com/imin/iminapi/service/ticket/google/GoogleServiceAccountKey.java`
- Create: `src/main/java/com/imin/iminapi/service/ticket/google/GoogleWalletJwtSigner.java`
- Modify: `src/main/java/com/imin/iminapi/service/ticket/TicketConfig.java` — add to `@EnableConfigurationProperties`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/com/imin/iminapi/service/ticket/google/GoogleWalletJwtSignerTest.java`
- Test helper: `src/test/java/com/imin/iminapi/service/ticket/google/GoogleTestKeys.java`

**Interfaces:**
- Produces: `GoogleWalletProperties.fullyConfigured() : boolean`; `GoogleServiceAccountKey.parse(String json) : GoogleServiceAccountKey` with `clientEmail()`, `privateKey() : RSAPrivateKey`; `GoogleWalletJwtSigner.sign(Map<String,Object> payload) : String`
- Consumes: `com.nimbusds.jose.crypto.RSASSASigner`, `com.nimbusds.jwt.SignedJWT`

**The claim set below is verified against Google's Event-tickets JWT documentation**, not written from memory: `iss` = the service-account email, `aud` = `"google"`, `typ` = `"savetowallet"`, `iat` = unix seconds, `origins` = an array of approved domains, `payload` = the resource envelope. Signing is **RS256** with the service-account private key. The save URL is `https://pay.google.com/gp/v/save/{jwt}`.

---

- [x] **Step 1: Write the failing test**

The test that matters. A signer test that only checks "a string came out" is worthless; this one **verifies the signature with the matching public key** before it looks at a single claim. If the signing algorithm, the key, or the canonical JSON is wrong, verification fails and the test goes red — which is exactly what would happen at `pay.google.com`.

```java
package com.imin.iminapi.service.ticket.google;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google save-link signing, driven with a REAL RSA keypair generated at test
 * time and verified with its matching public key.
 *
 * <p><b>Why not a stub signer.</b> A double that returns a canned string would
 * keep this file green through every mistake that actually matters: the wrong
 * algorithm, the wrong key, a claim set Google rejects, a payload serialised in
 * a way that breaks the signature. Google verifies the signature; so does this
 * test. That is the only version of this test worth having.
 */
class GoogleWalletJwtSignerTest {

    @Test
    void theSignatureVerifiesWithTheServiceAccountsPublicKey() throws Exception {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        GoogleWalletJwtSigner signer = new GoogleWalletJwtSigner(
                GoogleServiceAccountKey.parse(keys.serviceAccountJson()));

        SignedJWT jwt = SignedJWT.parse(signer.sign(Map.of("eventTicketObjects", List.of())));

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.verify(new RSASSAVerifier(keys.publicKey())))
                .as("Google verifies this signature; if it fails here it fails there")
                .isTrue();
    }

    @Test
    void theClaimSetIsWhatGoogleExpects() throws Exception {
        GoogleTestKeys.Bundle keys = GoogleTestKeys.generate();
        var signer = new GoogleWalletJwtSigner(GoogleServiceAccountKey.parse(keys.serviceAccountJson()));

        SignedJWT jwt = SignedJWT.parse(signer.sign(Map.of("eventTicketObjects", List.of())));
        var claims = jwt.getJWTClaimsSet();

        assertThat(claims.getIssuer()).isEqualTo(keys.clientEmail());
        assertThat(claims.getAudience()).containsExactly("google");
        assertThat(claims.getStringClaim("typ")).isEqualTo("savetowallet");
        assertThat(claims.getIssueTime()).isNotNull();
    }

    /**
     * A wrong key must not silently produce a JWT that "looks fine". This is the
     * negative that proves the positive above is not vacuous.
     */
    @Test
    void aSignatureFromADifferentKeyDoesNotVerify() throws Exception {
        GoogleTestKeys.Bundle a = GoogleTestKeys.generate();
        GoogleTestKeys.Bundle b = GoogleTestKeys.generate();
        var signer = new GoogleWalletJwtSigner(GoogleServiceAccountKey.parse(a.serviceAccountJson()));

        SignedJWT jwt = SignedJWT.parse(signer.sign(Map.of("eventTicketObjects", List.of())));
        assertThat(jwt.verify(new RSASSAVerifier(b.publicKey()))).isFalse();
    }

    @Test
    void aMalformedServiceAccountJsonFailsLoudlyAtParseTime() {
        assertThatThrownBy(() -> GoogleServiceAccountKey.parse("{\"not\":\"a key\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Unset config must be "off", never "on with an empty key". */
    @Test
    void unconfiguredPropertiesAreNotFullyConfigured() {
        assertThat(new GoogleWalletProperties().fullyConfigured()).isFalse();
    }

    @Test
    void anIssuerIdWithoutAServiceAccountIsNotFullyConfigured() {
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setEnabled(true);
        p.setIssuerId("3388000000000000000");
        assertThat(p.fullyConfigured()).isFalse();
    }

    /**
     * The demo-mode guard, pinned. Credentials alone must NOT turn Google on:
     * there is a window where they are correct, the endpoint works, and the pass
     * reaches nobody but issuer test accounts. Flipping `enabled` is the
     * deliberate last step after publishing access is granted.
     */
    @Test
    void completeCredentialsAreStillOffUntilEnabledIsSetExplicitly() {
        GoogleWalletProperties p = new GoogleWalletProperties();
        p.setIssuerId("3388000000000000000");
        p.setServiceAccountJsonBase64("eyJ9");
        assertThat(p.fullyConfigured())
                .as("enabled defaults false for Google — see GoogleWalletProperties#enabled")
                .isFalse();
        p.setEnabled(true);
        assertThat(p.fullyConfigured()).isTrue();
    }
}
```

`GoogleTestKeys.generate()` mints a 2048-bit RSA keypair, PEM-encodes the PKCS#8 private key, and assembles a service-account-shaped JSON (`type`, `client_email`, `private_key`, `private_key_id`, `project_id`). Model it on `WalletTestCerts` — same idea, same reason it exists.

- [x] **Step 2: Run to verify it fails** — compilation error, none of the three classes exist.

- [x] **Step 3: Properties**

```java
@ConfigurationProperties(prefix = "imin.google-wallet")
public class GoogleWalletProperties {
    /**
     * Master switch — and it defaults to FALSE, unlike imin.apple-wallet.enabled.
     *
     * <p>The asymmetry is deliberate. A new Google issuer account is in demo
     * mode: passes are only deliverable to accounts holding Admin/Developer on
     * the issuer or explicitly added as testers, and they carry a [TEST ONLY]
     * prefix. Publishing access is a separate review that CANNOT be requested
     * until at least one Passes Class exists — i.e. until this code has already
     * run against production Google.
     *
     * <p>So there is a window where the credentials are correct, the endpoint
     * works, and the pass reaches nobody. With an enabled-by-default switch,
     * setting the issuer id to get through stage 1 would light the CTA for every
     * real buyer during exactly that window. Default false makes turning it on
     * the last deliberate step after approval lands.
     */
    private boolean enabled = false;
    /** Issuer ID from the Google Pay & Wallet Console. Blank ⇒ off. */
    private String issuerId = "";
    /**
     * The service-account JSON key, base64-encoded.
     *
     * <p>Base64 for the same reason the Apple p12 is: the raw file is multi-line
     * JSON containing a PEM block with embedded "\n" escapes, and every layer
     * between a dashboard field and System.getenv mangles at least one of them.
     * A single base64 blob has no escaping to get wrong.
     */
    private String serviceAccountJsonBase64 = "";
    /**
     * Approved domains the save link may be initiated from.
     *
     * <p>Google's JWT docs warn that the Add-to-Google-Wallet button "will not
     * render when the origins field is not defined", failing as
     * X-Frame-Options / "Refused to display". That warning is about the
     * embeddable JS button rather than the plain 302 this service uses, so it
     * may not bite here — but shipping the field undefined in production is
     * betting on a distinction the docs do not make for us. Blank is the local
     * dev default; production sets it.
     */
    private List<String> origins = List.of();

    public boolean fullyConfigured() {
        return enabled && !isBlank(issuerId) && !isBlank(serviceAccountJsonBase64);
    }
    // getters/setters, isBlank as in AppleWalletProperties
}
```

**Every field needs a Java default**, because `src/test/resources/application.yaml` replaces the main YAML and carries no `imin.google-wallet` block — a value that lives only in the main file is invisible to every test.

`application.yaml`, beside `imin.apple-wallet`:

```yaml
  google-wallet:
    # Google Wallet Event Ticket passes. Blank issuer-id or service account ⇒
    # /google-wallet returns 503 and the save CTA is suppressed. Independent of
    # imin.apple-wallet: either wallet can be live without the other.
    #
    # DEFAULTS FALSE, unlike apple-wallet.enabled — a new Google issuer account
    # is in demo mode until publishing access is granted, and that request cannot
    # be made until a class already exists in production. See
    # GoogleWalletProperties#enabled. Flip this true only after approval.
    enabled: ${GOOGLE_WALLET_ENABLED:false}
    # Issuer ID from the Google Pay & Wallet Console (a long numeric string).
    issuer-id: ${GOOGLE_WALLET_ISSUER_ID:}
    # Base64 of the service-account JSON key. The service account needs exactly
    # one scope: https://www.googleapis.com/auth/wallet_object.issuer
    service-account-json-base64: ${GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64:}
    # Approved origins for the save link. Blank is the dev default; set it in
    # production — see GoogleWalletProperties#origins.
    origins: ${GOOGLE_WALLET_ORIGINS:}
```

Register in `TicketConfig`:

```java
@EnableConfigurationProperties({TicketProperties.class, AppleWalletProperties.class,
        GoogleWalletProperties.class})
```

- [x] **Step 4: Credential + signer**

`GoogleServiceAccountKey.parse(json)` reads `client_email` and `private_key`, strips the PEM armour, base64-decodes and builds an `RSAPrivateKey` via `KeyFactory.getInstance("RSA")` + `PKCS8EncodedKeySpec`. It throws `IllegalArgumentException` with a message naming the missing field — a credential that half-parses is worse than one that does not parse.

`GoogleWalletJwtSigner.sign(payload)` builds `JWSHeader(RS256)` + a `JWTClaimsSet` with `iss` = client email, `aud` = `"google"`, `typ` = `"savetowallet"`, `iat` = now, `origins` when configured, and `payload` = the map. Signs with `RSASSASigner`. **Never log the serialised JWT**: it is a bearer artifact that mints a pass.

- [x] **Step 5: Run the tests, commit**

```bash
git commit -m "feat(wallet): Google Wallet credential loading and save-link JWT signing"
```

---

## Task 6: Event Ticket class + object, created through the REST API

Size: **M**. Gated on nothing to build; **G-ISSUER** to run against real Google.

Per Decision 2: both the class and the object are `insert`ed through `walletobjects`, and the JWT carries only the object id. The verified API surface:

| | |
|---|---|
| Base | `https://walletobjects.googleapis.com/walletobjects/v1/` |
| Class | `POST /eventTicketClass`, `GET /eventTicketClass/{resourceId}` |
| Object | `POST /eventTicketObject`, `GET /eventTicketObject/{resourceId}` |
| Scope | `https://www.googleapis.com/auth/wallet_object.issuer` — the only one |
| Class required fields | `id`, `eventName`, `issuerName`, `reviewStatus` |
| Object required fields | `id`, `classId`, `state` |
| Barcode required fields | `type`, `value` (`alternateText`, `renderEncoding` optional) |
| `state` enum | `ACTIVE`, `COMPLETED`, `EXPIRED`, `INACTIVE` |
| Id charset | alphanumeric plus `.`, `_`, `-`, and the id must be `{issuerId}.{identifier}` |

Two constraints that will bite if skipped:

- **`reviewStatus: "UNDER_REVIEW"` on insert.** `DRAFT` cannot be used to create any object, and leaving `draft` is one-way. `UNDER_REVIEW` is auto-promoted to `APPROVED` by the platform and is usable immediately — there is no human queue at *class* level (the human queue is the account-level publishing-access request, §What is gated on what).
- **`issuerName` should be ≤ 20 characters** — Google's own note is that longer strings truncate on small screens. `"imin"` is fine; an organizer's brand name may not be, so decide deliberately which one goes here rather than defaulting.

**Good news on ids:** ticket tokens are `TKT_<uuid>` and event ids are dashed UUIDs — uppercase alphanumeric, `_` and `-` — all inside Google's allowed set. **No sanitisation is needed, but assert it** rather than trusting this sentence.

**Files:**
- Create: `service/ticket/google/GoogleWalletModels.java` — records for the class and object payloads
- Create: `service/ticket/google/GoogleWalletApiClient.java` — `RestClient` wrapper + OAuth token acquisition
- Create: `service/ticket/google/GoogleWalletProvisioner.java` — idempotent class-then-object insert
- Test: `src/test/java/com/imin/iminapi/service/ticket/google/GoogleWalletModelsTest.java`
- Test: `src/test/java/com/imin/iminapi/service/ticket/google/GoogleWalletProvisionerTest.java`

> **As built** — `110d472`, `71fbf46`, `c0790d1` on `feat/wallet-apple-hygiene`. Full suite **2515 tests, 0 failures, 0 errors** (baseline 2457; +58 = 21 models, 17 client, 17 provisioner, 3 context).
>
> **Three additions beyond the plan's file list:**
> 1. `GoogleWalletConfig` — a named `googleWalletRestClient` bean with 5s connect and 5s read timeouts. See defects 4 and 5.
> 2. `GoogleWalletApiClientTest` — 17 cases over `MockRestServiceServer`, including opening the RFC 7523 assertion and verifying it against the public half of the signing key. The plan specified a test for the provisioner and none for the client, which is the layer where the wire lives.
> 3. `GoogleWalletContextTest` — boots the real context and asserts every Google Wallet bean constructs and is wired **closed**. This exists because the first attempt at `GoogleWalletApiClient` gave it two constructors and no `@Autowired`; Spring's implicit single-constructor rule needs exactly one, so the container went looking for a no-arg constructor and refused to start the context — **1075 tests erroring, none of them about wallets**. No unit test can see that.
>
> **`heroImage` is deliberately not sent, although `events.poster_url` exists.** Google's hero image is a ~3:1 banner and every imin poster is 4:5, so a buyer would see a horizontal slice through the middle of their own artwork; and Google fetches the URL when the **class** is inserted, so a poster that has since moved would fail the class insert permanently for every ticket to that event, because a class is created once and nothing patches it (ADR-0004). Apple arrived at the same place from the other end when Task 4 cut the poster event ticket. Pinned by `theEventPosterIsNotSentAsAHeroImage_setOrUnset`, which is the test to delete if this is ever revisited — and deleting it should require answering both objections.
>
> **Class per event, and the operational cost of that stated rather than implied.** A class holds `eventName`, `venue` and `dateTime`, so one shared class would put a single event's name on every ticket imin ever sells; that settles it. The consequence worth remembering is the blast radius in the other direction: **a class change propagates immediately to every object referencing it**, so per-event a bad edit reaches one night's holders instead of everyone. The price is real and accepted — there is no template inheritance between classes, so a change wanted on *all* events is N patches, and classes already created keep the shape they were created with. Nothing patches anything today, so new events get the new shape and old ones silently diverge. If a systematic class change is ever needed it is a backfill job over `events`, and it should be written as one rather than discovered as a bug report about one club's ticket looking different.
>
> **Ordering changed from the plan: eligibility, then the gate.** A refunded ticket is refunded whether or not Google Wallet is switched on, so `provision` throws `409` before it consults the gate — `503 "temporarily unavailable"` would be false and would invite a retry that can never succeed. It is the plan's own reason for checking the token before the config (Task 7, Step 1), applied one level down. Pinned by `aRefundedTicketIs409EvenWhenTheWalletIsOff`.
>
> ### Plan defects found while executing Task 6
>
> 1. **The one "good news" claim in the task is false about production data, and the conclusion survives by exactly one character.** *"**Good news on ids:** ticket tokens are `TKT_<uuid>` and event ids are dashed UUIDs — uppercase alphanumeric, `_` and `-` — all inside Google's allowed set."* `TKT_` appears nowhere in `src/main`; it is the **test fixture** token asserted at `AppleWalletPassServiceTest:104` (`"imin1.TKT_X."`). `PaidCheckoutService.randomToken()` is `Base64.getUrlEncoder().withoutPadding()` over 24 random bytes — mixed-case alphanumerics plus `-` and `_`. The conclusion holds only because base64**url** substitutes those two for base64's `+` and `/`; one character different in the token generator and every object id would be a 400 from Google. The plan said *"assert it rather than trusting this sentence"*, which was right, so the charset is now checked at runtime on every id and driven by 500 real tokens in the test.
> 2. **Step 4's get-then-insert on the object costs a round trip to learn what the insert already reports, and breaks the plan's own budget.** *"Get-then-insert for the class, then get-then-insert for the object, both tolerating `409` as success so two concurrent buyers cannot both create and neither fails."* Tolerating `409` achieves that goal by itself; the read adds nothing, and unlike the class there is no second field to inspect. §Decision 2 also states the ceiling as *"two synchronous outbound calls sit inside the request"* — get-then-insert on both is up to four, plus the token exchange, so the plan contradicts its own budget. Built as: read the class (for `reviewStatus` alone, see defect 6), insert-tolerating-409 for the object. Steady state after the first buyer is one call.
> 3. **Step 1's null-safety list names a field the task never specifies sending, and a field Google does not have.** *"a venue with no coordinates, a null `posterUrl`, a null `venueName` must each omit their field"*. `posterUrl` implies `heroImage`, which appears nowhere else in the task or its file list. And Google's `EventTicketClass.venue` is `{name, address}` — it carries **no coordinates at all**, so there is no field for them to be absent from. Meanwhile the real venue trap goes unmentioned anywhere in the plan: Google requires **both** `name` and `address`, so an event with a venue name and a blank address is a 400 — which, with `IMIN_GEOCODING_ENABLED` false by default, is the common case rather than the edge one. The whole block is omitted when either half is missing; two tests pin it.
> 4. **The client is specified in a shape that cannot be tested the way the same task requires it to be tested.** *"Create: `service/ticket/google/GoogleWalletApiClient.java` — `RestClient` wrapper + OAuth token acquisition"*, and then *"Its test mocks the HTTP layer"*. A class that builds its own `RestClient` cannot have `MockRestServiceServer` bound to it: the constructor's `requestFactory(...)` overwrites the mock and the suite quietly hits the real `walletobjects.googleapis.com`. This repository already documents that trap in `ExpoPushSenderTest`, and the plan names no config class and no bean. Fixed with `GoogleWalletConfig` and an injected client.
> 5. **No timeout is specified for calls the plan itself puts on the buyer's request thread.** §Decision 2: *"**Ceiling, stated:** two synchronous outbound calls sit inside the request."* Neither Task 6 nor Decision 2 sets a connect or read timeout, and there is no global HTTP default in this project — every client either sets its own or has none. A hung connection to `walletobjects.googleapis.com` would pin a Tomcat worker for as long as the OS allows. Set to 5s/5s, so a total outage costs a buyer about fifteen seconds across the worst path and then a 503 on one button.
> 6. **"`5xx` surfaces as `503`" leaves 4xx unmapped — and 4xx is what the failures this task exists to prevent actually look like.** *"Assert: a `409` on insert is success; a `5xx` surfaces as `503 UPSTREAM_UNAVAILABLE`, **never as a 500 to the buyer and never as a partially-created pass**."* The likely production failures are 401/403 (the service account not added to the issuer, or without `wallet_object.issuer`) and **400** — which is how a `DRAFT` class reports itself, the exact condition the task's own constraint list opens with. Built as written, every one of those hits `RestClient`'s default and throws `HttpClientErrorException`: a 500 to the buyer, precisely what the sentence forbids, through the statuses it forgot to name. Everything non-2xx is mapped to 503 here, with 401/403 logged as an operator fault naming the console fix.
> 7. **The token cache is specified as a phrase, and the response it caches is untrusted input.** *"Cache the access token until shortly before expiry."* "Shortly" is undefined, and nothing says what to do with an absent or absurd `expires_in`. Caching a token past its life turns one bad response into an outage lasting until the next deploy, and the symptom is 401s that no credential change fixes. Implemented with a 60s refresh margin and a rule that an out-of-range `expires_in` **shortens** the cache to 300s and can never lengthen it; pinned by `anAbsurdExpiresInShortensTheCacheRatherThanExtendingIt`.
> 8. **The task's one concurrency-correctness constraint has no owner and is currently vacuous.** *"**Must not run inside the read transaction** of the ticket lookup."* The transaction belongs to Task 7's service; nothing in Task 6 can enforce it and no Task 6 test can observe it. `GoogleWalletProvisioner` carries no `@Transactional` and today has no caller at all, so the constraint is satisfied by accident. **It must be re-asserted and tested in Task 7**, where the read transaction actually exists.
> 9. **The "one canonical payload" assertion cannot fail inside this task.** *"The barcode carries the **same** `qrSigner.sign(token)` string the pkpass and the emailed PNG carry — one canonical payload, three transports. Assert the literal `imin1.` prefix."* The models take `qrPayload` as a parameter, so the test feeds in the literal it then asserts — a tautology. The invariant only becomes real at the seam where the payload is produced, which is Task 7. **Task 7 must pass `QrPayloadSigner.sign(token)` through the real path and assert the object's barcode equals what the pkpass carries**, or this property is documented and unproven.

---

- [x] **Step 1: Write the failing model test** — assert the serialised JSON, not the record

The value is in the mapping, and the mapping is only visible after Jackson runs. Assert on the JSON tree:

- Class `id` is `{issuerId}.evt_{eventId}` and object `id` is `{issuerId}.tkt_{ticketToken}`, both matching `^[A-Za-z0-9._-]+$` after the issuer prefix.
- `reviewStatus` is `UNDER_REVIEW` — **and a test that it is never `DRAFT`**, because `DRAFT` is the value that silently makes every object insert fail.
- The barcode carries the **same** `qrSigner.sign(token)` string the pkpass and the emailed PNG carry — one canonical payload, three transports. Assert the literal `imin1.` prefix, the way `AppleWalletPassServiceTest:99` does. `type` is `QR_CODE`, `value` is the payload, `alternateText` is the bare token.
- `state` is `ACTIVE` for a live ticket.
- Venue and `dateTime` round-trip, with the start time carrying the **venue's** offset — the same door-time invariant Apple's `ignoresTimeZone` protects. Google takes an ISO-8601 string, so the offset must be the event's zone, not the server's.
- **Null-safety on optional blocks:** a venue with no coordinates, a null `posterUrl`, a null `venueName` must each omit their field rather than serialise `null`. `IMIN_GEOCODING_ENABLED` is false by default, so the no-coordinates case is the *common* one, not the edge case.

- [x] **Step 2: Build the models** — plain records + Jackson with `@JsonInclude(NON_NULL)`. Do not hand-roll JSON strings.

- [x] **Step 3: The API client**

`RestClient` + a bearer token minted from the service-account key (the same `GoogleServiceAccountKey` from Task 5, signing a `https://oauth2.googleapis.com/token` assertion). Cache the access token until shortly before expiry — a token fetch per pass save is an avoidable round trip on the door path.

- [x] **Step 4: The provisioner**

Get-then-insert for the class, then get-then-insert for the object, both tolerating `409` as success so two concurrent buyers cannot collide. **Must not run inside the read transaction** of the ticket lookup.

Its test mocks the HTTP layer — the subject is the idempotency and the error mapping, not Google. Assert: a `409` on insert is success; a `5xx` surfaces as `503 UPSTREAM_UNAVAILABLE`, **never as a 500 to the buyer and never as a partially-created pass**; a second call for the same event does not re-insert the class; and a failure creating the *class* does not go on to attempt the object.

- [x] **Step 5: Run, commit**

```bash
git commit -m "feat(wallet): Google Event Ticket class and object provisioning"
```

---

## Task 7: The save-link endpoint

Size: **M**. Gated on nothing to build; **G-ISSUER** to switch on.

```
GET /api/v1/public/tickets/{token}/google-wallet  →  302  https://pay.google.com/gp/v/save/<jwt>
```

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/ticket/google/GoogleWalletPassService.java`
- Modify: `src/main/java/com/imin/iminapi/controller/publicapi/PublicTicketAssetController.java`
- Test: `src/test/java/com/imin/iminapi/controller/publicapi/GoogleWalletEndpointTest.java`

**SecurityConfig needs no change.** `SecurityConfig:127` already blanket-permits `GET /api/v1/public/**`; the pkpass endpoint relies on exactly that and has no matcher of its own. **Verify this rather than adding a redundant line** — an extra matcher here is a second place to keep in sync.

> ### AS BUILT (2026-08-16) — `02ba47a`, `6881f1e` on `feat/wallet-apple-hygiene`
>
> Full suite **2542 tests, 0 failures, 0 errors** (baseline 2515; +27 = 20 endpoint, 3 provisioner, 3 controller, 1 context). Eight plan defects below.
>
> **Both obligations Task 6 carried forward are now discharged, and both were discharged by changing the shape the plan prescribed.**
>
> **1. "Must not run inside the read transaction" has an owner.** Task 6 recorded it as *"currently satisfied by accident — the provisioner has no caller"*. `GoogleWalletProvisioner.provision` now calls `assertNoTransactionOpen()` immediately before the first socket — after eligibility and after the config gate, because both of those refuse without opening one and have violated nothing, so a refunded ticket still gets 409 and a closed gate still gets 503 from inside a transaction. It throws `IllegalStateException`, not a graceful degrade: it is unreachable by any deployment or any input, only by an edit to a caller, and it should read like the broken build it is. Three tests, at three levels:
> - `GoogleWalletProvisionerTest#provisioningInsideATransactionIsRefusedBeforeAnySocketOpens` — sets the thread-local directly (no database needed) and proves the guard bites with nothing sent, against an expectation-free mock server.
> - `GoogleWalletEndpointTest#theSaveLinkPathGoesThroughTheNoTransactionGuard` — proves the guard is on *this* path and not bypassed by it. **The first draft of this test passed when the guard was deleted**: it asserted only "500", and with the guard gone the request went on to hit a mock server with no expectations and 500ed on *that* — same status, entirely different reason, certifying a guard that was no longer there. It now asserts the resolved exception by type and message. Worth recording because it is the exact failure mode §Global Constraints warns about, produced by a test I wrote against my own guard.
> - `GoogleWalletContextTest#theSaveLinkServiceIsNotWrappedInATransaction` — the static half, in the real container: the bean is not an AOP proxy and neither the class nor `saveUrl` carries `@Transactional`. A runtime guard only fires on a request that reaches it, and on every machine where the wallet is off nothing ever does.
>
> The edit all three exist to catch is a *reasonable-looking* one. `saveUrl` only reads rows, so `@Transactional(readOnly = true)` is what a reviewer would suggest — and it would hold a pooled JDBC connection across up to three 5s-timeout calls to Google on an **unauthenticated** endpoint, turning a slow upstream into pool exhaustion for the entire API. It needs concurrency plus a slow upstream to appear, so it would pass every other test in the repository. Proven by mutation: adding the annotation turns the context test red at the proxy assertion; removing the guard turns the other two red.
>
> **2. The "one canonical payload" assertion is no longer a tautology.** Task 6's models take `qrPayload` as a parameter, so its test asserted the literal it fed in. `GoogleWalletEndpointTest#allThreeTransportsCarryTheSameQrPayload` drives all three transports through their real code on one MockMvc — the object body Google would receive, the `/qr.png` PNG **decoded back out of its pixels** with zxing, and the barcode inside a genuinely signed `.pkpass` (`WalletTestCerts`, real RSA, real CMS signature) — and asserts the three are equal to each other and to `QrPayloadSigner.sign(token)`. Proven by mutation: replacing `qrSigner.sign(ticket.getToken())` with `ticket.getToken()` in the service turns it red. What it catches is any future change that gives one wallet a different payload from another — a bare token, a re-signature under a different secret, an id where the payload belongs. Today a door scanner accepts all three because they are the same bytes; this is the test that keeps that sentence true.
>
> **What the endpoint returns, per failure mode.** Gate closed (any of the three closed states) ⇒ `503 UPSTREAM_UNAVAILABLE`, nothing on the wire. `DRAFT` class ⇒ `503`, object insert never attempted, `ERROR` naming the console fix. Refunded ⇒ `409 TICKET_ALREADY_REFUNDED`; revoked ⇒ `409 INVALID_STATE`; both **before** the config gate, so they hold with the wallet off. Unknown token ⇒ `404`, before everything except the rate limiter. Google 4xx (401/403/400) ⇒ `503`, with 401/403 logged as an operator fault. Google 5xx ⇒ `503`. Timeout ⇒ `503` (5s connect + 5s read from `GoogleWalletConfig`, so ~15s worst case across three calls, on one button). Never a 500 from any of them — the only 500 reachable is the transaction guard, which no deployment can trigger.
>
> **Two build notes.**
> - **The Order row is not read.** Apple resolves the organization through `orders.findById(...).getOrgId()`; `events.getOrgId()` is the same organization, so the Google path reads two rows plus a nullable third instead of three plus a nullable fourth. One fewer round trip on a path a buyer waits on.
> - **The Apple endpoint was brought onto the same ordering.** `PublicTicketAssetController.applePass` consulted `isConfigured()` before eligibility, so with `APPLE_WALLET_ENABLED` unset a refunded ticket answered `503` rather than `409` — shipped code, not a plan defect, but it contradicts §Decision 1.5's *"one shared rule (Tasks 3 and 7)"*. Pinned by `PublicTicketAssetControllerTest#applePass_returns409ForARefundedTicketEvenThoughTheWalletIsUnconfigured`.
>
> ### Plan defects found while executing Task 7
>
> 1. **The test shape Step 1 prescribes cannot test the only class the task builds, and cannot discharge either obligation Task 6 handed it.** *"A `@SpringBootTest` mirroring `PublicTicketAssetControllerTest`, plus a `@MockitoBean GoogleWalletPassService` for the wired cases."* `GoogleWalletPassService` **is** the task. Mocked, every "wired case" asserts that a stub returns what the stub was told to return; and since the QR payload is produced inside that service, a mock never calls `QrPayloadSigner` at all, so Task 6's defect 9 could not be closed. The `@SpringBootTest` half is self-defeating too: the same paragraph notes that `src/test/resources/application.yaml` replaces the main YAML and carries no `imin.google-wallet` block, which is exactly why the gate cannot be opened there. Built as standalone MockMvc over the real service, provisioner, client and signer with only the HTTP transport doubled — the construction `PublicTicketAssetControllerWalletTest` next door already uses, for this same reason, on the Apple side.
> 2. **The warning about the mock is advice for a method the plan never gave the class, and taking it would have hidden the problem rather than fixed it.** *"a `@MockitoBean` answers `isConfigured()` ⇒ `false` by Mockito default … stub it in `@BeforeEach` or every case 404s/503s and the suite is green for the wrong reason."* §Interfaces for Tasks 5–6 specifies `fullyConfigured()`, `isUsable()` and `provision(...)` — no `isConfigured()` anywhere on the Google side; it is copied from Apple. And stubbing it `true` on a mocked service is not the cure for green-for-the-wrong-reason, it is the mechanism: it opens the gate on a component that has no gate and no behaviour behind it.
> 3. **"refunded ticket ⇒ 409" is listed as a case and is unreachable in the ordering the same step specifies.** Step 1 orders the checks token → config → …, and names unconfigured as *"the default under the test profile"*. A refunded ticket on that server answers `503`, not `409`, so the case as written cannot pass in the environment the case above it establishes. Task 6 had already found and fixed this one level down (*"eligibility, then the gate"*) and the plan did not propagate it up. Fixed by ordering the endpoint token → eligibility → config, on both wallets.
> 4. **"Google returning `5xx` ⇒ 503" repeats Task 6's defect 6 word for word, and 4xx is still unnamed.** The two failures this endpoint will actually have in production are 401/403 — a service account not added to the issuer, or without `wallet_object.issuer` — and 400, which is how a `DRAFT` class reports itself. Neither is a 5xx. Task 6 mapped everything non-2xx, so the built endpoint is correct; the plan's checklist would still have passed a build that 500s on both, because it never asks. Pinned at the HTTP layer by `a403FromGoogleIs503AndNotA500` and `aDraftClassIs503AndTheObjectIsNeverInserted`.
> 5. **The task asks for `SecurityConfig` to be verified, in a test shape that cannot verify it.** *"Verify this rather than adding a redundant line."* Standalone MockMvc — which is what defect 1 forces — runs no security filter chain, so a 302 there says nothing about `SecurityConfig:127`. Verified instead in `PublicTicketAssetControllerTest`, the `@SpringBootTest` that boots the real chain, where a change to the blanket `GET /api/v1/public/**` permit now surfaces as a 401/403 in the suite rather than in production.
> 6. **The 1800-character cap is specified as a test assertion and nothing else.** Nothing says what the *service* does when it is exceeded. Over the cap the link does not fail loudly at Google — it is truncated somewhere between the browser and Google, and produces a save page that fails for reasons no log line on our side can see. Built to refuse with a `503` and an `ERROR` naming the cause (*"the JWT payload is no longer just an object id"*), so the failure is legible on the day it happens; the test assertion is kept as well.
> 7. **`Cache-Control: private, no-store` is carried over from the pkpass with its reasoning left behind.** Step 1 lists it as a header to assert. For the pkpass it protects a response *body* on a shared cache. Here it protects a bearer artifact sitting in a `Location` **header**, on a `302` — a status code that caches, proxies and browser histories handle far more freely than a file download. Same header, materially stronger argument, and the plan carries across only the weaker one.
> 8. **"Same 404/409/503 shapes" (Decision 3) asserts a parity that does not exist on the 503.** For Apple, unconfigured is one condition. For Google it is three — disabled, unconfigured, and configured-but-unparseable — and Task 5 built `gateReason()` precisely because an operator needs to tell them apart. Collapsing all three into one buyer-facing 503 is right (a buyer can act on none of the difference) but it is a decision, and the plan states the parity instead of taking it.

---

- [x] **Step 1: Write the failing test**

A `@SpringBootTest` mirroring `PublicTicketAssetControllerTest`, plus a `@MockitoBean GoogleWalletPassService` for the wired cases. Remember: **a `@MockitoBean` answers `isConfigured()` ⇒ `false` by Mockito default**, so a `@TestPropertySource` cannot open the gate — stub it in `@BeforeEach` or every case 404s/503s and the suite is green for the wrong reason.

Cases:
- unconfigured (the default under the test profile, since `src/test/resources/application.yaml` has no `imin.google-wallet` block) ⇒ **503** with `$.error.code == "UPSTREAM_UNAVAILABLE"`
- unknown token ⇒ **404**, and the 404 must come **before** the config check so an attacker cannot use the status code to distinguish a real token from a fake one on an unconfigured server. Pin that ordering with an explicit test — it is the same ordering the pkpass endpoint already has (`PublicTicketAssetController:54-57`) and it is easy to lose in a refactor.
- refunded ticket ⇒ **409** (`WalletEligibility`)
- configured + live ⇒ **302**, `Location` starting with `https://pay.google.com/gp/v/save/`, `Cache-Control: private, no-store`
- the JWT in the `Location` verifies against the test public key, and its `payload.eventTicketObjects[0]` is **`{"id": "…"}` and nothing else** — assert the thinness explicitly, because an inline object that creeps back in is how the 1800-character URL limit gets breached in production and nowhere else
- **the whole `Location` URL is under 1800 characters** — one assertion, and it is the one that catches the regression Decision 2 exists to prevent
- Google returning `5xx` while provisioning ⇒ **503**, not 500, and no partially-created pass

- [x] **Step 2–4:** build the service (provision, then sign a thin JWT), wire the controller method (same shape as `applePass`, same `wallet-pass` rate-limit bucket), run.

- [x] **Step 5: Commit**

```bash
git commit -m "feat(wallet): Google Wallet save-link endpoint"
```

---

## Task 8: One wallet contract for two wallets and two platforms

Size: **M**. Gated on nothing.

`walletAvailable` was a single boolean for a single wallet on a single platform. There are now two of each. The question the brief asks — *what must `walletAvailable` become* — has a specific answer:

**It must become nothing. It stays exactly what it is, forever, and a new field carries the new information.**

- It is read today at `imin-public/lib/api/types.ts:243` and rendered at `ticket-view.tsx:161-165`.
- It is in `imin-webapp/docs/openapi.yaml:8865` and in the generated types.
- The Phase 0 plan's Task 8 exists entirely because shipped app binaries cannot be force-updated.
- And critically: **repurposing it to mean "either wallet" would light the Apple CTA on Android**, because the client-side gate is `walletAvailable && isApplePlatform()` — a true value from a Google-only server would produce a button that downloads a `.pkpass` an Android device cannot open.

So:

```java
public record PublicTicketResponse(
        String token,
        String state,
        String tierName,
        String qrPayload,
        String qrUrl,
        /**
         * @deprecated Apple only. Kept permanently — imin-public reads it and a
         * shipped app binary cannot be force-updated. Equal to
         * {@code wallet.apple.available}. New clients read {@link #wallet}.
         */
        @Deprecated boolean walletAvailable,
        Wallet wallet,
        Event event,
        Order order) {

    /**
     * What this server can mint for this ticket, per wallet.
     *
     * <p>{@code available} is a server fact: certificates are configured AND the
     * ticket is live. It is deliberately NOT a device fact — the same ticket URL
     * is legitimately opened on a laptop and forwarded to a phone, so the
     * platform gate belongs on the client, where
     * {@code imin-public/components/buyer/wallet-cta.tsx:8-16} already does it.
     *
     * <p>{@code url} is absolute and server-built so no client ever concatenates
     * a path again. The Google link cannot be client-built at all: it is a
     * signed JWT.
     */
    public record Wallet(Target apple, Target google) {
        public record Target(boolean available, String url) {}
    }
}
```

`available` folds in `WalletEligibility.isLive(ticket)`, so a refunded ticket reports `false` on both and the 409 from Tasks 3 and 7 becomes unreachable through the UI — belt and braces, because the endpoint is directly linkable from an old email.

Also in this task:
- **`PublicOrderResponse.Ticket` gains the same `wallet` block.** Today `imin-public/components/buyer/order-view.tsx:46,57` fetches a *whole extra ticket* through a `<Suspense>` boundary purely to learn `walletAvailable`, because the order response does not carry it. Adding it deletes that round trip.
- **`TicketIssuanceEmailer` grows the Google CTA** beside the Apple one (`:175-177` HTML, `:193-194` text), each gated on its own wallet's `isConfigured()`. Both branches already exist for Apple; this is symmetry, not new machinery.

**Test** (`WalletContractTest`): `walletAvailable == wallet.apple.available` in every state; both are `false` for a refunded ticket even when certs are configured; the URLs are absolute and point at this API's `imin.ticket.api-public-base-url`, not the buyer site.

- [ ] **Commit**

```bash
git commit -m "feat(wallet): two-wallet ticket contract, walletAvailable preserved"
```

---

## Task 9: ADR and the docs that are already wrong

Size: **S**.

- [ ] **Create `docs/decisions/ADR-0004-wallet-passes-are-not-updated.md`** — Decision 1 above, in the house ADR format (Context / Decision / Consequences), including the two triggers that reverse it and the explicit note that ticket transfer does not exist today.

- [ ] **Update `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md` §14** — **in the imin-public repo**; `imin-api/docs/PUBLIC_PAGE_API.md` is a stub that says it drifted and defers. Three edits:
  - §14.1 field table: `walletAvailable` marked deprecated-but-permanent, and the new `wallet` block documented with the three status codes (`404` unknown token, `409` non-live ticket, `503` wallet not configured).
  - §14.2 asset-endpoint table: add `GET …/google-wallet` → `302`.
  - **Fix the pre-existing drift at `:1186`**, which says `state` is one of `"issued" | "redeemed" | "revoked"`. There are four — `refunded` has been in `Ticket.java:53`, in `imin-public/lib/api/types.ts:226-231`, in `ticket-view.tsx:25,32,50-53` and in the gate's `RedeemOutcome` for months. This plan is the first thing to touch that section since; leave it correct.

- [ ] **Fix `imin-public/docs/BRANDING.md:92,94` and `imin-webapp/docs/BRANDING.md:90,92`** — both point at `imin-api/src/main/resources/wallet/*.png`, which only becomes true when Task 4 lands. Either land Task 4 first or correct the docs.

```bash
git commit -m "docs(wallet): ADR-0004 and the two-wallet public contract"
```

---

## Deployment and follow-through

- [ ] **Merge to `master` and let Railway deploy.** Direct push to the default branch needs explicit user OK.
- [ ] **Set the production env vars.** Every one of these is blank-by-default and blank means *that wallet is off*, nothing else:

| Var | Blank behaviour | Gate |
|---|---|---|
| `APPLE_WALLET_PASS_TYPE_ID` | Apple off: `/apple-wallet.pkpass` ⇒ 503, `wallet.apple.available` false, email CTA suppressed | **G-CERT** |
| `APPLE_WALLET_TEAM_ID` | as above | **G-CERT** |
| `APPLE_WALLET_CERT_P12_BASE64` | as above | **G-CERT** |
| `APPLE_WALLET_CERT_PASSWORD` | **After Task 1, blank is legal** — a passwordless `.p12` works. Before Task 1 it silently disabled Apple entirely | **G-CERT** |
| `APPLE_WALLET_WWDR_PEM_BASE64` | Apple off | **G-CERT** |
| `APPLE_WALLET_ENABLED` | defaults `true`; set `false` to disable without deleting the certificate from the environment | — |
| `GOOGLE_WALLET_ISSUER_ID` | Google off: `/google-wallet` ⇒ 503, `wallet.google.available` false | **G-ISSUER** |
| `GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64` | Google off | **G-ISSUER** |
| `GOOGLE_WALLET_ENABLED` | **defaults `false`** — deliberately asymmetric with Apple, because of the demo-mode window. This is the last switch flipped, after publishing access is granted | **G-ISSUER stage 2** |
| `GOOGLE_WALLET_ORIGINS` | blank = undefined origins. Fine for dev; **set it in production** — Google's docs warn the save button will not render with `origins` undefined, and the plan is not going to bet on that warning being scoped to the JS widget | — |

  **Nothing fails open.** A blank value never produces an unsigned pass, a partial pass, or a broken CTA — it produces an absent CTA and a 503 on a URL nothing links to. The one historical exception was the `certPassword` case, which failed closed for the *wrong reason*; Task 1 removes it.
- [ ] **G-CERT verification, on a real iPhone.** Generate a pass against the production certificate and add it to Wallet. `WalletTestCerts`'s own Javadoc says the synthetic chain would be rejected by Apple — the suite proves the archive is well-formed and correctly signed by the key it was given, and cannot prove more than that. **Apple Wallet is not done until this checkbox is ticked.** Check specifically: the pass adds without an error; `relevantDates` surfaces it on the lock screen near door time; the door time reads in the **venue's** zone; and (U5/U6) the poster layout renders on iOS 18+ despite the two absent semantic tags.
- [ ] **G-ISSUER, stage 1 — demo mode.** Set the issuer id and service account, deploy, and let the first save-link request create the first class. Add the pass on a real Android phone signed in as an issuer Admin/Developer. It will carry a `[TEST ONLY]` prefix; that is correct, not a fault. Check the barcode scans in the gate PWA.
- [ ] **G-ISSUER, stage 2 — publishing access.** Only possible *after* stage 1, because the request requires at least one Passes Class to exist. Complete the Business Profile including a payment profile, then request publishing access and wait for the Google Wallet team's response. **Until this lands, Google Wallet reaches nobody but test accounts** — so `wallet.google.available` being `true` in production before approval would light a CTA that fails for every real buyer. Keep `GOOGLE_WALLET_ENABLED=false` in production until approval arrives, and flip it as the last step.
- [ ] **Scan one of each with the real gate scanner** before announcing. The payload is supposed to be byte-identical across the emailed PNG, the web QR, the pkpass and the Google object — assert that in the field, not only in a test.
- [ ] **After the deploy is live**, run `npm run api:sync` in `imin-webapp` (its `api:fetch` curls the **production** OpenAPI URL, so FE types only reflect this once Railway has it) and reconcile `src/shared/api/types.ts`.
- [ ] **Then the frontend work in `imin-public`**, which is not in this plan but is what makes it visible:
  - `components/buyer/wallet-cta.tsx` gains an Android branch mirroring `isApplePlatform()` at `:8-16`, reading `wallet.google`.
  - `lib/api/public-events.ts:30-32`'s `ticketWalletUrl()` is replaced by the server-supplied `wallet.apple.url`.
  - `lib/i18n/{en,es,fr,uk}.ts` — `wallet.add` is Apple-specific ("Add to Apple Wallet"); a `wallet.addGoogle` is needed, and **`wallet.hint` at `en.ts:408` currently says "No Apple Wallet? Screenshot your QR"**, which becomes wrong the moment an Android buyer has a real option. All four locales in lockstep, `check:i18n` clean.
  - Neither CTA uses the official badge artwork today (`wallet-cta.tsx:38-45` explains why, and the reasoning applies identically to Google's badge). Both vendors mandate their own assets; obtaining them is a separate, cheap task and should be done before this is marketed.
- [ ] **`imin-webapp` needs nothing.** It has no wallet surface and should not grow one — passes are a buyer artifact.

---

## Known gaps, recorded rather than silently skipped

- **No pass updates, on either platform.** The whole of Decision 1 / ADR-0004. The concrete consequence: a buyer refunded *after* adding a pass keeps a pass that looks valid until the event expires it, and is correctly refused at the door. Revisit if refund volume on live events becomes material, or if ticket transfer ships.
- **`associatedStoreIdentifiers` is not set**, so the pass does not deep-link to the iOS app. It needs the numeric App Store id, which does not exist until the app is submitted (ADR-0003). One line to add later; recorded so it is not rediscovered.
- **No `thumbnail.png` or `background.png`** on the classic layout. Task 4 gives the poster style its `artwork.png` from `events.poster_url`; the classic fallback still renders without an image band. Low value once the poster style works, and it is the same fetch-and-re-encode cost for a layout most devices will not use. (`strip.png` is deliberately absent and always will be — it is a **coupon and store-card** asset, not an event-ticket one.)
- **The organizer's brand logo is not on the pass.** `Organization.brandLogoUrl` exists and `BrandLogoCompositor` already fetches and caches org logos for posters. Deferred: `logoText` carries the organizer's name (Task 3), which is most of the value for none of the per-request cost.
- **Locations are usually absent.** `IMIN_GEOCODING_ENABLED` defaults to `false` (`application.yaml`), so `venue_latitude/longitude` are NULL on most rows and the location-based lock-screen trigger never fires. The date-based trigger (`relevantDates`) still does. Turning geocoding on is a separate decision with a Nominatim rate-limit story attached.
- **Semantics are incomplete, and two of the gaps are data gaps not effort gaps.** `venueLocation` and `venueEntrance` are deferrable. `venueRoom` and `performerNames` are **documented as required for the poster event ticket and have no backing field anywhere** — `grep -i 'lineup\|performer' src/main/java` returns nothing. Under the no-fabricated-data rule they are omitted, not invented. If the poster layout turns out to need them (U6), the honest fix is a real line-up field on `Event`, which is a product decision, not a wallet task.
- **Google `notifyPreference` is never set**, so no Google-side push ever fires. Correct while ADR-0004 stands; noted because the field exists and is a one-line temptation. If it is ever used: **max 3 push-triggering updates per object per 24 hours**, and only a specific field list triggers one.
- **Google's `[TEST ONLY]` prefix persists until publishing access is granted.** Everything works in demo mode; the passes just say so, and only reach test accounts. Do not read that as a bug during development.
- **Pass generation is not cached.** Each request re-signs. Deliberate: a cache would have to be invalidated on refund and revoke to keep `WalletEligibility` honest, and a stale cached pass for a refunded ticket is exactly the failure this plan is trying to avoid. The `wallet-pass` rate-limit bucket bounds the cost instead.
- **The email carries no Google CTA until Task 8**, and after it the email cannot know the recipient's platform, so it will show both links. That is correct — an email is read on more devices than it is sent to — but it means an iPhone user sees a Google link. `TicketIssuanceEmailer` renders both or neither per wallet; there is no per-recipient targeting and there should not be.
- **jpasskit 0.5.8 pulls `pushy`**, an APNs client, transitively. It is unused. It is *not* a signal that the pass-update service is half-built; if a future plan reverses ADR-0004, that dependency is a convenience and not a head start.
- **Both wallets still sign with SHA-1** (`Hashing.sha1()` for the manifest, `SHA1withRSA` for the CMS signature) — verified in both jpasskit 0.4.1 and 0.5.8 source jars. That is what the library does and what Apple's format specifies; it is recorded here because a future security review will ask, and the answer is "the format, not our choice".

---

## Verification status

Everything about **this repository** in this plan was read from source: the jpasskit behaviour was confirmed by unzipping the 0.4.1 and 0.5.8 source jars, the frontend consumption by reading `imin-public`, the migration numbers from `git log` on `master`.

Everything about **Apple's and Google's APIs** was checked against current official documentation (developer.apple.com/documentation/walletpasses, the Wallet HIG, developers.google.com/wallet/tickets/events). The following is what remains genuinely unverified, marked rather than asserted.

| # | Still unverified | Affects | How to close it |
|---|---|---|---|
| **U1** | How quickly a Google object update reaches a device. Google documents that changes propagate "on sync" and publishes **no** interval or user action. | Nothing in this plan — recorded because it is the first fact someone will want if ADR-0004 is ever revisited. | Empirical, on a real device. |
| **U2** | Whether an inline-JWT class in `DRAFT` blocks the save. The docs only say a draft class cannot be used to create objects *via the API*. | Nothing — Decision 2 never uses `DRAFT` and never uses inline classes. | Moot unless both change. |
| **U3** | `apns-topic` = the Pass Type ID. Stated consistently across Apple's APNs guidance and developer forums, but not in one quotable line of the walletpasses reference. | Only a reversal of ADR-0004. | Moot while we build no web service. |
| **U4** | On-device behaviour of `voided: true` (Expired folder / greyed out). Community-reported; not in current Apple docs. Apple's own guidance is explicitly *not* to rely on voiding via push. | Nothing — we do not set `voided`, because we cannot update a pass to set it later, and setting it at mint time is what `WalletEligibility`'s 409 does better. | Moot. |
| **U5** | Pixel dimensions for poster-ticket assets beyond the HIG's point values; Apple publishes no @2x/@3x pixel table for `artwork.png`. | **Task 4, Step 5.** The 358×448 figure is points. | Ship @2x/@3x by the usual multiplication and confirm on device at Task 4 Step 6. |
| **U6** | Whether the poster event-ticket layout renders when `venueRoom` and `performerNames` are absent — both are documented as required and neither has a backing field. | **Task 4, Step 5.** | Task 4 Step 6, on a real iOS 18+ device. `preferredStyleSchemes` falls back by construction, so the downside is bounded. |
| **U7** | Which WWDR generation the issued certificate actually chains to. Apple's reference table says **G4** for Pass Type ID (required for certs issued after 2022-01-27, expiring 2030), but the authoritative answer is the certificate itself. | **G-CERT.** | Read the Organization field off the issued certificate; `WalletCredentialCheck` will fail loudly at boot if the supplied intermediate does not match, and jpasskit calls `checkValidity()` on it. |
