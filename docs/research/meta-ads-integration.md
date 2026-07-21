# Meta Ads Integration — scope decision & plan

- **Task:** ClickUp 86cav34p2 — decide integration scope (pixel-only vs full ads management) for imin.wtf.
- **Author:** research spike, 2026-07-21. Web facts cited inline; access date = 2026-07-21.
- **TL;DR:** The Pixel + Conversions API (CAPI) measurement foundation is **already live in prod**. It needs finishing, not re-proposing. Ads *management* (creating/running campaigns) is entirely greenfield and is a much larger commitment gated by App Review + Business Verification. Recommendation: **finish the measurement layer, then let organizers connect their own ad account via Facebook Login for Business for read-only reporting; defer campaign creation.**

---

## 1. Current state — what already ships (live in prod since the 2026-06 UTM project)

This is a **measurement-only** integration built on manual token paste. It touches **zero** Marketing API / ad-account surface.

**Backend (`imin-api`, package `com.imin.iminapi.marketing`):**
- `MetaPixelConnection` + `V59__meta_pixel_connections.sql` — one connection per `(org, event)`; `event_id NULL` = org-wide default. Stores `pixel_id`, `capi_access_token_enc`, `test_event_code`, `status`.
- `MetaConnectionController.java:26` — organizer surface `/api/v1/marketing/meta`: `GET/PUT/DELETE /connection`, `POST /connection/test`, `GET /stats`, `GET /funnel`. Org from auth context, never the path.
- `PutMetaConnectionRequest.java:11` — organizer **pastes** `pixelId` + `capiAccessToken` (self-minted System-User token) + `testEventCode`. **No OAuth anywhere.**
- `CapiTokenCipher.java` — token stored AES-256-GCM (`imin.meta.enc-key`), write-only, never echoed back.
- `MetaCapiOutboxWriter.java:56` — at order-paid, writes one outbox row **iff `orders.ads_consent` (`:62`) AND an active pixel connection (`:69`)**. Hashes email (SHA-256), carries `fbp`/`fbc`, `value`, `currency` (rejected if blank).
- `MetaCapiPoller.java:57` — `@Scheduled` 30s (ShedLock), drains outbox → Graph. Backoff 1m→12h, dead after 5 attempts → Sentry.
- `MetaGraphClient.java:45` — `POST /{version}/{pixelId}/events`, Graph `v25.0` (configurable). **CAPI events only** — no ad/campaign endpoints.
- `MetaGraphConfig.java` + `application.yaml:297` (`imin.meta.*`).
- Consent wiring: `orders.ads_consent` (`V60`, `Order.java:87`) set from checkout — `StripeCheckoutController.java:47`, `PaidCheckoutService.java:150`, `FreeCheckoutService.java:109`.
- Pixel exposed to buyer site via `PublicEventService.java:89` → `PublicEventResponse.metaPixelId` (event override → org default).
- CAPI is **`Purchase`-only** server-side (`MetaCapiOutboxWriter.java:85`); `PageView`/`InitiateCheckout` fire browser-pixel-only.

**Frontend (`imin-public`, read-only checkout):**
- `components/meta/meta-pixel-loader.tsx` — injects `fbevents.js` **only after consent granted** + pixel id present.
- `lib/meta-pixel.ts` — `metaTrack` fires `PageView`/`InitiateCheckout`/`Purchase`, consent-gated, with `eventID` for browser↔CAPI dedup.
- `app/order/[token]/PurchasePixel.tsx` — Purchase with `eventID = orderToken` (matches CAPI `event_id = order_token`).
- `components/consent/consent-state.ts` — binary `imin.consent` = `granted`/`denied` in localStorage; `consent-banner.tsx`. `buy-modal.tsx:451` sends `adsConsent: getConsent()==="granted"`.

**Surprises from the audit (things the doc must build ON, not re-propose):**
1. The integration is **measurement-only + manual-token-paste** — no OAuth, no Marketing API, no ad account is touched. Ads *management* is 100% greenfield.
2. Consent gating **already matches the strictest 2026 EU legal bar** (no consent ⇒ no pixel *and* no CAPI). See §4.
3. Server-side CAPI is **Purchase-only**; upper-funnel signal exists only in the browser pixel (thinner match quality than achievable).
4. **No `data_processing_options`/LDU is sent anywhere.** Fine for EU (we hard-gate on consent), but a gap the day imin takes US traffic (§4).
5. Consent is a **binary localStorage banner**, not a TCF/CMP; consent is enforced by *not firing*, never transmitted to Meta as a signal.

---

## 2. Research findings (2026, cited)

