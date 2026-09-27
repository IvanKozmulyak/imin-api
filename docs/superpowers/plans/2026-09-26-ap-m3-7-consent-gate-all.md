# M3-7 ConsentGate for all campaigns (ap-m3-7-consent-gate-all)

Programme plan: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` §M3-7, D6.

## Goal and scope
Generalise the ConsentGate send-path check that M3-2 (`SendPathGuard`, origin 9e6755c3) applies to `origin='audience_plan'` campaigns so that, when `imin.audience-plan.consent-gate-all-campaigns=true`, it applies to **every** campaign (manual, Momentum, plan) both at materialisation (`RecipientMaterializer`) and on the per-batch re-check (`EmailChannelSender.divertGuarded`). The skip is recorded with reason `consent_gate` on the recipient row and in the campaign exclusion summary. Flag default `false`: off keeps today's SendGate-only path for non-arm campaigns. This is a legal/behaviour switch, not a beta gate, so the flag stays.

Carried forward verbatim:
- D6: "ConsentGate applies to ALL campaigns before the first real send."
- M3-7: "`RecipientMaterializer` requires `ConsentGate.canMarket` for **every** campaign (reason `consent_gate`) when the flag is on; off keeps today's SendGate-only path for non-arm campaigns. Add the key to properties + both yaml files."
- M3-7: "Before flipping: a read-only prod count per org of members mailable under SendGate but not under ConsentGate (by reason, esp. `legacy_unproven`), shared with Ivan/Bohdan. Flip together with, or before, the first real send (D6), never later."
- Review: "M3-7 ships behind a flag with a pre-flip count rather than switching on at merge, because it changes live sending for every org."
- D1: "explicit consent only now (`soft-opt-in-enabled=false`)".

Out of scope: the pre-flip prod count and the flip itself (Ivan's job); no env change.

## Repos in ship order
| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-7-consent-gate-all` | `./mvnw test` |

## Affected files (per repo)
api:
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanProperties.java` — `consentGateAllCampaigns` (Boolean, blank binds false).
- `src/main/resources/application.yaml` — `consent-gate-all-campaigns: ${IMIN_CONSENT_GATE_ALL_CAMPAIGNS:false}`.
- `src/test/resources/application.yaml` — `consent-gate-all-campaigns: false`.
- `src/main/java/com/imin/iminapi/audienceplan/service/SendPathGuard.java` — ConsentGate check when plan campaign OR flag on.
- Tests: `AudiencePlanAccessTest` (binding), `SendPathGuardTest` (branches), `SendPathGuardMaterializeTest` (materialiser, real ConsentGate), `SendPathGuardPerBatchTest` (per batch).

## Ordered steps
1. Add property + both yaml keys + binding tests (default false, blank env binds false, true binds, shipped yaml, null setter).
2. `SendPathGuard`: inject `AudiencePlanProperties`; `consentGated = planCampaign || flag`; early return only when neither about an event nor consent-gated.
3. Tests per branch (below). Reproduction test: n-a (new behaviour behind a flag).

## Verification commands
`./mvnw test` from the worktree root.

## Test impact
- Flag off → manual campaign reaches a `legacy_unproven` member (guard + materialiser).
- Flag on → manual campaign skips that member with `consent_gate` (guard, materialiser with summary `{"consent_gate":1}`, per batch).
- Flag on → manual campaign keeps a member ConsentGate admits.
- Flag on → manual campaign without `event_id` still asks ConsentGate (the no-event early return no longer short-circuits).
- Flag on → Momentum draft (`origin='momentum'`) gated.
- Arm (`audience_plan`) campaigns gated with flag off (existing tests) and with flag on.
- Caps still win over ConsentGate when flag on.

## Live-test
Not needed: no endpoint or contract change, flag default off; covered by SpringBootTest integration tests against the real ConsentGate SQL.

## Contract impact
none

## i18n impact
none (api only; `consent_gate` already an existing skip reason).

## Blast radius
`SendPathGuard` is on every campaign's send path (materialise + per batch). With the flag off the behaviour is identical to origin (only change: one extra property read). With the flag on every campaign in every org is ConsentGate-filtered — almost no existing member is mailable (`legacy_unproven`) until M3-P1 consents accrue; hence the pre-flip count.

## Risks
- Flag flipped without the pre-flip count → organizers' manual campaigns silently reach far fewer people. Mitigated: default false, flip is Ivan's decision.
- Test context mutating the shared `AudiencePlanProperties` bean → reset in `@AfterEach`.

## Definition of done
Key in properties + both yaml; flag off = origin behaviour; flag on = `consent_gate` skip for every origin at materialise and per batch; tests per branch green; `./mvnw test` green after rebase on latest origin/master.

## Live-test evidence
n-a (see Live-test).

## Review rounds
Implementation run 2026-09-27: baseline `./mvnw test` on untouched origin 9e6755c3 green. Guard proof: with the flag term removed from `SendPathGuard`, 7 flag-on tests fail (4 guard, 2 materialiser, 1 per-batch); restored → green. Rebased on origin/master (still 9e6755c3; tagged stash `ap-m3-7-consent-gate-all pre-rebase` 31e50601). Final `./mvnw test`: 4295 run, 0 failures, 0 errors, BUILD SUCCESS.
