# imin-api Public-Contract Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the 5 public-API contract changes the imin-public branch fix/uiux-critique-2026-06 is deploy-gated on.
**Architecture:** The session→order lookup the success page needs is **already in production** as `GET /api/v1/public/checkout/{sessionId}` (`PublicCheckoutController` → `CheckoutStatusService`), so this plan no longer builds a duplicate endpoint — it repoints the FE poller at the existing one and documents it. The ticket payload's `qrPayload`/`walletAvailable` fields already ship via the existing QR signer and are pinned with a regression-guard test rather than new code. The listing gains an additive `includeOngoing` param and derived `soldOut`/`lowStock` flags guarded by the `list_responseItemKeysAreAllowListed` allowlist test, and the refund-form context gains a single additive `timezone` field. Every contract change updates `PUBLIC_PAGE_API.md` in the imin-public repo in lockstep (doc commits land on `fix/uiux-critique-2026-06`).
**Tech Stack:** Java 17, Spring Boot 4, Maven, JPA/Flyway, springdoc
**Deploy note:** merge+deploy imin-api BEFORE deploying imin-public; then run `npm run api:sync` in imin-webapp (its `api:fetch` pulls prod OpenAPI).

---

## Execution order

Ship the packages strictly in order:

1. **Package 1 — hard FE gates** (ticket payload guard + success-page repoint): the buyer ticket page 500s without `qrPayload`, and the success page poller was pointed at a `/checkout-sessions/` path the backend never shipped — it is repointed at the existing `GET /api/v1/public/checkout/{sessionId}` endpoint (no new backend endpoint, so **no BE deploy gate for the repoint** — it ships purely in imin-public).
2. **Package 2 — hard `/events` gate + soft chips**: `includeOngoing` fixes the listing's `from=now` doors-open regression (hard `/events` gate), and `soldOut`/`lowStock` feed the soft availability chips; the leak-allowlist test must move in the same change.
3. **Package 3 — cosmetic**: refund-form `timezone` only corrects a "UTC" time label.

All imin-api commits land on `feat/public-contract-companion` (created in Task 1.1; do not push). All `PUBLIC_PAGE_API.md` doc-sync commits land in the separate imin-public repo on `fix/uiux-critique-2026-06`.

| Companion item | Plan task(s) | imin-public FE task it unblocks (`docs/superpowers/plans/2026-06-10-uiux-critique-fixes.md`) |
|---|---|---|
| 1. Ticket payload: `qrPayload` + `walletAvailable` (hard gate) | Task 1.2 (guard test), Task 1.3 (doc §14.1) | Task 6.5 — rebuild the web-ticket page (inline-SVG QR, loud states, wallet gating) |
| 3. Session-to-order lookup (hard gate) | Task 1.4 (repoint FE poller at existing `/public/checkout/{sessionId}`), Task 1.5 (doc §10.4 — existing endpoint) | Task 8.1 — poll the existing checkout endpoint, surface "View your tickets" on success. **No BE deploy gate** — the endpoint already ships in prod, so the repoint lands in imin-public alone. |
| 4. `includeOngoing` listing filter (hard `/events` gate) | Task 2.1 (param + predicate), Task 2.2 (doc §9.1) | Task 8.6 — `from=now` hides in-progress events during their peak sales window |
| 5. `soldOut` / `lowStock` availability flags (soft chips) | Task 2.3 (flags + allowlist), Task 2.4 (doc §9.2) | Task 8.7 — card scannability: availability signal |
| 2. Refund-form venue `timezone` (cosmetic) | Task 3.1 (field), Task 3.2 (doc §17) | Task 7.2 — honestly label the refund-page event time |

---

## Package 1: Ticket payload (qrPayload + walletAvailable) and checkout-session lookup

This package closes two FE-gating contract holes. Item 1 (`qrPayload` + `walletAvailable` on `GET /api/v1/public/tickets/{token}`) is a **hard FE gate** — the buyer ticket page 500s without `qrPayload` — but the backend already emits both fields plus `qrUrl` from `PublicTicketResponse`/`PublicOrderController.getTicket`; the work is a regression-guard test plus a stale-doc rewrite of `PUBLIC_PAGE_API.md` §14.1. Item 3 (session → order lookup) was originally scoped as a net-new `GET /api/v1/public/checkout-sessions/{sessionId}` endpoint, but **the backend already ships an equivalent production endpoint**: `GET /api/v1/public/checkout/{sessionId}` (`PublicCheckoutController` → `CheckoutStatusService.statusFor`) returns `{"status":"ready","orderToken":"…"}` once the Order exists for that `stripe_session_id` and `{"status":"pending"}` otherwise, with `Cache-Control: private, no-store`. So this package no longer builds a duplicate endpoint, migration, or DTO — it **repoints the imin-public success-page poller** (which the FE branch wrote against the never-shipped `/checkout-sessions/` path) at the existing endpoint and documents that endpoint in `PUBLIC_PAGE_API.md` §10.4. The Stripe success URL already carries `session_id={CHECKOUT_SESSION_ID}`, so the id the FE holds is exactly the `cs_...` value `CheckoutStatusService` resolves via `OrderRepository.findByStripeSessionId`. Because the endpoint is already in prod, the repoint has **no imin-api deploy gate** — it lands entirely in imin-public.

### Task 1.1: Branch off master

**Files:**
- (no source files — VCS only)

**Covers:** (setup task for the whole package)

- [x] **Step 1: Create the feature branch in imin-api.** Run from anywhere:
  ```bash
  git -C /Users/ivan/imin/imin-api checkout master
  git -C /Users/ivan/imin/imin-api pull --ff-only
  git -C /Users/ivan/imin/imin-api checkout -b feat/public-contract-companion
  ```
  Expected: `Switched to a new branch 'feat/public-contract-companion'`.
- [x] **Step 2: Confirm the FE doc branch exists in imin-public.** The doc-sync commits in this package land on the existing `fix/uiux-critique-2026-06` branch in `/Users/ivan/imin/imin-public` (a separate git repo). Run:
  ```bash
  git -C /Users/ivan/imin/imin-public checkout fix/uiux-critique-2026-06
  ```
  Expected: `Switched to branch 'fix/uiux-critique-2026-06'` (or `Already on …`). Do not create it here; it already exists.

### Task 1.2: Regression-guard the ticket payload (qrPayload + walletAvailable)

**Files:**
- Test: `/Users/ivan/imin/imin-api/src/test/java/com/imin/iminapi/controller/publicapi/PublicTicketPayloadTest.java` (Create)

**Covers:** "1. Ticket payload: in-band QR + wallet flag - GET /api/v1/public/tickets/{token} must emit qrPayload (the signed imin1.<token>.<hmac> string) and walletAvailable. Update /Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md section 14.1 to document both fields, the full 5-value state vocabulary (issued/pre/redeemed/checkedIn/revoked), and the qr.png + apple-wallet.pkpass asset endpoints (ticketWalletUrl). HARD GATE: the FE ticket page 500s without qrPayload."

- [x] **Step 1: Write the failing guard test.** `PublicOrderController.getTicket` already emits `qrPayload`, `qrUrl`, and `walletAvailable` (see `PublicTicketResponse`), so this test pins the FE-gating contract so a future refactor can't silently drop the field. Create the file with this exact content:
  ```java
  package com.imin.iminapi.controller.publicapi;

  import com.imin.iminapi.config.TestRateLimitConfig;
  import com.imin.iminapi.model.Event;
  import com.imin.iminapi.model.EventStatus;
  import com.imin.iminapi.model.EventVisibility;
  import com.imin.iminapi.model.Order;
  import com.imin.iminapi.model.Organization;
  import com.imin.iminapi.model.Ticket;
  import com.imin.iminapi.model.User;
  import com.imin.iminapi.model.UserRole;
  import com.imin.iminapi.repository.EventRepository;
  import com.imin.iminapi.repository.OrderRepository;
  import com.imin.iminapi.repository.OrganizationRepository;
  import com.imin.iminapi.repository.TicketRepository;
  import com.imin.iminapi.repository.UserRepository;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
  import org.springframework.context.annotation.Import;
  import org.springframework.test.web.servlet.MockMvc;

  import java.util.UUID;

  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  @SpringBootTest
  @AutoConfigureMockMvc
  @Import(TestRateLimitConfig.class)
  class PublicTicketPayloadTest {

      @Autowired MockMvc mvc;
      @Autowired TicketRepository tickets;
      @Autowired OrderRepository orders;
      @Autowired EventRepository events;
      @Autowired OrganizationRepository orgs;
      @Autowired UserRepository users;

      @Test
      void getTicket_emitsSignedQrPayloadAndWalletFlag() throws Exception {
          Ticket t = persistIssuedTicket();

          mvc.perform(get("/api/v1/public/tickets/" + t.getToken()))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.qrPayload").value(
                          org.hamcrest.Matchers.startsWith("imin1." + t.getToken() + ".")))
                  .andExpect(jsonPath("$.walletAvailable").isBoolean())
                  .andExpect(jsonPath("$.qrUrl").value(
                          org.hamcrest.Matchers.endsWith(
                                  "/api/v1/public/tickets/" + t.getToken() + "/qr.png")))
                  .andExpect(jsonPath("$.state").value("issued"));
      }

      private Ticket persistIssuedTicket() {
          Organization org = new Organization();
          org.setName("Payload Test Org");
          org.setSlug("payload-test-org-" + UUID.randomUUID().toString().substring(0, 8));
          org.setContactEmail("payload@example.com");
          org.setCountry("DE");
          org = orgs.save(org);

          User owner = new User();
          owner.setEmail("payload-owner-" + UUID.randomUUID() + "@example.com");
          owner.setOrgId(org.getId());
          owner.setRole(UserRole.OWNER);
          owner = users.save(owner);

          Event ev = new Event();
          ev.setOrgId(org.getId());
          ev.setName("Payload Test Event");
          ev.setSlug("payload-test-event-" + UUID.randomUUID().toString().substring(0, 8));
          ev.setVisibility(EventVisibility.PUBLIC);
          ev.setStatus(EventStatus.LIVE);
          ev.setCurrency("EUR");
          ev.setCreatedBy(owner.getId());
          ev = events.save(ev);

          Order order = new Order();
          order.setToken("ORD_" + UUID.randomUUID());
          order.setEventId(ev.getId());
          order.setOrgId(org.getId());
          order.setEmail("buyer@example.com");
          order.setTotalMinor(1500L);
          order.setCurrency("EUR");
          order.setPaymentMethod("stripe");
          order = orders.save(order);

          Ticket t = new Ticket();
          t.setToken("TKT_" + UUID.randomUUID());
          t.setOrderId(order.getId());
          t.setEventId(ev.getId());
          t.setTierId(UUID.randomUUID());
          t.setTierName("GA");
          t.setState("issued");
          return tickets.save(t);
      }
  }
  ```
