# Fix timing-arm full-entity saves racing an edit

Base: `origin/master` 8632624d. Repo: `imin-api` only.

## Goal

A slump arm's `armed_at` is written by two paths that each load the `AudienceExperiment` and save the whole entity
(`TimingArmScheduler.java:98-103` approval, `:127-135` disarm). The entity has no `@Version`/`@DynamicUpdate`, and
the approval takes no campaign row lock while the edit does (`CampaignService.java:216`). So:

- an approval that read the draft before an edit committed arms the edited draft (edit runs first, approval lands after);
- an edit that runs while an approval is in flight reads `armed_at = null`, disarms nothing, and the approval then
  commits `armed_at` on the edited draft;
- `fireSlump` reads the armed list, then its `markScheduledIfDraft` waits out the edit's row lock and schedules the
  edited, now disarmed draft (Postgres re-checks only `status = 'draft'` on the new row version).

All three end with edited content scheduled or armed without a fresh approval.

## Decision: the edit wins

An edit means the organizer changed the content, so the draft needs a fresh approval. When an approval and an edit of
the same slump draft race:

- **approval first, edit second** → the approval arms, the edit then disarms. End: not armed.
- **edit first, approval read the draft before the edit committed** → the approval is refused with 409
  `INVALID_STATE` ("edited meanwhile; review it and approve again"). End: not armed.
- An approval whose first read of the campaign comes after the edit committed is a fresh approval of the edited draft
  and arms it (that is `editingAnArmedSlumpDraft_disarmsIt_untilApprovedAgain`).

