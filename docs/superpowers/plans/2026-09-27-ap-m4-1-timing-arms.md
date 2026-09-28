# M4-1 Timing arms scheduling (ap-m4-1-timing-arms)

Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` § "M4-1". Depends on M3-1 (412865e8, invitations + per-arm drafts), M3-2 (event/monthly caps), M3-3 (kill switch).
Reproduction test: n-a (new behaviour).

## Goal and scope

An invited arm's draft campaign gets its send time from the arm, computed on the server when the organizer approves it in the existing campaign flow (`POST /api/v1/marketing/campaigns/{id}/send`), and stored in `campaigns.scheduled_at` for the existing dispatcher. No real sends: the kill switch (`IMIN_AUDIENCE_PLAN_SENDS_ENABLED=false`) still answers 409 `AUDIENCE_SENDS_DISABLED` before any of this runs, and the dispatcher never claims an `audience_plan` campaign while it is off.

- `launch` = max(approval time, event `on_sale_at`).
- `d3` = event date − 3 days at 18:00 in the event timezone.
- `early_bird_end` (logic 8.1, new arm) = 18:00 event timezone on the day the cheapest enabled tier's `sale_closes_at` falls; offered only when that day is before the D-3 date and another enabled tier stays on sale after it (no close, or a later close). Checked when inviting (400 when not available) and again at approval (409 when it no longer applies).
- `slump` (new arm): approval **arms** it (`audience_experiments.armed_at`, campaign stays `draft`); when `MomentumEvaluator` publishes `MomentumTriggered` with `slump` for that event, every armed slump draft of the event is scheduled for now. The listener re-checks the beta gate, the sends switch and the org's legal identity, so it never bypasses the kill switch.
- Every computed time is pushed out of email quiet hours (22:00–09:00 org timezone, `QuietHours`) to the next 09:00; a time at or after the event start, or a d3/early-bird time already past at approval, is a 409 `INVALID_STATE`.
- A client `scheduledAt` on an arm campaign is a 400 `FIELD_INVALID` (the arm owns the time). Campaigns that are not an invitation arm keep today's behaviour.
- `two_emails` is **not** added: it needs two drafts for one arm half, which does not fit M3-1's one-campaign-per-arm shape (`Arm.segmentId/campaignId`). Left for a later task.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m4-1-timing-arms` | `./mvnw test` (full suite via `/Users/ivan/.imin-pipeline/mvnlock.sh test`) |

## Affected files (per repo)

