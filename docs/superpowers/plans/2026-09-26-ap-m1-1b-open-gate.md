# M1-1b — Open the audience-plan gate by default

## Goal and scope
Turn `AudiencePlanAccess` from a dark beta gate into a global kill switch that is open by default (Ivan 2026-09-26: no beta gating). `imin.audience-plan.enabled` defaults to `true`; a blank `beta-org-ids` means all orgs; a non-blank list still restricts (optional allow-list); `enabled=false` 404s everyone; null org 404s; a malformed UUID still fails startup. Nothing calls `requireEnabled` yet.

## Repos in ship order
| key | base | worktree | verification command |
|---|---|---|---|
| api | master | imin-api/.claude/worktrees/ap-m1-1b-open-gate | `./mvnw test` |

## Affected files (per repo)
api: `audienceplan/config/AudiencePlanAccess.java`, `audienceplan/config/AudiencePlanProperties.java`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`, `CLAUDE.md`, `src/test/.../audienceplan/config/AudiencePlanAccessTest.java`.

## Ordered steps
1. Properties: field default `enabled = true`; javadoc rewritten (kill switch, blank list = all).
2. Access: pass when enabled, org non-null, and (list empty or org listed).
3. application.yaml: `${IMIN_AUDIENCE_PLAN_ENABLED:true}`, comments updated; test yaml `enabled: true`.
4. CLAUDE.md env-var bullet updated.
5. Tests, one per branch.

Reproduction test: n-a (behaviour change, not a bug).

## Verification commands
`./mvnw test` from the worktree root.

## Test impact
`AudiencePlanAccessTest`: defaults open (plain construction, immune to env); disabled + listed org → 404; disabled + blank list → 404; non-blank list, absent org → 404; listed org passes; blank list → any org passes; trailing comma dropped and list still restricts; null org → 404 even with blank list; malformed UUID → startup failure.

## Live-test
Not needed: no caller of `requireEnabled` exists, so there is no reachable surface.

## Contract impact
none

## i18n impact
none

## Blast radius
Config class only; no endpoint uses it yet. Once endpoints land, the tool is live for every org in prod unless `IMIN_AUDIENCE_PLAN_ENABLED=false` is set on Railway.

## Risks
If Railway already has `IMIN_AUDIENCE_PLAN_ENABLED=false` or a non-empty `IMIN_AUDIENCE_PLAN_BETA_ORGS`, those still win; check the env at ship. An env var set to an empty string resolves to "" rather than the `true` default.

## Definition of done
Defaults open, kill switch and allow-list behave as above, `./mvnw test` green.

## Live-test evidence
n-a (see Live-test).

## Review rounds
(orchestrator)
