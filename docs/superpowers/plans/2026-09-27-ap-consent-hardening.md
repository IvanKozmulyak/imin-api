# ap-consent-hardening

Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md`, "Programme audit #2 2026-09-27 — actions", first bullet (three sub-items). Mode: autonomous runner. Reproduction test: n-a (hardening of shipped behaviour; every new branch is test-first).

## Goal and scope

1. **Door QR and survey sign-ups wait for confirmation.** A `door_qr` / `survey` consent is still recorded (proof, text version, event, counts), but it is marked as needing confirmation and does not make the address mailable for `SendGateService` (manual and Momentum campaigns) nor `ConsentGate` (plan, and all campaigns behind the flag) until it is confirmed. A member already mailable through another basis stays mailable. The confirmation email/flow is NOT built (pre-`sends-enabled` card); the state is shaped so that a confirm flow only sets `consent_records.confirmed_at` and applies the membership state.
2. **`MomentumPlanTarget.best()` is empty unless `imin.audience-plan.sends-enabled` is true**, so Momentum falls back to its default Repeat target while audience sends are off.
3. **Momentum plan snapshots carry `segments.origin='momentum'`**: hidden from `listSegments`, delete refused with 404 (like plan arms), and `SendPathGuard` keys the Momentum ConsentGate check on that origin. The old `prebuilt_key='MOMENTUM_PLAN'` marker is dropped (one marker, not two); existing rows are migrated.

Out of scope: confirmation email and endpoint, consent-trail/export columns for the pending state (no contract change), webapp copy.

### State design

`consent_records` + `confirmation_required BOOLEAN NOT NULL DEFAULT FALSE`, `confirmed_at TIMESTAMP WITH TIME ZONE NULL`. A record is *awaiting confirmation* iff `confirmation_required AND confirmed_at IS NULL`.
- `ConsentService.capture` sets `confirmation_required` itself from the source (`door_qr`, `survey`), so no writer can forget it. An awaiting record is appended but does not touch the membership's denormalized `consent_status` / `consent_basis` (what SendGate reads) and does not lift `objected_profiling` (an unconfirmed address typed at a door is not proof the person acted). `ConsentChanged` is still published.
- `ConsentGateSql` ranks only records not awaiting confirmation (latest-consent head and the legacy-import-only test), so an existing proven basis keeps deciding; a member with only a pending sign-up reads `no_basis`.
- `FanFeatureCalculator.lastContactFromPerson` ignores awaiting records (an unconfirmed sign-up must not extend the 3-year window).
- Later confirm flow: set `confirmed_at`, then set the membership to `subscribed` / `explicit` and clear the objection (the capture path's deferred steps).

Prod safety (M4-3/M4-4 shipped hours before): the migration flags every existing `door_qr` / `survey` record `confirmation_required = TRUE`, and restores the membership state of every member whose latest email record is such a pending sign-up to the latest other email record's status/basis (`never` / NULL when none).

Rolling deploy: the previous release keeps serving until it drains and writes door/survey records with the column default (`FALSE`). `ConsentConfirmationReconciler` re-applies the same two statements to those rows only (flag each unflagged, unconfirmed door/survey record; restore only the members of the rows it flagged) on `ApplicationReadyEvent` and every 15 min (ShedLock `consent_confirmation_reconcile`, failures only logged). Idempotent: once nothing is unflagged it changes nothing. V155 is unchanged; the SQL lives only in the reconciler.

Prod check after deploy (expect 0; the periodic pass should clear any non-zero within 15 min):
`select count(*) from consent_records where source in ('door_qr','survey') and confirmation_required = false`

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-consent-hardening` | `./mvnw test` (full run via `/Users/ivan/.imin-pipeline/mvnlock.sh test`) |

## Affected files (per repo)

api:
- new `src/main/resources/db/migration/V155__consent_confirmation_and_momentum_segments.sql`
- `audience/model/ConsentRecord.java` (+ `confirmationRequired`, `confirmedAt`, `isAwaitingConfirmation()`)
- new `audience/service/ConsentConfirmation.java` (sources needing confirmation)
- `audience/service/ConsentService.java` (capture: flag + skip membership state while awaiting)
- `audienceplan/repository/ConsentGateSql.java` (awaiting records never rank)
- `audienceplan/engine/FanFeatureCalculator.java` (awaiting records are not contact)
- `audienceplan/engine/ClassRules.java` (a pending sign-up never hides an import basis)
- new `audience/service/ConsentConfirmationReconciler.java` (rolling-deploy backstop, see Prod safety)
- `audience/model/Segment.java` (+ `ORIGIN_MOMENTUM`, `isSystemOrigin()`)
- `audience/service/SegmentService.java` (list/delete treat momentum like plan arms)
- `audienceplan/service/MomentumPlanTarget.java` (sends-enabled gate; snapshot writes origin, drops `SNAPSHOT_KEY`)
- `audienceplan/service/SendPathGuard.java` (key on origin)
- tests: `DoorOptInServiceTest`, `SurveyServiceTest`, `ConsentGateScenarios`, `RetentionJobScenarios`, `FanFeatureCalculatorTest`, `MomentumPlanTargetTest`, `marketing/MomentumEvaluatorTest` (plan-target test turns sends on; new sends-off case), `SendPathGuardTest`, `AudienceSegmentTest`, new `migration/ConsentConfirmationMigrationScenarios` + H2/Postgres subclasses, new `audience/service/ConsentConfirmationReconcilerTest`.