api:
- `src/main/resources/db/migration/V157__audience_experiments_armed_at.sql` (new; V155 consent-hardening, V156 M3-5, V158 M4-8)
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanLogic.java` (`TimingArm` + `EARLY_BIRD_END`, `SLUMP`)
- `src/main/java/com/imin/iminapi/audienceplan/engine/ActionPlanner.java` (new arms are not plan-dated)
- `src/main/java/com/imin/iminapi/audienceplan/engine/ArmTimes.java` (new, pure)
- `src/main/java/com/imin/iminapi/audienceplan/model/AudienceExperiment.java` (`armedAt`)
- `src/main/java/com/imin/iminapi/audienceplan/repository/AudienceExperimentRepository.java` (two finders)
- `src/main/java/com/imin/iminapi/audienceplan/service/TimingArmScheduler.java` (new)
- `src/main/java/com/imin/iminapi/audienceplan/service/SlumpArmListener.java` (new)
- `src/main/java/com/imin/iminapi/audienceplan/service/InvitationService.java` (early-bird availability check; additive)
- `src/main/java/com/imin/iminapi/audienceplan/dto/AudiencePlanInvitationsRequest.java` (javadoc only)
- `src/main/java/com/imin/iminapi/marketing/service/CampaignService.java` (`send` asks the scheduler for arm campaigns and returns `CampaignSendResponse`; `patch` disarms an armed slump draft)
- `src/main/java/com/imin/iminapi/marketing/dto/CampaignSendResponse.java` (new, review round 1)
- `src/main/java/com/imin/iminapi/marketing/controller/CampaignController.java` (`/send` 202 carries the body, review round 1)
- `CLAUDE.md` (env/behaviour line)
- tests (new): `engine/ArmTimesTest.java`, `service/SlumpArmListenerTest.java`, `controller/TimingArmSchedulingTest.java`; (changed, one line) `controller/AudiencePlanInvitationScenarios.java`: its unknown-arm case used `slump`, which is now valid, so it uses `two_emails`; after the rebase onto M4-8, `controller/AudiencePlanInviteOnPublishWebTest.java` gets the same one-line change

## Ordered steps

1. Migration V157: `audience_experiments.armed_at TIMESTAMP WITH TIME ZONE` (nullable).
2. `TimingArm` gains `EARLY_BIRD_END`, `SLUMP`; `ActionPlanner` gives them no plan date (the plan suggests the default arms only).
3. `ArmTimes` (pure): `d3`, `earlyBirdEnd(tiers, start, zone)`, `outOfQuietHours(t, orgZone)`.
4. `InvitationService.requireArmsAvailable`: `early_bird_end` only while `ArmTimes.earlyBirdEnd` is present.
5. `TimingArmScheduler.onApproval(campaign, principal, requestedAt, now)` → time or armed; `fireSlump(orgId, eventId)` (REQUIRES_NEW).
6. `CampaignService.send`: after the kill switch, legal identity and channel guards, arm campaigns use the scheduler; slump arming audits and returns without the CAS.
7. `SlumpArmListener` on `MomentumTriggered` (`slump` only): beta gate, sends switch, then `fireSlump`; failures logged.
8. Tests (one per branch), CLAUDE.md line.

## Verification commands

- `cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m4-1-timing-arms && /Users/ivan/.imin-pipeline/mvnlock.sh test`

## Test impact

New: `ArmTimesTest` (d3 for 24.10.2026 = 21.10.2026 18:00 Paris; early-bird 10.10.2026 → 18:00; no second tier → none; second tier closing earlier → none; closing on/after D-3 → none; cheapest wins; disabled tier ignored; quiet-hours push 22:30 → next 09:00, 07:00 → same-day 09:00, 18:00 unchanged). Scenarios through the API: approval with sends on schedules launch/d3/early-bird at the computed instant; client `scheduledAt` → 400; kill switch still 409 first; d3 already past → 409; slump approval arms without scheduling; SLUMP fires → scheduled; other trigger → nothing; sends off at fire → nothing; early-bird arm not available → 400; a member with two sends about the event is skipped `event_cap` by an arm campaign. `SlumpArmListenerTest` (unit): non-slump trigger, beta off, sends off, failure swallowed.

## Live-test

Not needed locally: no UI, and sends stay off in every environment; the scenarios drive the real controllers on H2 and Postgres (Testcontainers when Docker is available).

## Contract impact

/api/v1 (review round 1): `POST /marketing/campaigns/{id}/send` now answers its 202 with `CampaignSendResponse {scheduledAt, armed}` (was empty; additive, the request is unchanged). Marker = `CampaignSendResponse`; webapp runs `api:sync` in `/ship-imin` after the api deploy. `arms` stays a free string list (new accepted values `early_bird_end`, `slump`); 400 on `scheduledAt` for arm campaigns is new behaviour.

## i18n impact

none (api only; error messages are the existing codes).

## Blast radius

`CampaignService.send` for `origin='audience_plan'` campaigns only (everything else returns before the new branch). `MomentumTriggered` gains a second listener (sync, own transaction, never throws). `TimingArm` enum growth: the only exhaustive switch is `ActionPlanner`.

## Risks

- Early-bird at 18:00 on the closing day can land after a tier that closes earlier that day (spec wording kept; the organizer sees the time on the campaign before it goes).
- An armed slump draft is disarmed by editing it (PATCH clears `armed_at`) or deleting it; there is no explicit disarm endpoint.
- Quiet hours use the org timezone (as the dispatcher does), the arm times use the event timezone.

## Definition of done

Full api suite green; all branches above tested; kill switch untouched; CLAUDE.md updated.

## Verification results

- Baseline (origin/master 412865e8, untouched): `./mvnw test` → Tests run: 5066, Failures: 0, Errors: 0, Skipped: 3, BUILD SUCCESS.
- This change: `mvnlock.sh test` → Tests run: 5105, Failures: 0, Errors: 0, Skipped: 3, BUILD SUCCESS (+39: TimingArmSchedulingTest 19, ArmTimesTest 17, SlumpArmListenerTest 3).
- Review round 1 fixes, rebased on c83debac: `mvnlock.sh clean test` → Tests run: 5136, Failures: 0, Errors: 0, Skipped: 3, BUILD SUCCESS (TimingArmSchedulingTest now 27).

## Live-test evidence

n/a (see Live-test).

## Review rounds

(appended by /do-task)

- Round 1 → SHIP, with three approved fixes applied in review-fix:
  1. Tests for the untested `TimingArmScheduler` branches: event gone → 409, event started at approval → 409, slump approval on a non-draft → 409, quiet-hours push at/after the event start → 409, `fireSlump` pushed time at/after the start → 0, null `campaignId` skipped, invitation with `early_bird_end` when available.
  2. `PATCH` of an armed slump draft clears `armed_at` (`TimingArmScheduler.disarm`), so it needs a new approval; tested.
  3. `/send` returns `CampaignSendResponse {scheduledAt, armed}` (contract marker `CampaignSendResponse`); tested for d3, slump and a non-arm audience-plan campaign.
  Rebased onto origin/master c83debac (M4-8, V158); V157 kept. M4-8's `AudiencePlanInviteOnPublishWebTest` used `slump` as its unknown arm; now `two_emails`, as in the invitation scenarios.
