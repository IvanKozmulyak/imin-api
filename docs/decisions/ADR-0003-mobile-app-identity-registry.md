# ADR-0003: Mobile app identity registry — names, ids and accounts

Status: Accepted (decisions locked) · Blocked on company registration (accounts unopened)
Date: 2026-08-15

## Context

The IMIN fan app ships to the App Store and Google Play as a fifth repo, `imin-fan-app` (see `design_handoff_imin_mobile/STACK-DECISION.md`). Before a line of app code runs, a set of identifiers has to exist, and **most of them are immutable after first submission**:

- An iOS **bundle identifier** cannot be changed once an app record exists in App Store Connect. Changing it means a new app record, a new App Store URL, and every existing install stranded on a build that will never update.
- An Android **applicationId** cannot be changed after the first Play upload, for the same reason.
- The **upload/signing keystore** cannot be rotated without Google Play App Signing already enrolled.
- The Apple **Sign in with Apple** private key and the Google OAuth **web client id** are baked into backend config and into the app binary respectively; changing either invalidates every issued token.

Separately, three developer accounts have to be opened, and **all three must be opened by a legal entity, not a person**:

| Account | Why the entity matters |
|---|---|
| Apple Developer Program | An Individual account publishes under the person's legal name and cannot use a trade name. Migrating Individual → Organization later requires a new membership and Apple's manual transfer of the app record. |
| Google Play Console | Since 2023 Google verifies developer identity and publishes the developer's name and address on the listing. A personal account publishes a home address. |
| Stripe (already exists) | Unchanged — the platform account is already live. |

**The company does not exist yet.** No accounts can be opened, no App IDs registered, nothing submitted.

## Decision

**Split the work in two.** Lock every decision that is free to make now and expensive to change later; defer only the account creation itself.

### 1. Identifiers — decided now, recorded here, treated as immutable

| Thing | Value | Notes |
|---|---|---|
| iOS bundle identifier | `wtf.imin.fan` | Reverse-DNS of the owned domain `imin.wtf`. `fan` (not `app`) leaves room for a future organizer app at `wtf.imin.organizer` and a gate app at `wtf.imin.gate` without a naming collision. |
| Android applicationId | `wtf.imin.fan` | Deliberately identical to the bundle id. One string in `app.config.ts`, one mental model. |
| App name (stores) | `imin` | Lowercase, matching the brand everywhere else. |
| App subtitle / short description | `Nightlife tickets` | 30 chars max on iOS. |
| URL scheme (custom) | `imin://` | Fallback for OAuth returns and Stripe redirects where a universal link cannot be used. |
| Universal-link host | `app.imin.wtf` | Already the buyer origin; already serves `.well-known`. |
| Expo project slug | `imin-fan` | |
| Repo | `imin-fan-app` | Fifth repo in the workspace, sibling of the other four. |

### 2. Accounts — blocked, but fully specified

When the company exists, this is a checklist, not a research task. Order matters: Apple enrolment has the longest lead time (D-U-N-S verification can take **1–2 weeks**), so it starts first.

| # | Step | Cost | Lead time | Blocks |
|---|---|---|---|---|
| 1 | Obtain a **D-U-N-S number** for the entity | free | 1–5 business days | Apple enrolment |
| 2 | **Apple Developer Program**, Organization type | $99/yr | 1–2 weeks after D-U-N-S | everything Apple |
| 3 | Register the **App ID** `wtf.imin.fan` with the *Sign in with Apple* and *Push Notifications* capabilities | — | minutes | `APPLE_OAUTH_NATIVE_AUDIENCE`, push |
| 4 | Create the **Sign in with Apple key** (`.p8`), record Key ID + Team ID | — | minutes | native Apple sign-in |
| 5 | Create the **APNs key** (`.p8`) — one key serves all apps on the team | — | minutes | iOS push |
| 6 | Create a **Pass Type ID** + certificate | — | minutes | Apple Wallet passes (separate plan) |
| 7 | **Google Play Console**, organization account | $25 once | days (identity verification) | everything Play |
| 8 | **Google Cloud OAuth clients**: one Web, one iOS, one Android | free | minutes | native Google sign-in |
| 9 | **Firebase project** + Android app, download `google-services.json` | free | minutes | Android push via FCM |
| 10 | **Expo organization** + EAS project, record `projectId` | free tier | minutes | EAS build/submit/update |

Apple Wallet (steps 3, 6) belongs to the separate Wallet-passes plan but is listed here because it shares the same Apple account and should be provisioned in the same sitting.

### 3. The three values the backend needs

Phase 0 has config gates that stay closed until these arrive. All three default to blank, and blank means the feature reports itself unavailable rather than half-working:

| Env var | Value | Consequence while unset |
|---|---|---|
| `GOOGLE_OAUTH_NATIVE_AUDIENCE` | the **Web** OAuth client id — this is what *both* native apps pass as `serverClientId`, so one audience covers iOS and Android | **Nothing — the endpoint is already live.** `getNativeAudience()` falls back to `client-id`, which is set in production, so `nativeEnabled()` is already true and `POST /buyer/auth/google/native` verifies tokens against the web client id today. That is the correct value in the normal setup; the override only matters if the apps are ever pointed at a different Google project. Verified against production 2026-08-15. |
| `APPLE_OAUTH_NATIVE_AUDIENCE` | the **bundle id** `wtf.imin.fan` — *not* the web Services ID in `apple.client-id`, which is a different value for the organizer web flow | `AppleNativeIdentityService.enabled()` is false ⇒ `POST /buyer/auth/apple/native` answers 404. **This one also blocks App Store review**: Guideline 4.8 requires Sign in with Apple wherever Google sign-in is offered |
| `EXPO_ACCESS_TOKEN` | from the Expo org, if enhanced security is enabled on the project | push sends unauthenticated, which Expo permits by default |

The audience distinction in the first two rows is the single most common way to get this wrong, and it fails as an opaque `aud` mismatch at verification time rather than as a configuration error.

## Consequences

**What proceeds now, unblocked.** All of Phase 0 (`docs/superpowers/plans/2026-08-15-mobile-phase0-backend.md`) — it is pure backend and touches no store account. The app repo scaffold, the design-token pipeline, the ported domain logic, and the whole UI can be built and run in the iOS Simulator and an Android emulator with no paid account. Jest and Maestro run locally. Only three things actually need the accounts: a signed device build, push on a physical iOS device, and submission.

**What is deliberately NOT done now.** No account is opened in a personal name "to get started". An Individual Apple account publishes under the person's legal name and cannot be renamed; migrating to an Organization afterwards means a new membership and a manual app-record transfer through Apple support. The temptation is real and the cost is a permanently wrong developer name on the listing.

**What could still go wrong.** `wtf.imin.fan` could be squatted on Play between now and registration — applicationIds are globally unique and first-come. The exposure is small (nobody is targeting this name) and the mitigation is to register the Play account before publicising the app.

**Revisit if:** the entity ends up in a jurisdiction where Apple requires a local business registration document beyond D-U-N-S, or if a decision is made to ship Android first (which would reorder the table above but change none of the values).