## Ordered steps

1. Tests first: door sign-up alone → SendGate excludes `no_lawful_basis`, ConsentGate `no_basis`, membership `never`/NULL, record flagged awaiting; same for survey; door sign-up of a member with a proven checkout consent → both gates still admit (SendGate basis `explicit` unchanged); door/survey counts unchanged; objection not lifted by an awaiting sign-up; a confirmed door record (confirmed_at set) is proven again.
2. Migration V155: columns, flag existing door/survey rows, restore membership state, move `MOMENTUM_PLAN` segments to `origin='momentum'` and clear the key.
3. `ConsentRecord`, `ConsentConfirmation`, `ConsentService.capture`.
4. `ConsentGateSql` filter; `FanFeatureCalculator` filter.
5. Tests first for 2: sends off → `best()` empty and nothing read; sends on → target (existing tests run with sends on).
6. Tests first for 3: snapshot writes origin `momentum` and no prebuilt key; `listSegments` hides it; delete → 404 and row kept; SendPathGuard gates a Momentum campaign on an origin-`momentum` segment and not one on an organizer segment; migration test moves a `MOMENTUM_PLAN` row.
7. Targeted tests, then the full suite.

## Verification commands

- `cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-consent-hardening && ./mvnw test -Dtest=<targeted classes>`
- `cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-consent-hardening && /Users/ivan/.imin-pipeline/mvnlock.sh test`

## Test impact

Changed expectations: `DoorOptInServiceTest` / `SurveyServiceTest` asserted the sign-up made the member `subscribed`/`explicit` and plan-mailable and lifted an objection; they now assert the pending state. `MomentumPlanTargetTest` stubs sends-enabled on and asserts origin instead of the prebuilt key. Fixtures that insert `door_qr` records directly (ConsentGate, Retention, ReadModel scenarios) represent confirmed consents and are unchanged (the column defaults to not-required).

## Live-test

Not needed: no endpoint shape change; behaviour is covered by Spring/H2 and Postgres tests. No real sends, no env change.

## Contract impact

none (no DTO or endpoint change).

## i18n impact

none.

## Blast radius

- `ConsentService.capture` (every consent writer) — only `door_qr` / `survey` change behaviour.
- `ConsentGateSql` (plan mailability, read model, retention job, candidate loader) — records awaiting confirmation stop counting; all other rows unchanged.
- `SegmentService.listSegments` / `deleteSegment`, `SendPathGuard`, `MomentumPlanTarget` (Momentum drafts now always Repeat while sends are off).
- Migration rewrites prod rows: `consent_records` door/survey flag, affected `memberships` consent state, `segments` MOMENTUM_PLAN rows.

## Risks

- A member whose objection was lifted by a door/survey sign-up before this ship keeps it lifted (the prior value is not recoverable); door/survey refused unsubscribed members, so the case needs an objection without an unsubscribe.
- A member whose pre-door basis existed without any consent record (pre-Tier-C) is restored to `never`; not recoverable from the trail.
- During a rolling deploy the old instance could still write a `MOMENTUM_PLAN` snapshot after the migration; it would need beta on in prod and is superseded by (2), which stops new snapshots while sends are off.
- Door sign-ups read `no_basis` in the ConsentGate breakdown until the confirm flow exists (no new reason key, to keep the contract).

## Definition of done

All three items implemented with tests per branch; targeted tests green on H2 and Postgres; full `./mvnw test` green; no contract change.

## Live-test evidence

n-a

## Review rounds

(appended by the orchestrator)

- Round 1 → SHIP, with fixes applied: (1) `ClassRules.importBasisValid` skips records awaiting confirmation, so a later pending door/survey sign-up keeps the import basis (`FanFeatureCalculatorTest`); (2) rolling-deploy gap closed by `ConsentConfirmationReconciler` (startup + periodic, ShedLock, logged failure), tested on H2 and Postgres (flag + restore, second run changes nothing, unrelated member untouched); (3) prod check query documented under Prod safety. Rebased onto origin/master `412865e8`; V155 kept.
