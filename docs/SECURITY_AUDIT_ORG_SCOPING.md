# Security audit — organizer-endpoint org-tenancy scoping

**Date:** 2026-05-18
**Scope:** every `@RestController` whose path starts with `/api/v1/…` and is **not** under `/public/` or `/auth/`. Goal: prove every authenticated mutation or read is scoped to `principal.orgId()` and that any cross-org probe returns a leak-safe `404 NOT_FOUND` (never 403, never an empty 200, never a message that reveals existence).

The canonical pattern is the one used by `EventService.loadOwned`:

```java
Event e = events.findActive(id).orElseThrow(() -> ApiException.notFound("Event"));
if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
```

— same message ("Event not found") whether the row is missing or belongs to another org.

## Endpoint-by-endpoint verdict

| Controller | Endpoints | Scoping mechanism | Verdict |
|---|---|---|---|
| `EventController` (`/api/v1/events`) | `GET /` (list), `POST /`, `GET /{id}`, `PATCH /{id}`, `POST /{id}/publish`, `POST /{id}/unpublish`, `GET /{id}/overview` | List uses `events.findVisibleByOrg(p.orgId(), …)` (repo-level org filter). Single-id reads/writes funnel through `EventService.loadOwned` (or `EventOverviewService.overview`, same pattern). Create stamps `e.setOrgId(p.orgId())` before persist. | **OK** |
| `EventTierController` (`/api/v1/events/{eventId}/tiers`) | `POST /`, `PATCH /{tierId}`, `DELETE /{tierId}` | `TicketTierService.loadOwnedEvent` checks the parent event, then `loadOwnedTier` uses `findByIdAndEventId(tierId, eventId)` and throws `notFound("Event")` (not "Tier") so cross-event probes can't even distinguish a wrong-event tier from a missing one. | **OK** |
| `EventPromoCodeController` (`/api/v1/events/{eventId}/promos`) | `POST /`, `PATCH /{promoId}`, `DELETE /{promoId}` | `PromoCodeService.loadOwnedEvent` (same gate as above) + `loadOwnedPromo` via `findByIdAndEventId`. Note the leak-safe 404 message here is `"Promo code not found"` for a wrong promo id under the *correct* event — i.e. the gate at the event level still produces "Event not found" for cross-org probes, which is the case this audit cares about. | **OK** |
| `EventMediaController` (`/api/v1/events/{eventId}/media/{kind}`) | `POST /{kind}` (multipart), `DELETE /{kind}` | `MediaUploadService.loadOwned` — identical pattern. | **OK** |
| `OrgController` (`/api/v1/org`) | `GET /`, `PATCH /`, `DELETE /` | The endpoint operates purely on `p.orgId()` — there is no `{orgId}` path variable. `OrgService` calls `orgs.findById(p.orgId())`. Cross-org access is structurally impossible because the org id is never read from the request. | **OK** (no surface for cross-org probe) |
| `TeamController` (`/api/v1/org/team`) | `GET /` (list), `POST /invite`, `DELETE /{userId}` | List uses `users.findByOrgIdOrderByCreatedAtAsc(p.orgId())`. Invite stamps `u.setOrgId(p.orgId())`. Remove loads by id then verifies `target.getOrgId().equals(p.orgId())` and throws `notFound("User")` on mismatch — leak-safe. | **OK** |
| `OrgAuditController` (`/api/v1/org/audit`) | `GET /` | Repository query `auditLogs.findByOrgIdOrderByOccurredAtDesc(p.orgId(), …)`. Org id never comes from the request. | **OK** |
| `DashboardController` (`/api/v1/dashboard`) | `GET /` | `DashboardService.build` only queries against `p.orgId()`. No request-supplied id. | **OK** |
| `MeController` (`/api/v1/me/notifications`) | `GET /notifications`, `PATCH /notifications` | Per-user, not per-org. Operates on `p.userId()`. Not in scope for org-tenancy, but defence-in-depth is fine (the user id is from the principal, never the request). | **OK** |
| `NotificationController` (`/api/v1/notifications/unread-count`) | `GET /unread-count` | Per-user (`p.userId()`). Same note as above. | **OK** |
| `ConceptController` (`/api/v1/ai/events/concept`, `…/regenerate`) | `POST /concept`, `POST /concept/regenerate` | New concepts stamp `g.setOrgId(p.orgId())`. Regenerate loads via `repo.findByIdAndOrgId(conceptId, p.orgId())` and throws `ApiException.notFound("Concept")` on miss — leak-safe. | **OK** |
| `StripeConnectController` (`/api/v1/orgs/{orgId}/stripe/…`) | `POST /connect`, `POST /account-session`, `POST /onboarding-link`, `GET /status` | **Has a path `{orgId}` — strong smell.** All four handlers delegate to `StripeConnectService`, which gates every method through `loadOwnedOrg(p, orgId)`:<br>`if (!orgId.equals(p.orgId())) throw ApiException.notFound("Organization");`<br>The path id is compared to `p.orgId()` *before* any DB or Stripe call. Cross-org probes return `404 NOT_FOUND` with message `"Organization not found"`. | **OK** |
| `EventCreatorController` (`POST /api/v1/events/ai-create`) | `POST /ai-create` | Permit-all (legacy poster pipeline, see `SecurityConfig`). Out of scope — no auth, no per-org data persisted in a way that another org can read. | **N/A (public)** |
| `EventContentController` (`POST /api/v1/events/ai-content`) | `POST /ai-content` | Same — permit-all legacy AI helper. | **N/A (public)** |
| `StyleReferenceController` (`/api/v1/posters/style-references…`) | listing + image | Permit-all reference assets. | **N/A (public)** |
| `ReferenceController` (`/api/v1/reference/countries`) | `GET /countries` | Permit-all static reference data. | **N/A (public)** |
| `StripeCheckoutController` (`/api/v1/public/events/{eventId}/checkout`) | — | Under `/public/`, out of audit scope. | **N/A (public)** |
| `StripeWebhookController` (`/api/v1/stripe/webhook`) | — | Stripe-signed inbound, permit-all path, no auth principal. | **N/A (webhook)** |
| `AuthController` (`/api/v1/auth/…`) | — | Under `/auth/`, out of audit scope. | **N/A (auth)** |