- [x] **Step 2: Run the test — expect PASS (guard, not red).** This is a characterization/guard test over already-shipped behavior, so it should pass on first run; if it fails, the regression already exists and must be fixed before proceeding.
  ```bash
  ./mvnw -q test -Dtest=PublicTicketPayloadTest
  ```
  Expected: `BUILD SUCCESS`, `Tests run: 1, Failures: 0, Errors: 0`. The `qrPayload` assertion verifies the `imin1.<token>.<hmac>` shape produced by `QrPayloadSigner.sign(ticket.getToken())`.
- [x] **Step 3: Commit the guard test in imin-api.**
  ```bash
  git -C /Users/ivan/imin/imin-api add src/test/java/com/imin/iminapi/controller/publicapi/PublicTicketPayloadTest.java
  git -C /Users/ivan/imin/imin-api commit -m "test(public): guard qrPayload + walletAvailable on ticket payload

  Pins the FE-gating contract: GET /api/v1/public/tickets/{token} must
  emit the signed imin1.<token>.<hmac> qrPayload, walletAvailable, and the
  qr.png qrUrl. The FE ticket page 500s without qrPayload.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
  ```
  Expected: one commit created on `feat/public-contract-companion`.

### Task 1.3: Doc-sync §14.1 — ticket payload contract (separate repo commit)

**Files:**
- Modify: `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md` (§14)

**Covers:** "1. Ticket payload: in-band QR + wallet flag - GET /api/v1/public/tickets/{token} must emit qrPayload (the signed imin1.<token>.<hmac> string) and walletAvailable. Update /Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md section 14.1 to document both fields, the full 5-value state vocabulary (issued/pre/redeemed/checkedIn/revoked), and the qr.png + apple-wallet.pkpass asset endpoints (ticketWalletUrl). HARD GATE: the FE ticket page 500s without qrPayload."

- [x] **Step 1: Replace the §14 intro paragraph.** In `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md`, find the paragraph that begins `Returns a single attendee ticket. Drives the` (it ends with `…paid tickets will land here once the Stripe webhook is wired. No auth. `Cache-Control: private, no-store`.`) and replace the whole paragraph with:
  ```markdown
  Returns a single attendee ticket. Drives the `/tickets/{token}` page on the
  buyer site (state badge, event headline, tier label, QR code, wallet button).
  Issued by both the free-ticket flow (§13) and the paid Stripe flow. No auth.
  `Cache-Control: private, no-store`.
  ```
- [x] **Step 2: Replace the §14.1 JSON response block.** Replace the existing fenced `json` block (the one starting `"token": "tkt_xyz1…"` and ending with the `"order"` line + closing brace) with:
  ```markdown
  ```json
  {
    "token": "tkt_xyz1…",
    "state": "issued",
    "tierName": "Early bird",
    "qrPayload": "imin1.tkt_xyz1….0f3a9c8b7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a",
    "qrUrl": "https://api.imin.wtf/api/v1/public/tickets/tkt_xyz1…/qr.png",
    "walletAvailable": true,
    "event": {
      "name": "Summer Fest 2026",
      "slug": "summer-fest-2026",
      "startsAt": "2026-07-15T18:00:00Z",
      "timezone": "Europe/Berlin",
      "venueName": "Funkhaus",
      "venueStreet": "Nalepastraße 18",
      "venueCity": "Berlin",
      "venuePostalCode": "12459",
      "venueCountry": "DE"
    },
    "order": { "token": "ord_aBcDeF…", "email": "buyer@example.com" }
  }
  ```
  ```
- [x] **Step 3: Replace the two trailing §14.1 explainer sentences with the field table + state vocabulary + asset endpoints.** Replace the block that runs from `The `event` block carries the same full venue postal address` through `the check-in state machine is deferred.` (the two short paragraphs just above `### 14.2 Errors`) with:
  ```markdown
  The `event` block carries the same full venue postal address as §13 — see
  the note there for null/empty semantics.

  | Field | Type | Notes |
  |---|---|---|
  | `token` | string | The ticket's URL-safe public token (path key). |
  | `state` | enum string | See the state vocabulary below. |
  | `tierName` | string | Snapshot of the tier label at issue time. |
  | `qrPayload` | string | The signed scan code: `imin1.<token>.<hmac>`. **Required by the FE — the ticket page errors without it.** Render this string as a QR client-side, or just `<img src>` the `qrUrl` PNG below. |
  | `qrUrl` | string (URL) | Absolute URL to a server-rendered 320×320 QR PNG (`GET …/tickets/{token}/qr.png`). `Cache-Control: private, no-store`. |
  | `walletAvailable` | boolean | `true` when the server is configured to sign Apple Wallet passes. Gate the "Add to Apple Wallet" CTA on this — when `false`, the `apple-wallet.pkpass` endpoint returns `503` and the button should be hidden. |
  | `event` | object | Event headline + full venue postal address (same shape as §13). |
  | `order` | object | `{ token, email }` — link back to the order page without a second call. |

  **State vocabulary.** `state` is one of `"issued" | "redeemed" | "revoked"`.
  Two legacy synonyms are normalized on read so the FE only handles those three
  canonical values: pre-V26 free-checkout rows persisted `"pre"` (surfaced as
  `"issued"`), and the door scanner historically wrote `"checkedIn"` (surfaced
  as `"redeemed"`). The full five-value inbound vocabulary the FE may have seen
  historically is therefore `issued / pre / redeemed / checkedIn / revoked`, but
  going forward the API only emits `issued`, `redeemed`, or `revoked`.

  **Asset endpoints (per-ticket).** Both are token-authed (the 24-byte token is
  the only credential), `Cache-Control: private, no-store`, and live on the API
  origin, not the buyer site:

  | Endpoint | Returns | Notes |
  |---|---|---|
  | `GET /api/v1/public/tickets/{token}/qr.png` | `image/png` (320×320) | The value of `qrUrl`. Embed directly. |
  | `GET /api/v1/public/tickets/{token}/apple-wallet.pkpass` | `application/vnd.apple.pkpass` | The Apple Wallet pass ("ticketWalletUrl"). Only call when `walletAvailable` is `true` — otherwise it returns `503 SERVICE_UNAVAILABLE`. Unknown token → `404`. |
  ```
- [x] **Step 4: Commit the doc change in imin-public.**
  ```bash
  git -C /Users/ivan/imin/imin-public add docs/PUBLIC_PAGE_API.md
  git -C /Users/ivan/imin/imin-public commit -m "docs(public): document qrPayload, walletAvailable, state vocab + asset endpoints (§14.1)

  §14.1 was stale: it omitted qrPayload (FE hard gate), qrUrl, and
  walletAvailable, and listed the wrong state vocabulary. Document all three
  fields, the canonical issued/redeemed/revoked states (with pre/checkedIn
  legacy synonyms), and the qr.png + apple-wallet.pkpass asset endpoints.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
  ```
  Expected: one commit on `fix/uiux-critique-2026-06` in the imin-public repo.

### Task 1.4: Repoint the FE success-page poller at the existing `/public/checkout/{sessionId}` endpoint

**Files (imin-public repo only — no imin-api source touched):**
- Modify: `/Users/ivan/imin/imin-public/lib/api/types.ts` (the `CheckoutSessionLookup` union)
- Modify: `/Users/ivan/imin/imin-public/lib/api/public-events.ts` (`getCheckoutSession`)
- Modify: `/Users/ivan/imin/imin-public/app/e/[id]/success/SuccessClient.tsx` (success/keep-polling mapping)

**Covers:** "3. Session-to-order lookup — the FE success page must poll a backend endpoint to resolve the Stripe `session_id` to an order token. The FE branch was written against a never-shipped path `GET /api/v1/public/checkout-sessions/{sessionId}` expecting `{orderToken}` / `{status:'processing'}`. The backend instead already ships `GET /api/v1/public/checkout/{sessionId}` returning `{status:'ready', orderToken}` / `{status:'pending'}`. Repoint the FE at the real endpoint. Document in PUBLIC_PAGE_API.md section 10.4 (Task 1.5)."

- [x] **Step 1: No backend change is needed — confirm why, then move on.** Before touching the FE, verify the existing endpoint is already safe for this use. `GET /api/v1/public/checkout/{sessionId}` (`PublicCheckoutController`) delegates to `CheckoutStatusService.statusFor(sessionId)`:
  ```java
  public Result statusFor(String sessionId) {
      if (sessionId == null || sessionId.isBlank()) {
          return new Result(Status.PENDING, null);
      }
      Optional<Order> o = orders.findByStripeSessionId(sessionId);
      return o.map(order -> new Result(Status.READY, order.getToken()))
              .orElse(new Result(Status.PENDING, null));
  }
  ```
  This is already hardened for the buyer poller:
  - **No existence leak for unknown ids.** An unknown (or never-existed) `sessionId` returns `PENDING`, exactly like a real-but-not-yet-issued one — the endpoint never 404s and never distinguishes "real-but-pending" from "fake". This is the leak-safe behavior the contract wanted; it just expresses "unknown" as `pending` rather than `404`. The FE treats `pending` as "keep polling" and degrades to the email fallback after the time budget, so an unknown id self-heals into the same fallback (no infinite spinner — the `MAX_ATTEMPTS` ceiling already caps it).
  - **No 500 on malformed ids.** `{sessionId}` is a free-form `@PathVariable String`, so any non-empty path segment binds; a blank/whitespace id is guarded by the `isBlank()` branch and returns `PENDING`. There is no parsing (no UUID coercion) that could throw, and `findByStripeSessionId` is a plain string equality lookup that returns `Optional.empty()` for no match. No `500` path exists.
  - **`Cache-Control: private, no-store`** is already set by the controller, so the transient per-buyer status is never served stale from a shared cache.

  Therefore **no imin-api code, migration, or test change is required** for the session→order lookup — the only gap was the FE pointing at a path the backend never shipped. (The originally-planned V38 index, `PublicCheckoutSessionResponse` DTO, and `PublicCheckoutSessionController` are dropped — we do not build a duplicate endpoint. `orders.stripe_session_id` remains unindexed, which is acceptable: the table is small relative to a per-buyer ~2.5s/~60s poll, and adding an index later is a non-blocking, FE-invisible follow-up if it ever shows up in slow-query logs.)