Serialization point: the campaign row lock the edit already takes. The approval takes the same lock
(`FOR NO KEY UPDATE`, conflicts with the edit's `PESSIMISTIC_WRITE`) and compares the row's `updated_at` (patch always
bumps it, `CampaignService.java:247`) with the value it loaded at the start of `send`. `fireSlump` takes the same lock
per campaign and re-reads `armed_at` before its CAS, so a disarm committed meanwhile wins.

ponytail: the server only knows what the approval request read, not what the organizer's screen showed; an
`If-Match`/version from the client is the ceiling.

## Writers audit — `audience_experiments`

| # | writer | file:line | what it writes | concurrent with |
|---|---|---|---|---|
| 1 | `ExperimentService.write` (from `record`, `MANDATORY` tx) | `audienceplan/service/ExperimentService.java:104-117` | INSERT whole row | nothing: new row, invisible until the invitation commits |
| 2 | `InvitationService.attachDraft` | `audienceplan/service/InvitationService.java:202-203` | full save, sets `campaign_id` | nothing: same tx that inserted the row and created the campaign |
| 3 | `TimingArmScheduler.onApproval` slump branch | `audienceplan/service/TimingArmScheduler.java:98-103` | full save, sets `armed_at` | **#4, #5** |
| 4 | `TimingArmScheduler.disarm` (from `CampaignService.patch` `:224`, under campaign lock `:216`) | `audienceplan/service/TimingArmScheduler.java:127-135` | full save, clears `armed_at` | **#3, #5** |
| 5 | `TimingArmScheduler.fireSlump` (from `SlumpArmListener.java:28`, `REQUIRES_NEW`) | `audienceplan/service/TimingArmScheduler.java:150-176` | reads `armed_at`, writes `campaigns` via `markScheduledIfDraft` `:169` | **#4** |
| 6 | FK `campaign_id … ON DELETE SET NULL` (draft delete `CampaignService.java:684-691`) | `db/migration/V136__audience_experiments.sql:15` | `campaign_id = null` | sets a column no other writer reads back into a save |
| 7 | FK `event_id … ON DELETE CASCADE` | `db/migration/V154__segments_origin_and_experiment_fks.sql:28-29` | row delete | — |
| 8 | V154 orphan cleanup | `db/migration/V154__segments_origin_and_experiment_fks.sql:15-20` | DELETE, migration-time only | — |

Endpoints: `PATCH /api/v1/marketing/campaigns/{id}` (`CampaignController.java:85` → #4),
`POST /api/v1/marketing/campaigns/{id}/send` (`CampaignController.java:123` → `CampaignService.send` `:430` →
#3 at `:458`), `POST /api/v1/events/{id}/audience-plan/invitations` (`AudiencePlanInvitationController.java:30` →
#1, #2), `DELETE /api/v1/marketing/campaigns/{id}` (`CampaignController.java:153` → #6). Readers that never write:
`OutcomeStore`, `PlanPruner`, `findInvited`.

## Ordered steps

1. Write `TimingArmRaceTest` (below); run it on the base code; record red.
2. `AudienceExperimentRepository`: add `arm(id, at)` — `UPDATE … SET armedAt = :at WHERE id = :id AND armedAt IS NULL`;
   `disarm(campaignId, orgId)` — `UPDATE … SET armedAt = NULL WHERE campaignId = … AND orgId = … AND armedAt IS NOT NULL`;
   `existsByIdAndArmedAtIsNotNull(id)`.
3. `CampaignRepository`: add `lockIfDraft(id, orgId)` (`SELECT id … AND status = 'draft' FOR NO KEY UPDATE`, native)
   and `findUpdatedAtById(id)` (JPQL scalar, reads the row as committed).
4. `TimingArmScheduler.onApproval` slump branch: `lockIfDraft` (empty → "Campaign is not in draft"), compare
   `findUpdatedAtById` with `c.getUpdatedAt()` (differs → 409 edited meanwhile), then `arm` (0 rows → "already armed").
5. `TimingArmScheduler.disarm`: one targeted `experiments.disarm(...)`.
6. `TimingArmScheduler.fireSlump`: per arm, `lockIfDraft` then `existsByIdAndArmedAtIsNotNull`, skip if either fails,
   then the existing `markScheduledIfDraft`.
7. Run the targeted and full gates.

Lock order is campaign row first, experiment row second on every path (patch, approval, fireSlump), so no new deadlock.

## Affected files

- `src/main/java/com/imin/iminapi/audienceplan/service/TimingArmScheduler.java`
- `src/main/java/com/imin/iminapi/audienceplan/repository/AudienceExperimentRepository.java`
- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRepository.java`
- `src/test/java/com/imin/iminapi/audienceplan/service/TimingArmRaceTest.java` (new)
- this plan

## Test impact

Concurrency/locks on Postgres → integration (`@IminIntegrationTest`, no extra context). New class
`TimingArmRaceTest`, interleavings forced with `PgFaults.pauseWrites` and waits on Postgres lock state:

1. `anApprovalRacingAnEdit_neverLeavesTheEditedDraftArmed` — `@ParameterizedTest` over the two orderings:
   - `EDIT_FIRST`: edit paused at its campaign write (holding the row lock); approval runs; edit released.
     Expect approval 409, `armed_at` null, edit kept. Base: approval arms → red.
   - `APPROVAL_FIRST`: approval paused at its `audience_experiments` write; edit runs; approval released.
     Expect approval 202 armed, then edit disarms → `armed_at` null, edit kept. Base: edit reads null, approval commits
     armed → red.
2. `aSlumpRacingAnEdit_neverSchedulesTheEditedDraft` — armed draft; edit paused at its campaign write (holding the
   row lock, disarm done); `fireSlump` runs and waits; edit released. Expect 0 scheduled, status `draft`, not armed,
   edit kept. Base: schedules the edited draft → red.
   The reverse ordering (slump schedules first, edit waits) is not in this change: on base the waiting edit writes
   `draft` back over `scheduled`, because `CampaignService.patch`'s locked re-read returns the stale managed entity.
   That is a separate defect in `CampaignService.patch`, reported for its own card.

Existing `TimingArmSchedulingTest` slump cases (`approvingASlumpArmThatIsNotADraft_isRefused`,
`approvingASlumpArm_armsItWithoutScheduling_andASecondApprovalIsRefused`,
`editingAnArmedSlumpDraft_disarmsIt_untilApprovedAgain`, the `aSlump*` cases) cover the non-race branches unchanged.

## Risks

- A non-edit write that bumps a draft's `updated_at` between the approval's load and its lock turns the approval
  into a spurious 409; the organizer re-clicks. Draft writers that bump it: `patch` only (`touch`, claims, retries
  act on non-draft rows).
- The approval now waits on an in-flight edit's row lock (bounded by the edit's transaction).
- `fireSlump` takes one campaign lock per armed arm, held until its own transaction commits.

## Definition of done

- `TimingArmRaceTest` red on base (evidence pasted), green after.
- `./mvnw test -Dtest='TimingArm*Test,*Experiment*Test,CampaignServiceTest,CampaignServiceRaceTest,SpringContextGuardTest'` green.
- Full `./mvnw test` green, no skipped tests.
