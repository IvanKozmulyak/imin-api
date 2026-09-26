# M1-3 Consent columns and fan_features schema

Slug: `ap-m1-3-consent-schema` · Programme: `docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (workspace root), task M1-3.

## Goal and scope

Add the consent inputs and the per-membership feature table the audience plan tool needs, without any behaviour change for sending:

- `memberships.objected_profiling` (boolean, default false). No tracking-consent columns (D3).
- `consent_records.text_version` (VARCHAR(32)) and `consent_records.order_id` (UUID, no FK).
- `fan_features` table, 1:1 with `memberships`, index `(org_id, class)`.
- `ConsentService.capture` overload carrying `textVersion` + `orderId`; existing overloads pass null.
- `AudienceOrderProjector` checkout capture writes the order id into the new column (`textVersion` stays null until M3-P1). The proof text keeps the order id as before.
- DSAR: `executeErase` deletes the `fan_features` row; the Art.15 export (`DsarRecords`) carries it as `fanFeatures`.

Out of scope: computing features (M1-4/M1-5), setting `objected_profiling` (M1-13), reading `text_version` (M1-6). No beta gate or new flag (no config key in this task).
Reproduction test: n-a (new code).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `imin-api/.claude/worktrees/ap-m1-3-consent-schema` | `./mvnw test` |

## Affected files (per repo)

api:
- new `src/main/resources/db/migration/V132__audienceplan_consent_columns.sql` (renumbered at rebase if taken)
- new `src/main/resources/db/migration/V133__fan_features.sql`
- new `audienceplan/model/FanFeature.java`, `audienceplan/repository/FanFeatureRepository.java` (`exported = false`)
- `audience/model/Membership.java` (`objectedProfiling`), `audience/model/ConsentRecord.java` (`textVersion`, `orderId`)
- `audience/service/ConsentService.java` (9-arg overload), `audience/service/AudienceOrderProjector.java`
- `audience/service/DsarService.java` (erase + export), `audience/service/DsarScopeService.java`, `audience/dto/DsarRecords.java` (`fanFeatures`, `FanFeatureRecord`)
- tests: `audienceplan/AudiencePlanSchemaTest.java` (new), `audience/AudienceDsarTest.java`, `audience/AudiencePostgresTest.java`, `marketing/MarketingOptInWriteTest.java`

## Ordered steps

1. Migrations (H2/PG-compatible: no jsonb, no enum, no unnamed CHECK).
2. Entity, repository, model fields.
3. `ConsentService` overload; projector passes `orderIdForProof`.
4. DSAR erase + export.
5. Tests, then `./mvnw test`.

## Verification commands

`./mvnw test` (from the worktree root). Docker available, so `AudiencePostgresTest` runs against Postgres 17.

## Test impact

One test per branch:
- H2 boot applies V132/V133: `fan_features` round-trip of every column; new membership has `objected_profiling=false` and `true` persists.
- `capture` with version + order → both persisted; `capture` via the old overloads → both null.
- Checkout capture (`MarketingOptInWriteTest`) → `order_id` = the order id, `text_version` null.
- `executeErase` → `fan_features` row gone and `deleteByMembershipId` called (explicit delete, not only the FK cascade).
- `exportRecords` → `fanFeatures` populated when a row exists, null when none.
- Postgres: same schema round-trip + new consent columns.

## Live-test

Not needed: schema and write-path only; no endpoint behaviour changes beyond an additive DSAR export field, which the tests cover.

## Contract impact

`/api/v1` additive: `MemberDto.dsarRecords` (`POST /api/v1/audience/members/{id}/export`) gains `fanFeatures` (`FanFeatureRecord`, nullable). Marker: `FanFeatureRecord`. The webapp needs `api:sync` after the api is live (its `api:check` drifts otherwise); no webapp UI change.

## i18n impact

None.

## Blast radius

`Membership`, `ConsentRecord`, `ConsentService`, `AudienceOrderProjector`, `DsarService`, `DsarScopeService`, `DsarRecords`. Migrations only add defaulted or nullable columns and a new table; no existing row is rewritten.

## Risks

- Flyway number collision with concurrently shipping tasks → take the next free number after rebasing on origin/master.
- `fan_features.logic_version` is NOT NULL without default: M1-5 must always set it (deliberate, a row without a logic version is meaningless).

## Definition of done

`./mvnw test` green on the rebased worktree; migrations use unique free numbers; report lists the contract marker.

## Live-test evidence

n-a (see Live-test).

## Review rounds

(appended by the orchestrator)