- [x] **Step 2: Switch the branch and confirm the current poller wiring.** The FE work lands on the existing imin-public branch `fix/uiux-critique-2026-06`:
  ```bash
  git -C /Users/ivan/imin/imin-public checkout fix/uiux-critique-2026-06
  ```
  The poller is `getCheckoutSession` in `lib/api/public-events.ts`, consumed by `app/e/[id]/success/SuccessClient.tsx` (which polls every `POLL_MS = 2500` up to `MAX_ATTEMPTS = 24` ≈ 60s). The `success/page.tsx` server component reads `session_id` from `searchParams` and passes it to `SuccessClient` — it does **not** call the API and needs no change.

- [x] **Step 3: Update the `CheckoutSessionLookup` union type.** In `/Users/ivan/imin/imin-public/lib/api/types.ts`, replace the existing `CheckoutSessionLookup` type (the `export type CheckoutSessionLookup = …` two-member union plus its leading comment block) with the shape the existing endpoint actually returns — a discriminated union on `status` (`"ready"` carries `orderToken`; `"pending"` does not). Replace from the comment line beginning `// Session-to-order lookup` through the closing `| { status: "processing"; orderToken?: never };` with:
  ```ts
  // Session-to-order lookup — `GET /api/v1/public/checkout/{sessionId}`.
  // Polled by the success page after Stripe redirects back with `?session_id=…`.
  // `status: 'ready'` carries `orderToken`, the tokenized key for
  // `GET /api/v1/public/orders/{token}` (§13); `status: 'pending'` means the
  // issuance webhook hasn't completed yet (the endpoint also answers `pending`
  // for unknown/expired ids — it never 404s — so the success page degrades to
  // the email fallback after its time budget rather than branching on a 404).
  export type CheckoutSessionLookup =
    | { status: "ready"; orderToken: string }
    | { status: "pending"; orderToken?: never };
  ```

- [x] **Step 4: Repoint `getCheckoutSession` at the existing endpoint.** In `/Users/ivan/imin/imin-public/lib/api/public-events.ts`, replace the entire `getCheckoutSession` function (the leading comment block through the closing brace) with the version below. It changes the URL from `/checkout-sessions/${…}` to `/checkout/${…}` and updates the comment to match the `ready`/`pending` contract. The `404 → null` guard is kept defensively (this endpoint does not 404 today, but the guard preserves the leak-safe collapse if that ever changes); the caller treats `null` like `pending`. Show-the-complete-function:
  ```ts
  // Browser-side. Polls the session-to-order lookup after Stripe redirects to
  // `/e/{eventId}/success?session_id=…`. Returns `{ status: 'ready', orderToken }`
  // once the issuance webhook has run, or `{ status: 'pending' }` while it's in
  // flight (the backend also answers `pending` for unknown/expired ids — it never
  // 404s). The `404 → null` guard is defensive only and collapses like `pending`.
  // Never cached — issuance state changes second-to-second.
  export async function getCheckoutSession(
    sessionId: string,
    signal?: AbortSignal,
  ): Promise<CheckoutSessionLookup | null> {
    const res = await fetch(
      `${API_BASE}/api/v1/public/checkout/${encodeURIComponent(sessionId)}`,
      { headers: { Accept: "application/json" }, cache: "no-store", signal },
    );
    if (res.status === 404) return null;
    if (!res.ok) throw await toApiError(res);
    return (await res.json()) as CheckoutSessionLookup;
  }
  ```