**Q1 — Business Manager + business verification (French/EU platform).**
Review runs ~2–14 days (commonly quoted 5–15 business days/cycle). Practical prerequisites: BM ideally 30–60 days old, initiating admin has 2FA, ≥2 admins. Verification is the gate for **Advanced/Full API access, WhatsApp scale, Custom Conversions, Audience Sharing** — it is **NOT required for Pixel/CAPI**. ([singhamandeep](https://singhamandeep.com/meta-business-verification-documents-required/), [agrowth.io](https://agrowth.io/blogs/facebook-ads/how-to-verify-your-business-on-meta), [360dialog](https://docs.360dialog.com/docs/resources/meta-business-verification))

**Q2 — App Review & access tiers (renamed 4 May 2026).**
"Ads Management Standard Access" → **"Marketing API Access Tier"**; tiers renamed **Standard→Limited** and **Advanced→Full**. *Limited*: no review, only ad accounts you own/manage, cannot build tools for other businesses. *Full*: managing ads for **third-party** advertisers, **requires App Review** (weeks). Qualifying threshold lowered 1,500→**500 Marketing API calls in 15 days**, error rate <15%, screen recording no longer required. Core ads permissions: `ads_management`, `ads_read`, `business_management`. ([Meta dev blog](https://developers.meta.com/blog/updates-to-ads-management-standard-access-feature/), [adamigo](https://www.adamigo.ai/blog/meta-ads-api-access-levels-for-agencies))
**Confirmed:** Pixel/CAPI need **no App Review, no permissions, no Business Verification** — official docs: *"Your app does not need to go through App Review. You do not need to request any permissions."* Token is a System-User token from Events Manager. ([Meta CAPI Get Started](https://developers.facebook.com/documentation/ads-commerce/conversions-api/get-started))

**Q3 — Agency model (running ads on behalf of organizers).**
Three shapes: (a) **System-User tokens** — non-expiring, best for server-to-server, but only for accounts inside *your own* BM; (b) **Business asset sharing / partner** — request access to a client Page/Ad Account; (c) **Facebook Login for Business (FLB)** — the organizer OAuths and grants scoped access (`ads_read`/`ads_management`/`business_management`) to *their own* assets. **App Review is required precisely when you access ad accounts belonging to other businesses** (i.e. any real multi-organizer SaaS). Meta ships an official **"FLB → Conversions API integration template"** and one-link onboarding for tech providers. For a ticketing SaaS the fitting model is **FLB: each organizer connects their own ad account**, not imin running everything from one house account. ([Meta Marketing API](https://developers.facebook.com/documentation/ads-commerce/marketing-api), [System Users](https://developers.facebook.com/docs/business-management-apis/system-users/install-apps-and-generate-tokens/), [FLB CAPI template](https://developers.facebook.com/documentation/facebook-login/facebook-login-for-business/conversions-api-integration-template/))

**Q4 — GDPR / consent (2026).**
A **2026 German court ruling**: with no user consent you must **not fire CAPI at all — not even with limited data**. imin's design already does exactly this. **LDU (`data_processing_options:['LDU']`) is a US-state-law mechanism (CCPA/CPRA)**, not the EEA/GDPR path — it limits personalization while still counting conversions. EU compliance = consent gating (done) + DPA with Meta + hashing (done) + minimization + retention + DSAR. Meta can apply LDU by geography automatically or you set flags per event. ([FlexyConsent](https://flexyconsent.com/blog/meta-pixel-facebook-conversions-api-consent-guide/), [Meta LDU 2026](https://www.auditsocials.com/blog/meta-limited-data-use-2026-state-privacy-signals-custom-audiences-conversions-api-retargeting), [adamigo compliance](https://www.adamigo.ai/blog/meta-conversion-data-compliance-best-practices))

**Q5 — Event-ticketing specifics.**
**Special Ad Categories** (employment/housing/credit/social-issues-politics) generally **do not** apply to ticketing — *unless* an event is political/social-issue; if imin ever runs ads *for* organizers it must let them declare a category. **Advantage+ Shopping (ASC)** is optimized for always-available products and needs ~**50 optimization events/week** to exit learning — an awkward fit for a single time-boxed, sell-out event; automation "works but is shopping-shaped." Meta is steering budget toward Advantage+ automation. ([Hive: Meta Ads for event marketers 2026](https://resources.hive.co/articles/meta-ads-in-2026-6-shifts-event-marketers-cant-ignore), [Jon Loomer: special ad categories](https://www.jonloomer.com/qvt/special-ad-categories-targeting/), [AdManage ASC](https://admanage.ai/blog/meta-advantage-plus-shopping-campaigns))

---

## 3. DECISION — recommended scope (phased)

**Adopt a phased "measurement first, reporting next, campaign creation last/maybe" scope.** Rationale: measurement is done and legally clean and needs no Meta approvals; reporting delivers organizer value for one OAuth + read-only scopes; campaign *creation* is a heavy, high-liability surface (App Review Full access, ad-policy ownership, special-ad-category handling, billing) that a ticketing SaaS should not own until there is proven demand.

**Phase 0 — Harden the live Pixel+CAPI foundation (no Meta approvals needed).**
- Add server-side `InitiateCheckout` (and optionally `ViewContent`) to the CAPI outbox to lift match quality beyond Purchase-only.
- Add `data_processing_options` plumbing (empty/LDU by geography) so US traffic is compliant the day it exists — cheap insurance, no behaviour change for EU.
- Optional UX: replace manual token paste with Events-Manager-guided setup; keep paste as fallback.
- Effort: **~3–5 dev-days.** Prereqs: none. Risk: low.

**Phase 1 — Organizer connects their own ad account (Facebook Login for Business), READ-ONLY.**
- FLB OAuth; request `ads_read` (+ `business_management` to list assets). Store per-org token (encrypt like the CAPI token). Surface spend / ROAS / campaign results next to imin's own funnel + attribution (`/api/v1/analytics/attribution`).
- This makes ROAS **real** (replaces the current mock) by joining Meta spend to imin conversions.
- Effort: **~2–3 dev-weeks** (OAuth, token lifecycle/refresh, reporting endpoints, dashboard UI). Prereqs: **Business Verification** (start early, ~2 wk lead) + **App Review for Full/Advanced access** because we read *other businesses'* ad accounts (~weeks). Risk: medium — token lifecycle + review.

**Phase 2 — (DEFER / optional) assisted campaign creation.**
- Only if organizers ask. Would need `ads_management` Full access, ad-policy + special-ad-category handling, creative pipeline (imin already has AI posters), budgeting/billing ownership, and Advantage+ that fits events poorly.
- Effort: **6–10+ dev-weeks + ongoing policy liability.** Recommend a "boosted-post / single-objective, event-scoped" MVP over full campaign management *if* pursued. Risk: high.

**Explicitly DO NOT build (now):**
- No house/agency ad account running campaigns for all organizers under one BM (concentrates policy risk + isn't the platform model).
- No full campaign manager / Ads-Manager clone; no audience/lookalike builder; no Advantage+ Shopping wrapper.
- No LDU-as-EU-compliance (LDU is US-state; EU stays consent-gated).
- No storing raw (unhashed) PII for Meta; no CAPI without the existing consent gate.

---

## 4. Plan — phases, prerequisites, risks

| Phase | Scope | Effort | Meta approvals | Key risk |
|---|---|---|---|---|
| 0 | Harden Pixel+CAPI (IC/VC events, DPO/LDU plumbing, guided setup) | 3–5 d | none | low |
| 1 | FLB connect own ad account, read-only ROAS/reporting | 2–3 wk | **Business Verification + App Review (Full/`ads_read`)** | token lifecycle, review latency |
| 2 (defer) | Assisted event-scoped campaign creation | 6–10+ wk | App Review (`ads_management` Full) | ad-policy liability, billing, special-ad-cat, Advantage+ misfit |

**Prerequisites / lead times to start NOW if Phase 1 is greenlit:**
- **Business Verification** — begin immediately; BM should be 30–60 days old, 2 admins + 2FA; ~2–14 day review. Gate for Full access.
- **App Review (Full/Advanced)** — needs ≥500 Marketing API calls/15 days once integrating, error rate <15%; review runs weeks. Sequence: build against own test account → hit call volume → submit.
- **DPA with Meta** in place (compliance, Phase 0/1).

**Cross-repo note:** Phase 0 CAPI additions are `imin-api`-only. Phase 1 adds an `/api/v1/marketing/meta/*` reporting surface (imin-api) + dashboard UI (`imin-webapp`) — ship together per the workspace's synchronized-cross-repo rule; run `api:sync` after the API deploys.

**Top risks:** (1) App Review latency/rejection blocks Phase 1 — mitigate by starting verification early and reading Meta's per-permission requirements in the App Dashboard. (2) Token lifecycle — FLB user tokens expire (~60 d); prefer long-lived/System-User where the asset model allows, add refresh + reconnect UX. (3) Scope creep into Phase 2 — hold the line; campaign creation is a product decision, not a technical default.