## Findings

**No gaps found.** Every organizer-facing controller path that operates on per-org state either:

1. Derives the org id from the authenticated principal only (no path variable to spoof — `OrgController`, `TeamController`, `OrgAuditController`, `DashboardController`, list endpoints on `EventController`), **or**
2. Loads by id and immediately compares `entity.getOrgId().equals(p.orgId())` (or routes through a repo method like `findByIdAndOrgId`) and throws `ApiException.notFound(<resource>)` on mismatch — same message as a true 404.

Notable strengths:

- `TicketTierService.loadOwnedTier` deliberately throws `notFound("Event")` (not "Tier") so a tier id existing under a different event of a different org reads identically to a tier id that simply doesn't exist. The comment in the code explicitly calls this out.
- `StripeConnectService.loadOwnedOrg` checks `!orgId.equals(p.orgId())` **before** the DB call, so an attacker can't even distinguish "this org id doesn't exist" from "this org id exists but isn't yours" by timing.
- The `loadOwned` / `loadOwnedEvent` pattern is replicated identically across `EventService`, `EventOverviewService`, `TicketTierService`, `PromoCodeService`, `MediaUploadService` — a consistent shape that's easy to keep correct as new code is added.
- `EventRepository.findVisibleByOrg`, `UserRepository.findByOrgIdOrderByCreatedAtAsc`, `AuditLogRepository.findByOrgIdOrderByOccurredAtDesc`, and `GeneratedEventRepository.findByIdAndOrgId` push the filter into the repo layer — services physically cannot widen the query.

One small observation, **not a security gap**: `OrgService.delete` checks `p.role() == UserRole.OWNER` and throws `ApiException.forbidden(...)` (403) for non-owners. That's correct authorization semantics (not org-tenancy) — a non-owner *is* the right person to receive a 403 because the org id is still theirs. The cross-org case never reaches this branch because the org id is taken from the principal.

## Recommendations

None. The codebase already enforces the canonical pattern uniformly. Two suggestions to keep things tight as new endpoints are added:

1. **Add a checkstyle / archunit rule** that flags any new controller method whose signature has `UUID orgId` as a path variable but whose service implementation doesn't call a method named `loadOwnedOrg` or equivalent in the first few lines. Currently `StripeConnectController` is the only path-id controller in this category and it's correct, but the pattern is fragile to copy-paste.
2. **Add `CrossOrgScopingTest`** (delivered alongside this report at `src/test/java/com/imin/iminapi/security/CrossOrgScopingTest.java`) to the CI gate. It seeds two orgs and asserts that every organizer endpoint returns 404 (`code == "NOT_FOUND"`) when org A tries to touch org B's data, with no leaky phrases ("forbidden", "wrong organization", "permission") in the message.