- [x] **Step 5: Adapt the success/keep-polling mapping in `SuccessClient.tsx`.** In `/Users/ivan/imin/imin-public/app/e/[id]/success/SuccessClient.tsx`, the only logic change is the success check inside `poll()`: the new union discriminates on `status === "ready"` rather than testing `"orderToken" in result`. Replace the success branch:
  ```ts
        const result = await getCheckoutSession(sessionId, controller.signal);
        if (cancelled) return;
        if (result && "orderToken" in result && result.orderToken) {
          setOrderToken(result.orderToken);
          return;
        }
  ```
  with:
  ```ts
        const result = await getCheckoutSession(sessionId, controller.signal);
        if (cancelled) return;
        // `ready` with an orderToken = issued → render "View your tickets".
        // `pending` (or a defensive `null` from a 404) = keep polling until the
        // MAX_ATTEMPTS budget elapses, then fall back to the email-recovery CTA.
        if (result && result.status === "ready" && result.orderToken) {
          setOrderToken(result.orderToken);
          return;
        }
  ```
  Everything else in the component is unchanged: `POLL_MS`/`MAX_ATTEMPTS` keep the ~2.5s/~60s cadence, the `pending`/network/abort paths still re-arm the timer, and the exhausted/no-session branches still render the "Resend tickets" recovery fallback. (The old contract's `status: "processing"` becomes `status: "pending"` here — both mean "keep polling" — so no separate "processing" branch is needed.)

- [x] **Step 6: Verify the FE — lint then build.**
  ```bash
  pnpm -C /Users/ivan/imin/imin-public lint
  pnpm -C /Users/ivan/imin/imin-public build
  ```
  Expected: `lint` passes with no new errors on the three touched files; `build` completes (`next build`) with no type errors — the discriminated union narrows cleanly in `SuccessClient.tsx` (no `"orderToken" in result` widening needed). It is now safe to run `pnpm build` (the earlier automation that made this unsafe is no longer running).

- [x] **Step 7: Commit the repoint in imin-public.**
  ```bash
  git -C /Users/ivan/imin/imin-public add \
    lib/api/types.ts \
    lib/api/public-events.ts \
    app/e/[id]/success/SuccessClient.tsx
  git -C /Users/ivan/imin/imin-public commit -m "fix: poll the existing /public/checkout/{sessionId} endpoint (task 8.1 repoint)

  The success-page poller was written against a never-shipped path
  /api/v1/public/checkout-sessions/{sessionId} expecting {orderToken} /
  {status:processing}. The backend already ships GET /api/v1/public/checkout/
  {sessionId} returning {status:ready, orderToken} / {status:pending}. Repoint
  the URL and adapt the CheckoutSessionLookup union + SuccessClient mapping
  (ready+orderToken = success; pending = keep polling). No backend change.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
  ```
  Expected: one commit on `fix/uiux-critique-2026-06` in the imin-public repo. No imin-api commit for this item.

### Task 1.5: Doc-sync §10.4 — document the existing session→order polling endpoint (separate repo commit)

**Files:**
- Modify: `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md` (§10.4)

**Covers:** "3. Session-to-order lookup — document the EXISTING endpoint `GET /api/v1/public/checkout/{sessionId}` (the `ready`/`pending` union, `orderToken` semantics, `no-store` header, and the FE ~2.5s/~60s polling cadence) in PUBLIC_PAGE_API.md section 10.4. This documents the production endpoint the FE is now repointed at (Task 1.4), not a new one."

- [x] **Step 1: Replace the §10.4 body.** In `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md`, replace the entire `### 10.4 Post-checkout` section (the three bullet points from `- Stripe redirects the buyer back to` through `de-duplicates by Stripe session id on the webhook side.`) — keep the `### 10.4 Post-checkout` heading — with:
  ```markdown
  - Stripe redirects the buyer back to `/e/{eventId}/success?session_id=…` on
    success and `/e/{eventId}` on cancel. Both URLs are configured in the
    backend on session creation; the frontend does not pass them.
  - **The webhook is the source of truth for ticket issuance** — the success
    page is a friendly confirmation, not a fulfillment trigger. Tickets are
    materialized by the backend on `payment_intent.succeeded`.
  - No idempotency key is required from the client. The backend de-duplicates
    by Stripe `PaymentIntent` id on the webhook side.

  **Polling for the order — `GET /api/v1/public/checkout/{sessionId}`.**
  Issuance is webhook-driven and can lag the redirect by a few seconds, so the
  success page polls this endpoint with the `session_id` from the redirect URL
  until the order exists, then navigates to the order page. The `session_id`
  itself is the authorization — only the buyer's browser receives it from
  Stripe's redirect. **No auth.** `Cache-Control: private, no-store`.

  `200 OK` — order issued:

  ```json
  { "status": "ready", "orderToken": "ord_aBcDeF…" }
  ```

  `200 OK` — order not issued yet (or the session id is unknown/expired):

  ```json
  { "status": "pending" }
  ```

  `status` is always present. `orderToken` is present **only** when
  `status === "ready"`, and is the token you pass to
  `GET /api/v1/public/orders/{token}` (§13).

  | HTTP | `status` | When |
  |---|---|---|
  | `200` | `"ready"` | The order exists for this session; `orderToken` is included. |
  | `200` | `"pending"` | The issuance webhook hasn't completed yet — **or** the session id is unknown/expired. This endpoint does not 404 and does not distinguish the two (leak-safe by construction); keep polling and let the time budget decide. |

  **Frontend polling policy:** poll roughly every **2.5s for up to ~60s**
  (`SuccessClient.tsx`: `POLL_MS = 2500`, `MAX_ATTEMPTS = 24`). On
  `{ "status": "ready", "orderToken": … }`, redirect to the order page. On
  `{ "status": "pending" }`, keep polling. Once the budget elapses (still
  `pending`, e.g. a stale/unknown id), show a "still processing — check your
  email" recovery fallback; the confirmation email lands independently. Do not
  cache this endpoint.
  ```
- [x] **Step 2: Commit the doc change in imin-public.**
  ```bash
  git -C /Users/ivan/imin/imin-public add docs/PUBLIC_PAGE_API.md
  git -C /Users/ivan/imin/imin-public commit -m "docs(public): document the existing session→order polling endpoint (§10.4)

  GET /api/v1/public/checkout/{sessionId}: 200 {status:ready, orderToken} once
  issued, 200 {status:pending} while in flight or for unknown/expired ids (no
  404 — leak-safe by construction), Cache-Control: private, no-store. FE polls
  ~2.5s up to ~60s, then redirects to the order page or shows the email-recovery
  fallback. Also corrects the stale 'de-dup by session id' / 'checkout.session.
  completed' notes to match the PI-driven webhook.

  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
  ```
  Expected: one commit on `fix/uiux-critique-2026-06` in the imin-public repo.

### Task 1.6: Full-suite verification

**Files:**
- (no source files — verification only)

**Covers:** (verification task for the whole package)

- [x] **Step 1: Run the touched imin-api test classes together.** Package 1 now changes only one imin-api test (the qrPayload guard, Task 1.2); the session→order item is FE-only. Run that guard plus the asset test for context:
  ```bash
  ./mvnw -q test -Dtest=PublicTicketPayloadTest,PublicTicketAssetControllerTest
  ```
  Expected: `BUILD SUCCESS`, all tests green (1 + 4).
- [x] **Step 2: Run the public-controller leak allowlist guard.** Confirms nothing in this package perturbed the field-leak snapshot on the listing.
  ```bash
  ./mvnw -q test -Dtest=PublicEventControllerTest
  ```
  Expected: `BUILD SUCCESS`, including `list_responseItemKeysAreAllowListed` (the actual method name in `PublicEventControllerTest`).
- [x] **Step 3: Confirm the imin-api branch holds exactly the one expected commit for this package.**
  ```bash
  git -C /Users/ivan/imin/imin-api log --oneline master..feat/public-contract-companion
  ```
  Expected (newest first): this branch shows **only** the qrPayload guard-test commit (Task 1.2) for Package 1 — there is no longer a migration or endpoint commit, because the session→order lookup item (Tasks 1.4/1.5) lands entirely in imin-public on `fix/uiux-critique-2026-06` (the repoint commit + the §10.4 doc commit). The §14.1 doc commit also lives in imin-public. Do not push.

## Package 2: Listing: includeOngoing param and availability flags

This package extends `GET /api/v1/public/events` with two FE-driven changes: an `includeOngoing` query param so doors-open (started-but-not-ended) events stay listed (companion item 4), and two derived availability booleans `soldOut` / `lowStock` on each `PublicEventListItem` (companion item 5). Item 5 is the **highest FE-gate severity in this package**: the leak-guardrail snapshot test `PublicEventControllerTest.list_responseItemKeysAreAllowListed` asserts an *exact* key set on `items[*]`, so the new fields MUST be added to that allowlist in the same change or the test fails the build (and adding fields without updating it would otherwise strip nothing but hard-fail CI). Item 4 is lower severity (a backward-compatible additive param defaulting to today's behavior) but still changes the repository predicate, so it ships behind a service+repository test. Both items derive their values inside the existing single-batch-per-page mapper path — no N+1 — by mirroring the existing `findMinEnabledPriceByEventIds` aggregate pattern.

> **Naming note (read before Task 2.3):** the companion contract references the test method as `list_response_item_keys_are_allow_listed`, but the method actually present in `src/test/java/com/imin/iminapi/controller/publicapi/PublicEventControllerTest.java` is camelCase `list_responseItemKeysAreAllowListed` (lines 326–356). This plan edits the real method. Do not rename it.

> **Branch:** all imin-api tasks in this package commit on `feat/public-contract-companion` (created as Task 1.1 of Package 1). Doc-sync steps commit in the separate `imin-public` repo. Do not push.

---

### Task 2.1: Add `includeOngoing` param + ongoing-inclusive listing predicate

**Files:**
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/service/event/PublicEventListQuery.java`
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/repository/EventRepository.java`
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/service/event/PublicEventService.java`
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/controller/publicapi/PublicEventController.java`
- Test: `/Users/ivan/imin/imin-api/src/test/java/com/imin/iminapi/service/event/PublicEventServiceTest.java`
- Test: `/Users/ivan/imin/imin-api/src/test/java/com/imin/iminapi/controller/publicapi/PublicEventControllerTest.java`

**Covers:** "4. Ongoing-inclusive listing filter - add boolean includeOngoing=true to GET /api/v1/public/events: predicate becomes (startsAt >= from OR endsAt > now) so doors-open events stay listed; truly-finished events still drop. Document in PUBLIC_PAGE_API.md section 9.1."

- [x] **Step 1: Write the failing service test for ongoing-inclusive filtering.** Append these two tests to `PublicEventServiceTest.java` (the class already has `NOW = 2026-06-01T12:00:00Z`, `publishedLiveEvent()`, `org`, `owner`, and autowired `publicEventService`/`eventRepository`). They assert that with `from = now` an event that already started but has not ended is kept only when `includeOngoing=true`, and that a truly-finished event drops in both modes. Insert before the final closing brace of the class:

```java
    // -----------------------------------------------------------------------
    // includeOngoing — listing predicate (companion item 4)
    // -----------------------------------------------------------------------

    private com.imin.iminapi.service.event.PublicEventListQuery listQuery(
            Instant from, boolean includeOngoing) {
        return new com.imin.iminapi.service.event.PublicEventListQuery(
                from, null, null, null, null, null, null, null,
                false, includeOngoing, 1, 20);
    }

    @Test
    void list_excludesOngoingEvent_whenIncludeOngoingFalse() {
        // Event started 1h ago, ends 1h from now => doors-open. from = now.
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.minusSeconds(3600));
        e.setEndsAt(NOW.plusSeconds(3600));
        eventRepository.save(e);

        var result = publicEventService.list(listQuery(NOW, false));

        assertThat(result.items()).isEmpty();
    }

    @Test
    void list_includesOngoingEvent_whenIncludeOngoingTrue() {
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.minusSeconds(3600));
        e.setEndsAt(NOW.plusSeconds(3600));
        eventRepository.save(e);

        var result = publicEventService.list(listQuery(NOW, true));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).id()).isEqualTo(e.getId());
    }

    @Test
    void list_dropsFinishedEvent_evenWhenIncludeOngoingTrue() {
        // Started and ended in the past => truly finished, must drop in both modes.
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.minusSeconds(7200));
        e.setEndsAt(NOW.minusSeconds(3600));
        eventRepository.save(e);

        assertThat(publicEventService.list(listQuery(NOW, true)).items()).isEmpty();
        assertThat(publicEventService.list(listQuery(NOW, false)).items()).isEmpty();
    }
```

- [x] **Step 2: Run the test, confirm it fails to compile.** The `PublicEventListQuery` constructor still has 11 args (no `includeOngoing`), so this is a compile failure — that is the expected "red". Run:

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=PublicEventServiceTest
```

Expected: `BUILD FAILURE` with a compilation error like `constructor PublicEventListQuery ... cannot be applied to given types` (12 args supplied, 11 expected).

- [x] **Step 3: Add `includeOngoing` to the query record.** Replace the body of `PublicEventListQuery.java` with the 12-field record (insert `includeOngoing` immediately after `onSaleOnly`, before pagination, matching the param order used in the controller):

```java
package com.imin.iminapi.service.event;

import java.time.Instant;

public record PublicEventListQuery(
        Instant from,
        Instant to,
        String genre,
        String type,
        String city,
        String country,
        String orgSlug,
        String q,
        boolean onSaleOnly,
        boolean includeOngoing,
        int page,
        int pageSize
) {}
```

- [x] **Step 4: Add the ongoing-inclusive branch to the repository query.** In `EventRepository.java`, modify `findPublicListing`'s JPQL `from` predicate and add the two new params. Replace the single `from` line:

```sql
           AND (CAST(:from AS timestamp) IS NULL OR e.startsAt >= :from)
```

with the ongoing-inclusive variant (when `includeOngoing=true`, an event that has not yet ended — `endsAt > now`, or has no scheduled end — passes even if `startsAt < from`):

```sql
           AND (CAST(:from AS timestamp) IS NULL
                OR e.startsAt >= :from
                OR (:includeOngoing = true AND (e.endsAt IS NULL OR e.endsAt > :now)))
```

Then add the `includeOngoing` parameter to the method signature (the `:now` param already exists for `onSaleOnly`, reuse it). Change the signature from:

```java
            @Param("onSaleOnly") boolean onSaleOnly,
            @Param("now") Instant now,
            Pageable pageable);
```

to:

```java
            @Param("onSaleOnly") boolean onSaleOnly,
            @Param("includeOngoing") boolean includeOngoing,
            @Param("now") Instant now,
            Pageable pageable);
```

- [x] **Step 5: Pass `includeOngoing` through the service.** In `PublicEventService.list(...)`, update the `eventRepository.findPublicListing(...)` call (step 3 of the method, "// 3. Query") to pass the new flag. Change:

```java
        Page<Event> result = eventRepository.findPublicListing(
                query.from(), query.to(),
                nullIfBlank(query.genre()), nullIfBlank(query.type()),
                nullIfBlank(query.city()), country, nullIfBlank(query.q()),
                orgId, query.onSaleOnly(), clock.instant(),
                PageRequest.of(page - 1, pageSize));
```

to:

```java
        Page<Event> result = eventRepository.findPublicListing(
                query.from(), query.to(),
                nullIfBlank(query.genre()), nullIfBlank(query.type()),
                nullIfBlank(query.city()), country, nullIfBlank(query.q()),
                orgId, query.onSaleOnly(), query.includeOngoing(), clock.instant(),
                PageRequest.of(page - 1, pageSize));
```

- [x] **Step 6: Bind the controller param.** In `PublicEventController.list(...)`, add the `includeOngoing` request param (defaulting to `false` to preserve today's behavior) and thread it into the query constructor. Change the method signature line:

```java
            @RequestParam(defaultValue = "false") boolean onSaleOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                from, to, genre, type, city, country, orgSlug, q, onSaleOnly, page, pageSize));
```

to:

```java
            @RequestParam(defaultValue = "false") boolean onSaleOnly,
            @RequestParam(defaultValue = "false") boolean includeOngoing,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                from, to, genre, type, city, country, orgSlug, q, onSaleOnly, includeOngoing, page, pageSize));
