# Mobile store readiness — what can be prepared before the company exists

**Date:** 2026-08-15 · **Status:** preparation · **Blocked on:** company registration (see `docs/decisions/ADR-0003-mobile-app-identity-registry.md`)

No Apple Developer or Google Play account can be opened until the legal entity exists, and neither store accepts a submission without one. This document is everything that can be *finished* before then, so that when the accounts open the remaining work is filling in forms, not making decisions.

Rule applied throughout: **every declaration below traces to a field that actually exists in the codebase.** Store privacy declarations are legally binding statements, and a label describing data we do not collect is as wrong as one omitting data we do.

---

## 1. What the app actually collects — the source of truth for both stores

Derived by reading the model, not by guessing at a feature list.

| Data | Where it lives | Why collected | Linked to identity? | Used for tracking? |
|---|---|---|---|---|
| Email address | `buyer_account_emails.email`, `orders.email` | Ticket delivery (contract), sign-in | yes | no |
| Name | `BuyerAccount.firstName`, `.lastName`, `.displayName` | Optional profile; name on the order | yes | no |
| Phone number | `orders.buyerPhone` | SMS ticket delivery, only with consent | yes | no |
| Date of birth | `BuyerAccount.dateOfBirth` | Age-restricted events | yes | no |
| City | `BuyerAccount.city` | Feed filtering. **Chosen from a picker, never sensed** — the app requests no location permission | yes | no |
| Purchase history | `orders`, `tickets` | The product | yes | no |
| Payment info | Stripe only | — | **never touches our servers or the app** | no |
| Push token | `buyer_push_devices.expo_token` (V92) | Drop alerts | yes | no |
| Crash / performance data | Sentry | Diagnostics | no (PII scrubbed) | no |
| Anonymous session id + UTM | `event_funnel_events` | First-party funnel analytics | no | no |

**Two declarations that follow from this, and are worth stating explicitly because they are easy to get wrong later:**

1. **No location permission.** The city is a manual picker (`app/cities/`), and `venue_latitude/longitude` are venue coordinates, not the user's. If a "near me" feature is ever added, this section and the store labels change together.
2. **No tracking, therefore no ATT prompt.** `imin-public` loads a Meta Pixel (`components/meta/meta-pixel-loader.tsx`, consent-gated). The app **must not** ship the Meta SDK: doing so converts every row above into "Data Used to Track You", forces an App Tracking Transparency prompt, and typical opt-in is ~25%. The first-party funnel beacon (`/track`, anonymous id + UTM) is not tracking under Apple's definition — it is not linked to identity and is not shared with a data broker. Keep the two deliberately separate.

---

## 2. Apple privacy "nutrition label" — draft answers

Ready to paste into App Store Connect.

- **Contact Info** → Email Address, Name, Phone Number · linked to user · purposes: App Functionality
- **Purchases** → Purchase History · linked to user · purposes: App Functionality
- **Identifiers** → Device ID (push token) · linked to user · purposes: App Functionality
- **User Content** → *none*
- **Diagnostics** → Crash Data, Performance Data · **not** linked to user · purposes: App Functionality
- **Usage Data** → Product Interaction · **not** linked to user · purposes: Analytics
- **Location** → *not collected*
- **Sensitive Info** → *not collected* (date of birth is declared under Contact Info as "Other User Contact Info" if Apple's form has no DOB category; it is not health or demographic data as Apple defines it)
- **"Used for Tracking"** → **No**, for every category

Google Play Data Safety mirrors these one-for-one. Play additionally requires: data is encrypted in transit (yes — HTTPS everywhere, HSTS on `api.imin.wtf`), and users can request deletion (yes — the Art. 17 flow already ships at `/profile/security`, which also satisfies **App Store Guideline 5.1.1(v)**, the account-deletion requirement that rejects apps lacking it).

---

## 3. Legal and support URLs the stores require

Both stores refuse a submission without a reachable privacy policy URL. **All of these pages already exist and are live** — `imin-public/app/legal/{privacy,terms,refunds}` and `app/help`. Nothing needs writing; the pages already say the entity details are being finalised (`legal/privacy/page.tsx:86`, `legal/terms/page.tsx:31`), which is exactly the one blocked field.

| Requirement | URL | Status |
|---|---|---|
| Privacy policy | `https://app.imin.wtf/legal/privacy` | live; entity name + registered address are the only gaps |
| Terms of service | `https://app.imin.wtf/legal/terms` | live; entity name is the only gap |
| Refund policy | `https://app.imin.wtf/legal/refunds` | live — Play requires this for a ticketing app |
| Support URL | `https://app.imin.wtf/help` | live |
| Marketing URL | `https://imin.wtf` | ready |
| Copyright line | — | needs entity name |

So this section reduces to a single find-and-replace on the day the entity is registered. Worth re-reading the privacy page against §1 above at that point: it was written for the web buyer site and does not yet mention the push token, which the app introduces (V92).

---

## 4. Store listing content — writable now

Nothing here needs an account. Doing it now means submission day is mechanical.

- **App name:** `imin` · **Subtitle (iOS, ≤30 chars):** `Nightlife tickets`
- **Promotional text** (170 chars, changeable without review — use it for what is on sale this week)
- **Description**, **keywords** (100 chars, iOS), **What's New** for 1.0.0
- **Screenshots:** 6.7" and 6.5" iPhone, plus Android phone. Generate from the real app against seeded data — never mock-ups, and never invented sold-out counts or fake attendee numbers.
- **App icon:** 1024×1024, no alpha, no rounded corners. **Every logo mark currently on disk is PNG**, so this is a design task, not an export task.
- **Age rating:** the questionnaire will ask about alcohol references. Nightlife events reference alcohol; answer honestly rather than optimising for a lower rating — a wrong answer is a removal risk.
- **Categories:** primary `Entertainment`, secondary `Lifestyle`.
- **Localisations:** EN, ES, FR. **Not Ukrainian** — out of scope for the app, which is also why the React Native font-fallback problem disappears (Barlow Condensed has no Cyrillic cut).

---

## 5. What is genuinely blocked, and what is not

**Blocked on the company — nothing else:**
- Signed device builds, TestFlight, internal testing tracks
- Push on a *physical* iOS device (the simulator cannot receive APNs)
- Sign in with Apple end-to-end (needs the App ID and the `.p8` key)
- Apple Wallet passes (needs the Pass Type ID certificate)
- Any submission

**Not blocked — all of this proceeds now:**
- The whole of Phase 0 backend (`docs/superpowers/plans/2026-08-15-mobile-phase0-backend.md`) — it touches no store account
- The app repo, the entire UI, navigation, the design-token pipeline, the ported domain logic
- iOS Simulator and Android emulator builds
- Android push on an emulator, once a Firebase project exists — a **Firebase project needs no paid account**, only the Play *listing* does
- Jest, Maestro, CI
- Everything in §2, §3 and §4 above

The practical consequence: the app can be built to feature-complete and demoed end-to-end on a simulator. What waits for the entity is putting it on a real phone that is not plugged into a Mac, and putting it in front of the public.

---

## 6. One thing to decide before the app repo is scaffolded

**Do not open an Apple Developer account in a personal name "to unblock testing."** An Individual account publishes under the person's legal name, cannot use a trade name, and migrating to an Organization later requires a new membership plus a manual app-record transfer through Apple support. The temptation is real — it is the only thing standing between the team and a device build — and the cost is a permanently wrong developer name on the listing, or a support ticket to undo it.

The honest alternative while waiting: simulator builds cover everything except push-on-device and Wallet.
