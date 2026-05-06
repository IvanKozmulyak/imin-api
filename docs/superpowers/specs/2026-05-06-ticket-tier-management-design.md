# Ticket-tier management API

**Status:** Draft
**Date:** 2026-05-06

## Goal

Add organizer-facing CRUD for `ticket_tiers`. Two consumer shapes ship together:

1. **Nested REST per tier** — `POST/PATCH/DELETE /api/v1/events/{eventId}/tiers[/{tierId}]`. For UI flows that edit one tier at a time.
2. **Embedded in event patch** — extend `PATCH /api/v1/events/{eventId}` to accept a `tiers[]` list that creates and updates tiers alongside event-level fields. **Merge-only** semantics — never deletes.

Both paths run through the same domain-level service so behavior, validation, and sales-protection rules are identical.

A non-goal: bulk-replace semantics on the embed path, separate "drag-to-reorder" endpoint, tier templates, or any read endpoint beyond what `EventController.detail` already returns. Reorder is just `sortOrder` updates via the existing endpoints.

## Background

`ticket_tiers` schema (V6, see `model/TicketTier.java`):

- `id` UUID, `event_id` UUID FK, `name` (≤128), `kind` (enum: `EARLY_BIRD / STANDARD / LATE_BIRD / CUSTOM`), `price_minor` (int), `quantity` (int), `sold` (int default 0), `sale_closes_at` (nullable timestamp), `enabled` (bool default true), `sort_order` (int default 0).

Today there is **no controller and no domain service** for tiers. `TicketTierRepository` exposes `findByEventIdOrderBySortOrderAsc` (operator) and the public-event branch added `findByEventIdAndEnabledTrueOrderBySortOrderAsc`. The only mutation path is direct JPA via Spring Data REST, which is disabled for tiers (`@RepositoryRestResource(exported = false)`).

`EventController` has `PATCH /{id}` accepting an `EventPatchRequest` body and an `If-Match` header. `EventService.patch(...)` ownership-checks via `loadOwned(principal, id)`, applies field-level merge, and bumps `updatedAt` to invalidate the ETag. Tier mutation in this spec follows the same ownership/auth pattern.

## Decisions taken during brainstorming

For traceability:

1. **Endpoint shape:** nested REST under the event (`/api/v1/events/{eventId}/tiers/...`).
2. **Bulk path semantics:** merge-only — `EventPatchRequest.tiers` only creates and updates. Deletion exclusively through `DELETE /tiers/{tierId}`.
3. **Sales protection (when `sold > 0`):**
   - Cannot delete.
   - Cannot reduce `quantity` below `sold`.
   - Cannot change `priceMinor`.
   - `name`, `enabled`, `saleClosesAt`, `sortOrder` remain editable.
   - `kind` is also locked once sold (changing kind retroactively reframes purchases).

## API surface

All endpoints live under `/api/v1/events/{eventId}/tiers` and require an authenticated session whose `AuthPrincipal` resolves to a member of the event's org (same `loadOwned` rule as `EventController`). All return the existing `TicketTierDto` (operator-facing, includes raw `quantity` and `sold`). Wire the controller into a new `controller/event/EventTierController` so the existing event-package grouping stays consistent.

### `POST /api/v1/events/{eventId}/tiers`

- **Body** (`TicketTierCreateRequest` — new DTO):
  - `name` (required, non-blank, ≤128).
  - `kind` (required; wire value from `TicketTierKind`).
  - `priceMinor` (required; ≥ 0).
  - `quantity` (required; > 0).
  - `saleClosesAt` (optional; if set, must be in the future and ≤ `event.endsAt` if both present).
  - `sortOrder` (optional; defaults to `max(existing) + 1`).
  - `enabled` (optional; defaults to `true`).
- **Response:** 201 with `TicketTierDto`. `Location: /api/v1/events/{eventId}/tiers/{tierId}`.
- **Errors:**
  - 404 `NOT_FOUND` if the event is not in the principal's org or doesn't exist (do not leak existence).
  - 400 `INVALID_REQUEST` with field-level details if validation fails.

### `PATCH /api/v1/events/{eventId}/tiers/{tierId}`

- **Body** (`TicketTierPatchRequest` — new DTO; all fields nullable):
  - `name`, `kind`, `priceMinor`, `quantity`, `saleClosesAt`, `sortOrder`, `enabled`.
  - Null = leave unchanged. To clear `saleClosesAt`, send a sentinel — see "Nullable field clear" below.
- **Response:** 200 with the updated `TicketTierDto`.
- **Errors:**
  - 404 `NOT_FOUND` if event not in org, or tier not under event.
  - 400 `INVALID_REQUEST` for validation failures.
  - 409 `INVALID_STATE` (with descriptive `fields` map) for sales-protected violations:
    - `priceMinor` change with `sold > 0`.
    - `quantity` reduction below `sold`.
    - `kind` change with `sold > 0`.

### `DELETE /api/v1/events/{eventId}/tiers/{tierId}`

- **Response:** 204 No Content.
- **Errors:**
  - 404 `NOT_FOUND` if event/tier missing or out of org.
  - 409 `INVALID_STATE` if `sold > 0` (cannot delete sold tiers).