```

- [x] **Step 7: Fix the controller test's existing query-binding helper.** The existing `list_bindsFullFilterSetIntoQueryObject` test (PublicEventControllerTest lines 284–317) does not assert `includeOngoing`, and `list_responseItemKeysAreAllowListed` builds a `PublicEventListItem` (unaffected here). No constructor in this file calls `PublicEventListQuery` directly, so no compile fix is needed yet. Add a controller binding assertion for the new param — append this test before the final closing brace of `PublicEventControllerTest`:

```java
    @Test
    void list_bindsIncludeOngoingIntoQueryObject() throws Exception {
        when(publicEventService.list(any(PublicEventListQuery.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 20));

        mvc.perform(get("/api/v1/public/events").param("includeOngoing", "true"))
                .andExpect(status().isOk());

        ArgumentCaptor<PublicEventListQuery> captor = ArgumentCaptor.forClass(PublicEventListQuery.class);
        verify(publicEventService).list(captor.capture());
        assertThat(captor.getValue().includeOngoing()).isTrue();
    }

    @Test
    void list_includeOngoingDefaultsToFalse() throws Exception {
        when(publicEventService.list(any(PublicEventListQuery.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 20));

        mvc.perform(get("/api/v1/public/events"))
                .andExpect(status().isOk());

        ArgumentCaptor<PublicEventListQuery> captor = ArgumentCaptor.forClass(PublicEventListQuery.class);
        verify(publicEventService).list(captor.capture());
        assertThat(captor.getValue().includeOngoing()).isFalse();
    }
```

- [x] **Step 7b: Fix the other listing service test — `PublicEventServiceListTest` (REQUIRED for the module to compile).** A *second* listing test class exists at `/Users/ivan/imin/imin-api/src/test/java/com/imin/iminapi/service/event/PublicEventServiceListTest.java` (it, not `PublicEventServiceTest`, holds the bulk of the `list(...)` filter/pagination/sort/priceFromMinor tests). It constructs `new PublicEventListQuery(...)` with the **old 11-arg shape** in ~15 places — two private helpers plus inline calls in nearly every `@Test`. After Step 3 changes the record to 12 fields, this class no longer compiles. Because `./mvnw -Dtest=...` still compiles the *entire* test source set before running the selected classes, the Step 8 run (and any later run) fails at the compile phase until this is fixed — even though `PublicEventServiceListTest` isn't named in the `-Dtest` list. (Step 2's red happens *before* the record change, so `PublicEventServiceListTest` still compiles there; only the new 12-arg test in `PublicEventServiceTest` triggers Step 2's expected failure.) Update every `new PublicEventListQuery(...)` call to insert `false` (the `includeOngoing` default) immediately after the `onSaleOnly` boolean and before `page`. The two helpers become:

```java
    private PublicEventListQuery emptyQuery() {
        return new PublicEventListQuery(
                null, null, null, null, null, null, null, null, false, false, 1, 20);
    }

    private PublicEventListQuery onlyPage(int page, int pageSize) {
        return new PublicEventListQuery(
                null, null, null, null, null, null, null, null, false, false, page, pageSize);
    }
```

  Apply the same `, false` insertion (after the existing `false`/`true` `onSaleOnly` arg, before the `1`/`page`) to every inline `new PublicEventListQuery(...)` in this file — the `filter_by_*`, `from_window_*`, `to_window_*`, `*orgSlug*`, `onSaleOnly_*`, `q_below_min_length_returns_400`, and `country_wrong_length_returns_400` tests. Grep to confirm none are missed:

```bash
cd /Users/ivan/imin/imin-api && grep -n "new PublicEventListQuery(" src/test/java/com/imin/iminapi/service/event/PublicEventServiceListTest.java
```

  Every printed line must, after editing, have **12** comma-separated args (two booleans in a row before the trailing `page, pageSize`). This class's `@DataJpaTest` `@Import` is also handled in Task 2.3 Step 8 (same `PublicListingConfig` addition), so this class is not fully green until that step — but it must at least **compile** now.

- [x] **Step 8: Run all three affected test classes, confirm green.**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=PublicEventServiceTest,PublicEventServiceListTest,PublicEventControllerTest
```

Expected: `BUILD SUCCESS`, `Tests run: <n>, Failures: 0, Errors: 0` across all three classes (the three new service tests and two new controller tests pass; the existing `list_bindsFullFilterSetIntoQueryObject` still passes because it never constructed the query directly; `PublicEventServiceListTest`'s existing tests pass with the `, false` includeOngoing arg added).

- [x] **Step 9: Commit.**

```bash
cd /Users/ivan/imin/imin-api && git add -A && git commit -m "feat(public): add includeOngoing listing param keeping doors-open events listed

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2.2: Document `includeOngoing` in PUBLIC_PAGE_API.md §9.1 (imin-public)

**Files:**
- Modify: `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md`

**Covers:** "4. Ongoing-inclusive listing filter - add boolean includeOngoing=true to GET /api/v1/public/events: predicate becomes (startsAt >= from OR endsAt > now) so doors-open events stay listed; truly-finished events still drop. Document in PUBLIC_PAGE_API.md section 9.1."

- [x] **Step 1: Add the `includeOngoing` row to the §9.1 query-parameter table.** In `PUBLIC_PAGE_API.md`, locate the §9.1 table (the row block between `| from |` and `| page |`). Insert a new row immediately after the existing `onSaleOnly` row:

```markdown
| `includeOngoing` | boolean | `false` | When `true`, events that have already started but not yet ended (`endsAt > now`, or `endsAt` is null) are kept in the result **even if their `startsAt` is before `from`** — so a "happening now" event still surfaces when the FE passes `from=now`. Truly-finished events (`endsAt <= now`) still drop. Without it (default), the `from` lower bound applies strictly to `startsAt`. |
```

- [x] **Step 2: Add a clarifying sentence to the §9.5 frontend integration notes.** Find the bullet in §9.5 that reads `**Filter combinations:** all filters AND together. Wide queries (no filters) return everything published, ordered by upcoming-first. To exclude past events, pass `from=now`.` and replace it with:

```markdown
- **Filter combinations:** all filters AND together. Wide queries (no filters) return everything published, ordered by upcoming-first. To exclude past events, pass `from=now`. To keep events that are *currently happening* (started, not yet ended) while still excluding finished ones, pass `from=now&includeOngoing=true`.
```

- [x] **Step 3: Bump the doc's Last updated date.** Change the `**Last updated:** 2026-05-08` line near the top of the file to `**Last updated:** 2026-06-10`.

- [x] **Step 4: Commit in the imin-public repo.**

```bash
cd /Users/ivan/imin/imin-public && git add docs/PUBLIC_PAGE_API.md && git commit -m "docs(public-api): document includeOngoing listing param (§9.1)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2.3: Add `soldOut` / `lowStock` availability flags to `PublicEventListItem`

**Files:**
- Create: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/service/event/PublicListingProperties.java`
- Create: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/service/event/PublicListingConfig.java`
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/dto/publicapi/PublicEventListItem.java`
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/repository/TicketTierRepository.java`
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/service/event/PublicEventService.java`
- Modify: `/Users/ivan/imin/imin-api/src/main/resources/application.yaml`
- Test: `/Users/ivan/imin/imin-api/src/test/java/com/imin/iminapi/controller/publicapi/PublicEventControllerTest.java`
- Test: `/Users/ivan/imin/imin-api/src/test/java/com/imin/iminapi/service/event/PublicEventServiceTest.java`

**Covers:** "5. Listing availability flags - add soldOut (every enabled tier remaining == 0) and lowStock (not sold out, total remaining below a threshold) booleans to PublicEventListItem on GET /api/v1/public/events. MUST update the allowlist snapshot test PublicEventControllerTest.list_response_item_keys_are_allow_listed in the SAME change or the fields get stripped/fail. Document in PUBLIC_PAGE_API.md section 9.2. Pick the lowStock threshold from any existing convention in the codebase, else propose a config property with a sane default (e.g. 10)."

- [x] **Step 1: Write the failing controller leak-guardrail update + service tests first.** The contract REQUIRES updating `list_responseItemKeysAreAllowListed` in the same change. Do the test edits before any production code so red is observable.

  First, update the leak-guardrail allowlist and the `sampleListItem()` constructor call in `PublicEventControllerTest.java`. Replace `sampleListItem()` (lines 230–248) with the 18-arg version (adds `soldOut=false`, `lowStock=true` after `priceFromMinor`):

```java
    private PublicEventListItem sampleListItem() {
        return new PublicEventListItem(
                EVENT_ID,
                "summer-festival-2026",
                "Summer Festival 2026",
                "live",
                Instant.parse("2026-01-15T10:00:00Z"),
                "techno",
                "concert",
                Instant.parse("2026-07-01T16:00:00Z"),
                Instant.parse("2026-07-02T04:00:00Z"),
                "Europe/Berlin",
                "Berlin",
                "DE",
                "https://cdn.example.com/cover.jpg",
                "EUR",
                2500,
                false,
                true,
                new PublicOrganizationDto("Acme Events", "acme-events"));
    }
```

  Then extend the `expectedItemKeys` allowlist in `list_responseItemKeysAreAllowListed` (lines 339–344). Replace:

```java
        Set<String> expectedItemKeys = Set.of(
                "id", "slug", "name", "status", "publishedAt",
                "genre", "type", "startsAt", "endsAt", "timezone",
                "venueCity", "venueCountry", "posterUrl", "currency",
                "priceFromMinor", "organization"
        );
```

  with:

```java
        Set<String> expectedItemKeys = Set.of(
                "id", "slug", "name", "status", "publishedAt",
                "genre", "type", "startsAt", "endsAt", "timezone",
                "venueCity", "venueCountry", "posterUrl", "currency",
                "priceFromMinor", "soldOut", "lowStock", "organization"
        );
```

- [x] **Step 2: Write the failing service tests for flag derivation.** Append these to `PublicEventServiceTest.java` before the final closing brace. They cover: all-enabled-tiers-empty => soldOut; some remaining but under threshold => lowStock; ample remaining => neither; sold-out wins over lowStock. The default threshold is 10 (see Step 6 config). Reuse the existing `tier(eventId, name, priceMinor, quantity, sold, enabled, sortOrder, saleClosesAt)` helper:

```java
    // -----------------------------------------------------------------------
    // soldOut / lowStock — listing availability flags (companion item 5)
    // -----------------------------------------------------------------------

    @Test
    void list_soldOut_whenEveryEnabledTierRemainingIsZero() {
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.plusSeconds(86400));
        e = eventRepository.save(e);
        tier(e.getId(), "GA",  500, 50, 50, true, 0, null);  // remaining 0
        tier(e.getId(), "VIP", 1500, 20, 20, true, 1, null); // remaining 0

        var result = publicEventService.list(listQuery(null, false));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).soldOut()).isTrue();
        assertThat(result.items().get(0).lowStock()).isFalse();
    }

    @Test
    void list_lowStock_whenTotalRemainingBelowThresholdButNotSoldOut() {
        // Default threshold = 10. Total remaining = 4 < 10, > 0 => lowStock.
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.plusSeconds(86400));
        e = eventRepository.save(e);
        tier(e.getId(), "GA",  500, 50, 48, true, 0, null);  // remaining 2
        tier(e.getId(), "VIP", 1500, 20, 18, true, 1, null); // remaining 2

        var result = publicEventService.list(listQuery(null, false));

        assertThat(result.items().get(0).soldOut()).isFalse();
        assertThat(result.items().get(0).lowStock()).isTrue();
    }

    @Test
    void list_neitherFlag_whenAmpleRemaining() {
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.plusSeconds(86400));
        e = eventRepository.save(e);
        tier(e.getId(), "GA", 500, 100, 0, true, 0, null); // remaining 100

        var result = publicEventService.list(listQuery(null, false));

        assertThat(result.items().get(0).soldOut()).isFalse();
        assertThat(result.items().get(0).lowStock()).isFalse();
    }

    @Test
    void list_disabledTiersExcludedFromAvailability() {
        // The only tier with remaining stock is disabled => event reads as sold out.
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.plusSeconds(86400));
        e = eventRepository.save(e);
        tier(e.getId(), "GA",       500, 50, 50,  true,  0, null); // enabled, remaining 0
        tier(e.getId(), "Disabled", 500, 50, 0,   false, 1, null); // disabled, ignored

        var result = publicEventService.list(listQuery(null, false));

        assertThat(result.items().get(0).soldOut()).isTrue();
        assertThat(result.items().get(0).lowStock()).isFalse();
    }

    @Test
    void list_notSoldOut_whenEventHasNoEnabledTiers() {
        // No enabled tiers => no inventory rows => neither flag set (cannot be "sold out").
        Event e = publishedLiveEvent();
        e.setStartsAt(NOW.plusSeconds(86400));
        e = eventRepository.save(e);

        var result = publicEventService.list(listQuery(null, false));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).soldOut()).isFalse();
        assertThat(result.items().get(0).lowStock()).isFalse();
    }
