# M3-3 Sends kill switch (ap-m3-3-sends-kill-switch)

Programme plan: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` §M3-3.

## Goal and scope

Real sends of audience-plan campaigns (`campaigns.origin = 'audience_plan'`) are refused while
`imin.audience-plan.sends-enabled` is false (the default). Scheduling or sending such a campaign
→ 409 `AUDIENCE_SENDS_DISABLED`; drafts stay editable. Keyed on the existing `campaigns.origin`
column, so nothing from M3-1 is needed. `manual` and `momentum` campaigns are unaffected.

Rules carried forward verbatim:
- Programme §M3-3: "Keyed on the existing `campaigns.origin` column (value `audience_plan`), so it needs nothing from M3-1. Scheduling or sending a campaign with `origin='audience_plan'` while `sends-enabled=false` → 409 `AUDIENCE_SENDS_DISABLED`; drafts stay editable. Add `sends-enabled` to properties + both yaml files."
- §2 D9: "Platform legal gates: legal entity, final privacy policy, DPA with organizers (spec §16, logic 6.8) | Open | Blocks flipping `sends-enabled` only."
- Decisions (Ivan 2026-09-26, later): "Real sends stay behind `sends-enabled=false` until D9."
- M3 ship order: "M3-6 and M3-7 must be live before `sends-enabled` is flipped for anyone."
- Risks: "Legal: no real send until D9 plus M3-4/M3-6/M3-7; `sends-enabled` is the enforcement point and is tested (M3-3)."
- §4.2: `sends-enabled: ${IMIN_AUDIENCE_PLAN_SENDS_ENABLED:false}        # M3-3 (D9)`; "Each later task adds its own key to `AudiencePlanProperties`, to `src/main/resources/application.yaml` and to `src/test/resources/application.yaml` (which replaces rather than merges), with a binding test for its default."
- Runner: no beta gating and no new per-feature enable flags; `sends-enabled` defaults to FALSE and is the one flag that stays off.

Enforcement points (all three are "sending"):
1. `CampaignService.send` (the only draft→scheduled path; `scheduledAt` null = send now, set = schedule).
2. `CampaignService.retry` (failed→scheduled re-queue).
3. `CampaignDispatcher.claimDue` skips `audience_plan` campaigns while the flag is off, so a flag switched
   back off stops a campaign already scheduled or retrying (the claim query also re-claims `failed` rows).

Plus: `duplicate` keeps `origin='audience_plan'` for a copy of an audience-plan campaign (it forced
`manual` before), otherwise duplicate-then-send bypasses the switch.

Not blocked: create/patch/delete/test-send (test-send goes to the organizer only), cancel.

Reproduction test: n-a (new code).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-3-sends-kill-switch` | `./mvnw test` |

## Affected files

api:
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanProperties.java` — `sendsEnabled` (default false).
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanAccess.java` — `ORIGIN`, `sendsAllowed(origin)`, `requireSendsAllowed(origin)`.
- `src/main/java/com/imin/iminapi/security/ErrorCode.java` — `AUDIENCE_SENDS_DISABLED`.
- `src/main/java/com/imin/iminapi/marketing/service/CampaignService.java` — guard in `send`, `retry`; origin kept in `duplicate`.
- `src/main/java/com/imin/iminapi/marketing/send/CampaignDispatcher.java` — skip in `claimDue`.
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — `sends-enabled`.
- Tests: `AudiencePlanAccessTest` (extended), new `marketing/AudiencePlanSendsKillSwitchTest`.

## Ordered steps

1. Property + both yaml files + binding test for the default (env var stubbed to empty).
2. `AudiencePlanAccess.requireSendsAllowed` + ErrorCode.
3. Wire into `send` (after the 404 + role gate, before the SMS check and the CAS), `retry` (after the state check), `duplicate`, dispatcher.
4. Tests, `./mvnw test`.

## Verification commands

`./mvnw test` from the worktree root.

## Test impact

`AudiencePlanAccessTest`: default false (plain construction and via yaml binding with the env var empty); `requireSendsAllowed` per branch (audience_plan+off → 409 code/message; audience_plan+on → pass; manual/momentum/null + off → pass).
`AudiencePlanSendsKillSwitchTest` (SpringBootTest, flag toggled on the bound properties bean, restored after each test):
- flag off → `send` now (scheduledAt null) 409 AUDIENCE_SENDS_DISABLED, stays draft, no audit row;
- flag off → `send` with scheduledAt 409, stays draft;
- flag off → draft still patchable;
- flag off → `retry` of a failed audience_plan campaign 409, stays failed;
- flag off → dispatcher does not claim a due audience_plan campaign; flag on → it does;
- flag on → `send` schedules normally; `retry` requeues normally;
- flag off → `manual` and `momentum` campaigns send normally;
- `duplicate` of an audience_plan campaign keeps the origin, of a momentum campaign gives manual.

## Live-test

Not needed: no endpoint or schema change; behaviour is fully covered by SpringBootTest through the service and dispatcher, and no audience_plan campaigns can exist in prod until M3-1.

## Contract impact

none (the error code is a string value in the existing error envelope; `ErrorCode` is not in the OpenAPI schema).

## i18n impact

none (api only). The webapp will show `AUDIENCE_SENDS_DISABLED` when M3 UI tasks land.

## Blast radius

`CampaignService.send/retry/duplicate` and `CampaignDispatcher.claimDue` gain one origin check each; every
existing campaign is `manual` or `momentum`, which the check passes untouched.

## Risks

- Flag flipped on before D9/M3-4/M3-6/M3-7 → real sends. Mitigation: default false in code and both yaml files, tested.
- Switching sends off mid-drive: `CampaignSendUnit` re-checks the switch before every batch and stops on the existing paused path (status stays `sending`, remaining recipients stay pending, no `sent`/`failed` stamp); the claim resumes it only once sends are re-enabled. At most the batch in flight when the flag flips still leaves.
- Postgres binding of the boolean claim parameter differs from H2 → covered by `CampaignClaimPostgresTest` (Testcontainers, real PG 17).
- A dispatcher-held audience_plan campaign (flag switched back off) stays `scheduled`/`failed` silently; acceptable for a kill switch, nothing sends.

## Definition of done

Flag bound with default false; the three enforcement points plus duplicate implemented and tested; `./mvnw test` green.

## Live-test evidence

n-a (see Live-test).

Verification 2026-09-26: baseline `./mvnw test` on untouched origin/master 38ddf336 green (3310 run, 0 fail).
After rebase onto origin/master 86c92ed7 (M1-3 V132/V133): `./mvnw test` green, 3342 run, 0 fail, 0 error, 0 skip.
No migration used. Found in implementation: a blank `IMIN_AUDIENCE_PLAN_SENDS_ENABLED` failed startup on a primitive
`boolean`; the property is a `Boolean` so blank binds false (tested). The dispatcher hold is in the claim SQL, not in
Java, so held campaigns cannot use up the `LIMIT 10` (tested with 10 held + 1 manual).

## Review rounds

Round 1 (orchestrator review, SHIP with fixes): added `CampaignClaimPostgresTest` (claimDue on Postgres: off → only manual, on → both), a stale-`sending` hold test, and the per-batch re-check in `CampaignSendUnit` + `CampaignSendUnitSendsSwitchTest` (switched off mid-drive stops before the next batch and stays `sending`; manual unaffected; on drains to `sent`).