### Embed path: `PATCH /api/v1/events/{eventId}`

`EventPatchRequest` gains one optional field:

```java
public record EventPatchRequest(
        // ... existing fields ...
        List<TicketTierEmbeddedPatch> tiers
) {}
```

`TicketTierEmbeddedPatch` is a tiny new DTO with `id` (nullable), `name`, `kind`, `priceMinor`, `quantity`, `saleClosesAt`, `sortOrder`, `enabled` — all nullable.

Reconciliation rules in `EventService.patch`:
- For each entry where `id == null`: treated as create. Required fields (`name`, `kind`, `priceMinor`, `quantity`) must be present, otherwise 400.
- For each entry where `id != null`: treated as update. The tier must belong to this event, otherwise 400 `INVALID_REQUEST` (referencing an unrelated tier id is a client bug, not a 404 — the event itself was found).
- Tiers in the database that are NOT mentioned in the payload are **untouched** (merge-only).
- Sales-protection rules apply identically.
- All-or-nothing: a single transaction wraps the event field updates + every tier change. If any tier validation fails, the whole patch rolls back.

The embed path still respects the event-level `If-Match` header (existing behavior). Tier mutations contribute to `event.updatedAt` bumping — even if the only thing changed is a tier, the event's ETag advances so the cache layer / GET re-renders.

### Nullable field clear

`saleClosesAt` is nullable. There is no existing clear-to-null pattern in the codebase, so this spec introduces one: an explicit `clearSaleClosesAt: true` boolean on `TicketTierPatchRequest` and `TicketTierEmbeddedPatch`. Behavior:

- `saleClosesAt: <Instant>` and `clearSaleClosesAt` absent/false → set to that instant.
- `saleClosesAt: null` (or absent) and `clearSaleClosesAt` absent/false → leave unchanged.
- `clearSaleClosesAt: true` → set to null. If `saleClosesAt` is also non-null in the same request, return 400 `INVALID_REQUEST` ("contradictory clear flag").

Validator enforces the contradiction check.

## Architecture

### New service: `service/event/TicketTierService`

```java
@Service
public class TicketTierService {
    @Transactional
    TicketTierDto create(AuthPrincipal p, UUID eventId, TicketTierCreateRequest req);

    @Transactional
    TicketTierDto patch(AuthPrincipal p, UUID eventId, UUID tierId, TicketTierPatchRequest req);

    @Transactional
    void delete(AuthPrincipal p, UUID eventId, UUID tierId);

    /** Used by EventService.patch for the embed path. */
    @Transactional
    List<TicketTier> reconcileEmbedded(Event event, List<TicketTierEmbeddedPatch> patches);
}
```

The shared validation + sales-protection logic lives in a `TicketTierValidator` (new), mirroring `EventValidator`. Both the nested-REST methods and `reconcileEmbedded` delegate to the validator so the rules can't drift.

### `EventService.patch` changes

- After applying field-level merge to the `Event` entity, call `tierService.reconcileEmbedded(event, body.tiers())` if `body.tiers() != null`.
- The whole method remains within a single `@Transactional` boundary (existing behavior). The reconcile call participates in that transaction.
- `event.setUpdatedAt(Instant.now())` is already bumped by the existing patch logic — no change there.

### New controller: `controller/event/EventTierController`

Sibling to `EventController` and `EventMediaController`. Three handlers (POST, PATCH, DELETE) delegating to `TicketTierService`. Authorization is enforced by `TicketTierService.loadOwnedTier(...)` which mirrors `EventService.loadOwned` — load the event in the principal's org, then load the tier under that event, then 404 on either miss.

### DTOs (new)

All under `dto/event/`:

- `TicketTierCreateRequest` — required fields enforced via validation logic in `TicketTierValidator`, not Bean Validation annotations (matches the existing `EventPatchRequest` style — server-side `Map<String, String>` field-error payload).
- `TicketTierPatchRequest` — all fields nullable, plus `clearSaleClosesAt` flag if needed.
- `TicketTierEmbeddedPatch` — slim shape used inside `EventPatchRequest.tiers`. Has `id` (nullable) plus the same nullable fields as the patch request.

### Validation rules — single source of truth in `TicketTierValidator`

Each rule below is enforced both on the nested-REST path and the embed path:

| Field | Create | Update | Sales-protected (sold > 0) |
|---|---|---|---|
| `name` | required, non-blank, ≤128 | non-blank, ≤128 | editable |
| `kind` | required, valid enum | valid enum | **locked** |
| `priceMinor` | required, ≥ 0 | ≥ 0 | **locked** |
| `quantity` | required, > 0 | > 0 | must be ≥ `sold` |
| `saleClosesAt` | optional; if set, > now AND ≤ `event.endsAt` (if event.endsAt set) | same | editable |
| `sortOrder` | optional, ≥ 0 | ≥ 0 | editable |
| `enabled` | optional | optional | editable |

A violated sales-protection rule returns 409 with `code: INVALID_STATE` and a `fields` map naming each locked field that was attempted (`{"priceMinor": "locked: tier has sold tickets"}`). 409 + `INVALID_STATE` (not 400 + `INVALID_REQUEST`) because the request was syntactically valid; the conflict is with current sales state. `INVALID_STATE` already exists in `ErrorCode`.