```

  > These tests rely on the `listQuery(Instant, boolean)` helper added in Task 2.1 Step 1. Tasks run in order, so it is present.

- [x] **Step 3: Run, confirm red.**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=PublicEventServiceTest,PublicEventControllerTest
```

Expected: `BUILD FAILURE` — compilation errors in both test classes (`PublicEventListItem` constructor takes 16 args, not 18; the listing item has no `soldOut()`/`lowStock()` accessors). This confirms the new fields do not yet exist.

- [x] **Step 4: Add the two booleans to the DTO.** Replace `PublicEventListItem.java` with the 18-field record (insert `soldOut`, `lowStock` after `priceFromMinor`, before `organization` — matching the test constructor order and keeping `organization` last as the controller test's nested-key assertion expects):

```java
package com.imin.iminapi.dto.publicapi;

import java.time.Instant;
import java.util.UUID;

public record PublicEventListItem(
        UUID id,
        String slug,
        String name,
        String status,
        Instant publishedAt,
        String genre,
        String type,
        Instant startsAt,
        Instant endsAt,
        String timezone,
        String venueCity,
        String venueCountry,
        String posterUrl,
        String currency,
        Integer priceFromMinor,
        boolean soldOut,
        boolean lowStock,
        PublicOrganizationDto organization
) {}
```

- [x] **Step 5: Add the remaining-inventory batch aggregate to the repository.** In `TicketTierRepository.java`, mirror the existing `findMinEnabledPriceByEventIds` shape: one batch query keyed by eventId, returning per-event `SUM` and `MAX` of per-tier `remaining = quantity - reserved - sold`. JPQL has no per-row `MAX(0, ...)`, so per-tier negatives (over-sell guard) are clamped in the service. **`MAX` (not `MIN`) is the correct aggregate for `soldOut`:** the contract defines `soldOut` as "*every* enabled tier remaining == 0", which holds iff the *largest* per-tier remaining is `<= 0` — `MAX(remaining) <= 0`. (A `SUM == 0` test is unsafe: an oversold tier at `-5` could cancel another tier's `+5` and mask available stock; a `MIN == 0` test is also wrong — it only proves the *emptiest* tier is empty, not all of them.) `SUM` is used only for the soft `lowStock` total. Add after `findMinEnabledPriceByEventIds`:

```java
    /**
     * Per-event inventory rollup across ENABLED tiers only, for the public listing's
     * soldOut / lowStock flags. One batch round-trip per page (no N+1) — same pattern
     * as {@link #findMinEnabledPriceByEventIds}.
     *
     * <p>Returns {@code (eventId, SUM(remaining), MAX(remaining))} where per-tier
     * {@code remaining = quantity - reserved - sold}. {@code MAX(remaining) <= 0}
     * means every enabled tier is empty ⇒ soldOut; {@code SUM} (clamped to >=0 in the
     * service, matching {@code PublicTierDto.from}'s {@code Math.max(0, ...)}) feeds the
     * lowStock total. Events with no enabled tiers simply do not appear in the result and
     * the service treats that as "not sold out".
     */
    @Query("""
        SELECT t.eventId,
               SUM(t.quantity - t.reserved - t.sold),
               MAX(t.quantity - t.reserved - t.sold)
          FROM TicketTier t
         WHERE t.enabled = true AND t.eventId IN :eventIds
         GROUP BY t.eventId
    """)
    List<Object[]> findEnabledRemainingByEventIds(
            @Param("eventIds") Collection<UUID> eventIds);
```

- [x] **Step 6: Create the config property for the lowStock threshold.** No existing lowStock convention exists in the codebase, so introduce a config property following the `TicketProperties` + `@EnableConfigurationProperties` pattern. Create `PublicListingProperties.java`:

```java
package com.imin.iminapi.service.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the public event listing endpoint.
 *
 * <p>{@code lowStockThreshold} drives the {@code lowStock} flag on each
 * {@code PublicEventListItem}: an event that is not sold out but whose total
 * remaining inventory (across enabled tiers) is at or below this value is
 * flagged so the FE can render an "Almost gone" badge. Default 10.
 */
@ConfigurationProperties(prefix = "imin.public")
public class PublicListingProperties {
    /** Total-remaining cutoff (inclusive) below which a not-sold-out event is "low stock". */
    private int lowStockThreshold = 10;

    public int getLowStockThreshold() { return lowStockThreshold; }
    public void setLowStockThreshold(int lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }
}
```

  Create `PublicListingConfig.java` to register it (matching `TicketConfig`):

```java
package com.imin.iminapi.service.event;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PublicListingProperties.class)
public class PublicListingConfig {
}
```

- [x] **Step 7: Document the property in application.yaml.** In `application.yaml`, add a `public:` block as a child of the existing top-level `imin:` key (which begins at line 118, alongside its siblings `stripe:`, `email:`, `ticket:`, `apple-wallet:`, `cors:`, `api:`, `auth:`, `gate:`, `ratelimit:`, `media:`). Insert the block immediately after the `imin.ticket` block's final property line `api-public-base-url: ${IMIN_API_PUBLIC_BASE_URL:http://localhost:8080}` (currently line 170) and before the `apple-wallet:` key (line 171). Keep 2-space indent on `public:` (same column as `ticket:`) and 4-space indent on its child key so it nests under `imin:`. Add:

```yaml
  public:
    # Total remaining inventory (across enabled tiers) at or below which a
    # not-sold-out event is flagged lowStock on GET /api/v1/public/events,
    # so the buyer site can show an "Almost gone" badge. 0 disables the badge.
    low-stock-threshold: ${IMIN_PUBLIC_LOW_STOCK_THRESHOLD:10}
```

- [x] **Step 8: Wire the aggregate + flags into the service.** In `PublicEventService.java`: (a) inject `PublicListingProperties`; (b) batch-load the remaining rollup in `list(...)` next to the existing `priceByEvent` block; (c) derive the two flags in `toListItem`.

  **First — make the new bean available to BOTH sliced service-test contexts (REQUIRED, or every test in them fails to load).** `PublicEventServiceTest` AND `PublicEventServiceListTest` are sliced `@DataJpaTest` classes with no component scanning; each wires the service explicitly via `@Import({PublicEventService.class, …FixedClockConfig.class})`. Once `PublicEventService`'s constructor takes a `PublicListingProperties` (below), that bean must be in each slice or context load fails with `UnsatisfiedDependencyException: ... PublicListingProperties`. Add `PublicListingConfig.class` to the `@Import` in **both** classes so the `@EnableConfigurationProperties` registers `PublicListingProperties` with its default `lowStockThreshold=10`.

  In `PublicEventServiceTest.java` (line 31), change:

```java
@Import({PublicEventService.class, PublicEventServiceTest.FixedClockConfig.class})
```

  to:

```java
@Import({PublicEventService.class, PublicListingConfig.class, PublicEventServiceTest.FixedClockConfig.class})
```

  And in `PublicEventServiceListTest.java` (line 33), change:

```java
@Import({PublicEventService.class, PublicEventServiceListTest.FixedClockConfig.class})
```

  to:

```java
@Import({PublicEventService.class, PublicListingConfig.class, PublicEventServiceListTest.FixedClockConfig.class})
```

  (No new import statement is needed — `PublicListingConfig` is in the same package `com.imin.iminapi.service.event` as both test classes. The `@SpringBootTest`-based `PublicEventControllerTest` loads the full context and picks up `PublicListingConfig` via component scan, so it needs no `@Import` change.)

  Next, add the field + constructor param. Change the field block:

```java
    private final EventRepository eventRepository;
    private final TicketTierRepository tierRepository;
    private final OrganizationRepository organizationRepository;
    private final Clock clock;

    public PublicEventService(EventRepository eventRepository,
                               TicketTierRepository tierRepository,
                               OrganizationRepository organizationRepository,
                               Clock clock) {
        this.eventRepository = eventRepository;
        this.tierRepository = tierRepository;
        this.organizationRepository = organizationRepository;
        this.clock = clock;
    }
```

  to:

```java
    private final EventRepository eventRepository;
    private final TicketTierRepository tierRepository;
    private final OrganizationRepository organizationRepository;
    private final PublicListingProperties listingProperties;
    private final Clock clock;

    public PublicEventService(EventRepository eventRepository,
                               TicketTierRepository tierRepository,
                               OrganizationRepository organizationRepository,
                               PublicListingProperties listingProperties,
                               Clock clock) {
        this.eventRepository = eventRepository;
        this.tierRepository = tierRepository;
        this.organizationRepository = organizationRepository;
        this.listingProperties = listingProperties;
        this.clock = clock;
    }
```

  Next, in `list(...)`, the "// 4. Batch-load priceFromMinor + orgs" section: after the `priceByEvent` map and before the `orgsById` map, add the remaining rollup. Insert this block right after the closing line of the `priceByEvent` assignment:

```java
        // Per-event remaining rollup across enabled tiers (one batch query, no N+1).
        // row[1] = SUM(remaining) -> total (clamped >=0, feeds lowStock);
        // row[2] = MAX(remaining) -> kept signed so soldOut is MAX <= 0 (oversell-safe).
        Map<UUID, long[]> remainingByEvent = eventIds.isEmpty()
                ? Map.of()
                : tierRepository.findEnabledRemainingByEventIds(eventIds).stream()
                        .collect(Collectors.toMap(
                                row -> (UUID) row[0],
                                row -> new long[]{
                                        Math.max(0L, ((Number) row[1]).longValue()),
                                        ((Number) row[2]).longValue()
                                }));
```

  Then change the final `return` of `list(...)` to pass the rollup and threshold into the mapper. Replace:

```java
        return PageResponse.from(result, e -> toListItem(e, priceByEvent.get(e.getId()), orgsById.get(e.getOrgId())));
```

  with:

```java
        return PageResponse.from(result, e -> toListItem(
                e, priceByEvent.get(e.getId()), orgsById.get(e.getOrgId()),
                remainingByEvent.get(e.getId()), listingProperties.getLowStockThreshold()));
```

  Finally, replace the `toListItem` helper with the flag-deriving version:

```java
    private static PublicEventListItem toListItem(Event e, Integer priceFromMinor, Organization org,
                                                  long[] remaining, int lowStockThreshold) {
        // remaining == null => event has no enabled tiers; cannot be "sold out".
        // remaining[0] = total remaining (clamped >=0), remaining[1] = MAX single-tier remaining (signed).
        // soldOut = every enabled tier empty => the largest per-tier remaining is <= 0.
        boolean soldOut = remaining != null && remaining[1] <= 0L;
        boolean lowStock = remaining != null && !soldOut
                && remaining[0] <= lowStockThreshold;
        return new PublicEventListItem(
                e.getId(), e.getSlug(), e.getName(), e.getStatus().wireValue(), e.getPublishedAt(),
                e.getGenre(), e.getType(),
                e.getStartsAt(), e.getEndsAt(), e.getTimezone(),
                e.getVenueCity(), e.getVenueCountry(), e.getPosterUrl(), e.getCurrency(),
                priceFromMinor, soldOut, lowStock,
                new PublicOrganizationDto(org.getName(), org.getSlug()));
    }
```

  > `soldOut` reads the **signed `MAX`** (`remaining[1] <= 0`): the contract's "every enabled tier remaining == 0" holds exactly when the largest per-tier remaining is non-positive, and keeping `MAX` signed makes it oversell-safe (a SUM == 0 test could be masked by a +5/-5 pair across two tiers). `lowStock` is "not sold out AND total remaining (`remaining[0]`, clamped >=0) <= threshold". Both `long[]` slots are read; neither is dead. The five service tests in Step 2 all use non-oversold tiers, so they pass under either aggregate — but `MAX` is the one that matches the contract definition under oversell.

- [x] **Step 9: Run, confirm green.**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=PublicEventServiceTest,PublicEventServiceListTest,PublicEventControllerTest
```

Expected: `BUILD SUCCESS`, `Failures: 0, Errors: 0`. The five new service tests pass, the updated `list_responseItemKeysAreAllowListed` passes with the extended allowlist, `list_returns200WithCacheControlHeader` / `list_endpointReachableWithoutAuth` still pass (they use the updated `sampleListItem()`), and all of `PublicEventServiceListTest` stays green now that its `@Import` has the `PublicListingConfig` and its `PublicEventListQuery` calls carry the `includeOngoing` arg.

- [x] **Step 10: Run the full public-api suite to confirm no collateral breakage.**

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest='com.imin.iminapi.controller.publicapi.*,com.imin.iminapi.service.event.PublicEventServiceTest,com.imin.iminapi.service.event.PublicEventServiceListTest'
```

Expected: `BUILD SUCCESS`. Confirms the leak guardrail and the quote/checkout/notify public tests are unaffected by the new fields.

- [x] **Step 11: Commit.**

```bash
cd /Users/ivan/imin/imin-api && git add -A && git commit -m "feat(public): add soldOut/lowStock availability flags to listing items

Derived per-page from a single enabled-tier remaining aggregate (no N+1),
mirroring findMinEnabledPriceByEventIds. lowStock threshold via new
imin.public.low-stock-threshold property (default 10). Leak-guardrail
allowlist updated in the same change.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2.4: Document `soldOut` / `lowStock` in PUBLIC_PAGE_API.md §9.2 (imin-public)

**Files:**
- Modify: `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md`

**Covers:** "5. Listing availability flags - add soldOut (every enabled tier remaining == 0) and lowStock (not sold out, total remaining below a threshold) booleans to PublicEventListItem on GET /api/v1/public/events. ... Document in PUBLIC_PAGE_API.md section 9.2. Pick the lowStock threshold from any existing convention in the codebase, else propose a config property with a sane default (e.g. 10)."

- [x] **Step 1: Add the two fields to the §9.2 example JSON.** In the §9.2 `PublicEventListItem` example object (the one ending `"organization": { "name": "Funkhaus Productions", "slug": "funkhaus" }`), add `soldOut` and `lowStock` before `organization`. Replace:

```json
  "priceFromMinor": 2500,
  "organization": { "name": "Funkhaus Productions", "slug": "funkhaus" }
}
```

  with:

```json
  "priceFromMinor": 2500,
  "soldOut": false,
  "lowStock": false,
  "organization": { "name": "Funkhaus Productions", "slug": "funkhaus" }
}
```

- [x] **Step 2: Add a `soldOut` / `lowStock` subsection after the `priceFromMinor` block in §9.2.** Immediately after the `#### `priceFromMinor`` block (the bullet list ending `... Detail page reveals sold-out state.`), insert:

```markdown
#### `soldOut` / `lowStock`

Per-event availability flags, derived server-side from the **enabled** tiers' total remaining inventory (`remaining = quantity − reserved − sold`, clamped at 0 per tier). Same 60s-stale caveat as `priceFromMinor` — use them for badges, not for gating the buy flow (checkout validates inventory atomically).

| Flag | Meaning |
|---|---|
| `soldOut` | `true` when the event has at least one enabled tier and **every** enabled tier has `remaining == 0`. An event with no enabled tiers is **not** `soldOut` (`false`). |
| `lowStock` | `true` when the event is **not** `soldOut` and its **total** remaining across enabled tiers is at or below a server-side threshold (default **10**, configurable via `imin.public.low-stock-threshold`). |

- The two flags are mutually exclusive: an event is never both `soldOut` and `lowStock`.
- Threshold-related semantics may be tuned server-side; do not hard-code `10` on the FE — treat `lowStock` as the authoritative "almost gone" signal.
- Computed via a single SQL aggregation per page (no N+1), same as `priceFromMinor`.
```

- [x] **Step 2b: Confirm the §9.5 leak-guardrail reference is still accurate.** The §9.5 bullet referencing `list_response_item_keys_are_allow_listed` documents the snapshot test by name. The real method is camelCase (`list_responseItemKeysAreAllowListed`); leave the doc's prose name as-is (it is descriptive, not a code reference) unless you also want to correct it — optional, no behavior impact.

- [x] **Step 3: Bump the Last updated date if not already done by Task 2.2.** Ensure the top-of-file line reads `**Last updated:** 2026-06-10`. (If Task 2.2 already set it, this is a no-op.)

- [x] **Step 4: Commit in the imin-public repo.**

```bash
cd /Users/ivan/imin/imin-public && git add docs/PUBLIC_PAGE_API.md && git commit -m "docs(public-api): document soldOut/lowStock listing flags (§9.2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

## Package 3: Refund-form context: venue timezone

The buyer refund form (`GET /api/v1/public/refund-requests/by-token/{token}`) renders the event start time but its `event` sub-object carries no `timezone`, so the FE labels the time "UTC". This package adds an IANA `timezone` field to the form-context `event` object, matching what the order (§13.1) and ticket (§14) payloads already carry, by resolving the order's `Event` in `RefundRequestService.lookupByToken` and copying `event.getTimezone()` exactly as `PublicOrderController.eventBlock` does. FE-gate severity: **COSMETIC** — the form works today, this only corrects the timezone label; no FE code is blocked, the new field is purely additive.

> Branch: this package's imin-api work happens on `feat/public-contract-companion` (created off `master` as Task 1.1 of Package 1). Do not branch again here. The doc-sync task commits in the separate `imin-public` repo.

### Task 3.1: Add `timezone` to the refund-form `event` object

**Files:**
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/refund/dto/PublicRefundFormResponse.java`
- Modify: `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/refund/RefundRequestService.java`
- Test: `/Users/ivan/imin/imin-api/src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java`

**Covers:** "2. Refund-form context venue timezone - GET /api/v1/public/refund-requests/by-token/{token} must add \"timezone\" (IANA name, e.g. Europe/Berlin) to its event object, matching what order (section 13.1) and ticket (section 14) payloads already carry. Document in PUBLIC_PAGE_API.md the new field. COSMETIC for FE (currently labels the time UTC)."

- [x] **Step 1: Add a failing assertion that the form-context event carries the venue timezone.** The existing `LookupByToken` nested class constructs the service via the 13-arg constructor and seeds an `Order` with an `eventId` but never stubs an `EventRepository`. Replace the `returns_form_data_for_valid_token` test body so it seeds an `Event` and asserts the new `event().timezone()`. In `/Users/ivan/imin/imin-api/src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java`, replace the whole method (lines 187–206):