### Caching / cache-control

This branch does not emit `Cache-Tag` headers. The `EventController.PATCH` ETag mechanism continues to work as before. Tier endpoints return JSON bodies with default no-store from Spring (operator-facing, authenticated — not a public cache concern).

When the public event endpoint exists (sister branch `feat/public-event-endpoint`) is merged, organizer-driven tier changes will be visible to public consumers within the public branch's `s-maxage=60` window. No cross-branch coordination needed for this spec.

### Error handling

| Condition | Response |
|---|---|
| Auth missing/invalid | 401 (existing filter) |
| Principal in wrong org | 404 `NOT_FOUND` (no leak) |
| Tier not under event | 404 `NOT_FOUND` |
| Validation failure | 400 `INVALID_REQUEST` + `fields` map |
| Sales-protection violation | 409 `INVALID_STATE` + `fields` map naming locked fields |
| Soft-deleted event | 404 (existing `loadOwned` already filters) |

## Testing

Three layers, mirrored after Phase 2/3 of the public-event-endpoint branch:

### 1. `TicketTierValidatorTest`

Pure unit, no Spring. One test per validation rule. Cover:
- All required-field omissions on create.
- All field-level constraints (length, range, enum).
- All sales-protected violations.
- Sales-protected non-violations (allowed edits when sold > 0).
- `saleClosesAt` window: in past → invalid; after `event.endsAt` → invalid; null → ok.

### 2. `TicketTierServiceTest`

`@DataJpaTest` slice with `@Import(TicketTierService.class)` (mirrors `PublicEventServiceTest`). Cover:
- Happy create / patch / delete.
- Owner check: principal from a different org gets 404.
- Tier-not-under-event: 404.
- Sales-protection blocks delete / quantity-below-sold / priceMinor change.
- `reconcileEmbedded`: creates new tier (id=null), updates existing (id matches), leaves unmentioned tier alone.
- `reconcileEmbedded` with id pointing to a different event: 400.

### 3. `EventTierControllerTest`

`@SpringBootTest @AutoConfigureMockMvc` (mirrors `EventControllerTest`) with `@MockitoBean TicketTierService`. Cover:
- 201 + Location header on create.
- 200 + body on patch.
- 204 on delete.
- 404 on missing event/tier.
- 400 on validation error (verify `fields` map shape).
- 409 on sales-protection violation.
- All endpoints reject unauthenticated requests with 401.

Plus extend `EventControllerTest` (existing) with one new test: `patch_withTiersList_reconciles` — verifies the embed path round-trips into `EventService.patch → tierService.reconcileEmbedded`.

## Out of scope

- **Bulk-delete endpoint** (`DELETE /tiers` with a list of ids). Use individual DELETEs.
- **Reorder endpoint** (`POST /tiers/reorder` with id-order). Use PATCH with `sortOrder`.
- **Tier templates / clone-from-event.**
- **History / audit log of tier mutations.**
- **Public read** beyond what the public-event-endpoint branch already exposes.
- **Capacity rollup** at the event level (sum of tier quantities). The existing `events.capacity` column is unchanged by this spec — its semantics (whether it's organizer-set vs auto-derived) is a separate concern.
- **Concurrent edit conflict on tiers.** Tier endpoints don't take `If-Match`. Two organizers editing the same tier simultaneously: last write wins. Acceptable because the operator UI typically has one editor per event at a time, and the data is recoverable from the audit log if added later.

## Migration order

No schema migration. Implementation order:

1. New DTOs (`TicketTierCreateRequest`, `TicketTierPatchRequest`, `TicketTierEmbeddedPatch`).
2. `TicketTierValidator` + `TicketTierValidatorTest`.
3. `TicketTierService` + `TicketTierServiceTest`.
4. `EventTierController` + `EventTierControllerTest`.
5. Extend `EventPatchRequest` with `tiers` field; wire `EventService.patch` to call `tierService.reconcileEmbedded`; extend `EventControllerTest`.
6. Smoke: `./mvnw test` clean.

Steps 1–4 are independent of step 5 (and of each other to a smaller degree), so steps 1, 2, 3, 4 can parallelize as a "tier-only" phase, with step 5 (event patch wiring) as a sequential follow-up.

## Risks / open questions

- **`saleClosesAt` clear-to-null introduces a new codebase pattern** (the explicit `clearSaleClosesAt` boolean). If we end up with more nullable fields needing clear semantics, we should generalize this — but with one such field today, the pattern is fine.
- **Race between `existsBy` and `save` of identical-name tiers.** Tier names aren't unique-constrained at the DB level today (V6 has no UNIQUE on `(event_id, name)`). Two simultaneous create requests with the same name would both succeed. If we want uniqueness, that's a follow-up migration. This spec does not add one.
- **`updatedAt` race for the ETag.** Two simultaneous tier writes against the same event could both bump `event.updatedAt` to nearly-identical timestamps; clients with stale ETags would 412 the second one. That is the desired behavior — losing the second write would be worse.