```java
        @Test
        void returns_form_data_for_valid_token() {
            when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            com.imin.iminapi.model.Event event = new com.imin.iminapi.model.Event();
            event.setId(o.getEventId());
            event.setName("Summer Fest 2026");
            event.setStartsAt(Instant.parse("2026-07-15T18:00:00Z"));
            event.setTimezone("Europe/Berlin");
            event.setVenueName("Funkhaus");
            when(events.findById(o.getEventId())).thenReturn(Optional.of(event));
            com.imin.iminapi.model.Ticket t = new com.imin.iminapi.model.Ticket();
            t.setId(UUID.randomUUID());
            t.setOrderId(orderId);
            t.setTierId(UUID.randomUUID());
            t.setPriceMinor(2500);
            t.setState(com.imin.iminapi.model.Ticket.STATE_ISSUED);
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(refundService.computeRefundAmountMinor(eq(o), org.mockito.ArgumentMatchers.anyList())).thenReturn(2000L);

            var resp = service.lookupByToken("raw");

            assertThat(resp.estimatedRefundMinor()).isEqualTo(2000L);
            assertThat(resp.tickets()).hasSize(1);
            assertThat(resp.reasons()).contains("cant_attend", "other");
            assertThat(resp.event().timezone()).isEqualTo("Europe/Berlin");
            assertThat(resp.event().name()).isEqualTo("Summer Fest 2026");
            assertThat(resp.event().startsAt()).isEqualTo(Instant.parse("2026-07-15T18:00:00Z"));
            assertThat(resp.event().venueName()).isEqualTo("Funkhaus");
        }
```

- [x] **Step 2: Add the `EventRepository` mock field and pass it to the service constructor in the test.** In the same file, add the mock field next to the other repo mocks (after line 35, `OrderRepository orders = mock(OrderRepository.class);`):

```java
    com.imin.iminapi.repository.EventRepository events = mock(com.imin.iminapi.repository.EventRepository.class);
```

  Then update the constructor call in `setUp()` (lines 58–60) to pass `events` as the new second argument (the source change in Step 5 inserts it right after `orders`):

```java
        service = new RefundRequestService(orders, events, attempts, tokens, requests,
            email, renderer, emailProps, ticketProps, publisher,
            tickets, refundTickets, tiers, refundService);
```

- [x] **Step 3: Run the test, expect a compile failure.** Run:

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=RefundRequestServiceTest
```

  Expected: compilation failure — `RefundRequestService` has no 14-arg constructor and `PublicRefundFormResponse.EventSummary` has no `timezone()` accessor. This is the red state (cannot construct the service / unknown method).

- [x] **Step 4: Add the `timezone` field to `EventSummary`.** In `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/refund/dto/PublicRefundFormResponse.java`, change the `EventSummary` record (line 17) to include `timezone` right after `startsAt`, mirroring the field order in `PublicOrderResponse.Event` (name, …, startsAt, timezone, venueName, …):

```java
    public record EventSummary(String name, Instant startsAt, String timezone, String venueName, String currency) {}
```

- [x] **Step 5: Inject `EventRepository` into `RefundRequestService`.** In `/Users/ivan/imin/imin-api/src/main/java/com/imin/iminapi/refund/RefundRequestService.java`, add the import next to the other repository imports (after line 22, `import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;`):

```java
import com.imin.iminapi.repository.EventRepository;
```

  Add the field next to the other final repos (after line 68, `private final OrderRepository orders;`):

```java
    private final EventRepository events;
```

  Add the constructor parameter immediately after `OrderRepository orders` (line 82) and assign it. Replace the constructor signature head + the first two assignments (lines 82–96, i.e. through `this.attempts = attempts;`) with the block below. Note both original assignments (`this.orders` and `this.attempts`) are preserved — only the new `this.events = events;` line is inserted between them — so the `final attempts` field stays assigned:

```java
    public RefundRequestService(OrderRepository orders,
                                EventRepository events,
                                OrderRecoveryAttemptRepository attempts,
                                RefundRequestTokenRepository tokens,
                                RefundRequestRepository requests,
                                EmailService email,
                                EmailTemplateRenderer renderer,
                                EmailProperties emailProps,
                                TicketProperties ticketProps,
                                ApplicationEventPublisher publisher,
                                TicketRepository tickets,
                                RefundTicketRepository refundTickets,
                                TicketTierRepository tiers,
                                RefundService refundService) {
        this.orders = orders;
        this.events = events;
        this.attempts = attempts;
```

- [x] **Step 6: Resolve the `Event` in `lookupByToken` and populate the `EventSummary`.** In the same file, inside `lookupByToken`, after the `Order order = orders.findById(...)` block (the `.orElseThrow(...)` closing at line 186) and before `List<Ticket> refundable = refundableTicketsFor(order);` (line 188), insert the event lookup. The order's event always exists for a refundable order; if it is somehow missing we degrade to a null event block rather than 410 the buyer out of a working refund:

```java
        Event event = events.findById(order.getEventId()).orElse(null);
```

  Add the `Event` model import next to the other model imports (after line 6, `import com.imin.iminapi.model.Order;`):

```java
import com.imin.iminapi.model.Event;
```

  Then replace the `order.getId(),` argument **plus** the two-line comment and the placeholder `EventSummary` construction (lines 205–209, i.e. from `order.getId(),` through the `null, null, null, order.getCurrency()),` line inclusive) with the block below — populating from the resolved event and copying the exact `getTimezone()` idiom used in `PublicOrderController.eventBlock`. The replacement re-emits `order.getId(),` as its first line, so the range MUST start at line 205 (the existing `order.getId(),`) to avoid leaving a duplicate:

```java
            order.getId(),
            new PublicRefundFormResponse.EventSummary(
                event == null ? null : event.getName(),
                event == null ? null : event.getStartsAt(),
                event == null ? null : event.getTimezone(),
                event == null ? null : event.getVenueName(),
                order.getCurrency()),
```

  (The now-stale `// Event name lookup intentionally omitted here; ...` comment on lines 206–207 is dropped by this same replacement.)

- [x] **Step 7: Run the unit test, expect green.** Run:

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=RefundRequestServiceTest
```

  Expected: `BUILD SUCCESS`, all `RefundRequestServiceTest` tests pass including the updated `returns_form_data_for_valid_token` asserting `timezone == "Europe/Berlin"`.

- [x] **Step 8: Run a `@SpringBootTest` to confirm the real service still wires.** `PublicRefundRequestControllerTest` replaces the service with `@MockitoBean RefundRequestService service`, so it does NOT exercise the real constructor and would pass regardless — use it only as a fast context-boot smoke check, not as the wiring guard. The real `RefundRequestService` bean (with its new `EventRepository` arg) is constructed by any full-context test that does not mock it; `EventRepository` is a standard Spring Data JPA bean already in the context (used by `PublicOrderController`), so autowiring resolves with no further config. Run:

```bash
cd /Users/ivan/imin/imin-api && ./mvnw test -Dtest=PublicRefundRequestControllerTest
```

  Expected: `BUILD SUCCESS`, all 3 tests pass (context boots; the new arg is satisfiable for the real bean).

- [x] **Step 9: Commit the backend change.** Run:

```bash
cd /Users/ivan/imin/imin-api && git add src/main/java/com/imin/iminapi/refund/dto/PublicRefundFormResponse.java src/main/java/com/imin/iminapi/refund/RefundRequestService.java src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java && git commit -m "$(cat <<'EOF'
feat(refund): add venue timezone to public refund-form context

GET /api/v1/public/refund-requests/by-token/{token} now resolves the
order's Event and surfaces event.timezone (IANA name) plus name,
startsAt, and venueName, matching the order (§13.1) and ticket (§14)
public payloads. The FE was labelling the refund-form start time UTC
because no timezone was carried. Cosmetic, additive field.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

  Expected: one commit on `feat/public-contract-companion`.

### Task 3.2: Document the refund-form `timezone` field in PUBLIC_PAGE_API.md

**Files:**
- Modify: `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md`

**Covers:** "2. Refund-form context venue timezone - GET /api/v1/public/refund-requests/by-token/{token} must add \"timezone\" (IANA name, e.g. Europe/Berlin) to its event object, matching what order (section 13.1) and ticket (section 14) payloads already carry. Document in PUBLIC_PAGE_API.md the new field. COSMETIC for FE (currently labels the time UTC)."

- [x] **Step 1: Append a refund-form-context section documenting the response shape with `timezone`.** The buyer refund-request surface is not yet documented in `PUBLIC_PAGE_API.md` (zero `refund` mentions today), so add a new top-level section at the end of the file. Append after the final line (after line 938, the `follow-up.` line that closes §16) in `/Users/ivan/imin/imin-public/docs/PUBLIC_PAGE_API.md`:

```markdown

---

## 17. Refund-request form context

```
GET /api/v1/public/refund-requests/by-token/{token}
```

Loads the refund form for a valid, unexpired, unconsumed refund-link token
(emailed to the buyer via `POST /api/v1/public/refund-requests`). Drives the
`/refund/{token}` page on the buyer site (event headline + start time,
refundable ticket lines, estimated refund). No auth.

### 17.1 Response shape

`200 OK`:

```json
{
  "orderId": "5f3c…-uuid",
  "event": {
    "name": "Summer Fest 2026",
    "startsAt": "2026-07-15T18:00:00Z",
    "timezone": "Europe/Berlin",
    "venueName": "Funkhaus",
    "currency": "EUR"
  },
  "tickets": [
    { "id": "a1b2…-uuid", "tierName": "Early bird", "faceMinor": 2500 }
  ],
  "estimatedRefundMinor": 2000,
  "currency": "EUR",
  "reasons": ["cant_attend", "event_changed", "duplicate_purchase", "not_as_described", "other"]
}
```

`event.timezone` is the IANA tz name (e.g. `"Europe/Berlin"`) — render
`event.startsAt` in it rather than labelling the time UTC. It matches the
`timezone` field already carried by order retrieval (§13.1) and web ticket
retrieval (§14.1). `event.name`, `event.startsAt`, `event.timezone`, and
`event.venueName` may each be `null` if the order's event can no longer be
resolved; the form still renders from the ticket lines and estimate.

### 17.2 Errors

| HTTP | `code` | When |
|---|---|---|
| `410` | `REFUND_TOKEN_EXPIRED_OR_CONSUMED` | Token unknown, expired, or already used. Single leak-safe code for all three so a probe can't distinguish them. |
| `409` | `NO_REFUNDABLE_TICKETS` | Every ticket on the order is already refunded or redeemed. |
```

- [x] **Step 2: Commit the doc change in the imin-public repo.** This is a separate git repo; the doc-sync commit lands there, not in imin-api. Run:

```bash
cd /Users/ivan/imin/imin-public && git add docs/PUBLIC_PAGE_API.md && git commit -m "$(cat <<'EOF'
docs(public-api): document refund-form context endpoint + event.timezone

Adds §17 for GET /api/v1/public/refund-requests/by-token/{token},
documenting the new event.timezone (IANA) field that the backend now
returns, matching order (§13.1) and ticket (§14.1) payloads.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

  Expected: one commit on the current imin-public branch touching only `docs/PUBLIC_PAGE_API.md`.
