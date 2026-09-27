# M3-2 Send-path guards

Slug: `ap-m3-2-send-guards` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (M3-2) · Mode: autonomous runner (no gates)

## Goal and scope

Own the experiments schema and make every send path respect it before any invitation (M3-1) exists:
- `V136__audience_experiments.sql` (`audience_experiments`, `audience_assignments`), entities, repositories (`@RepositoryRestResource(exported = false)`).
- `RecipientMaterializer` and the per-batch re-check in `EmailChannelSender`: for any campaign with `event_id`, skip members in a holdout of that event (`experiment_holdout`), members with 2 sent emails about that event (`event_cap`), members with 4 sent in 30 days (`monthly_cap`); for `origin='audience_plan'` campaigns, also require `ConsentGate.canMarket` (`consent_gate`). Quiet hours and SendGate re-check stay as they are. With the tables empty the holdout skip is a no-op, so shipping before M3-1 is safe.
- `DsarService.executeErase` cascade for `audience_assignments`.

Out of scope: writing experiments/assignments (M3-1), ConsentGate for every campaign (M3-7, behind `consent-gate-all-campaigns=false`), any contract change.

Rules carried forward verbatim:
- (C11) "Arm campaigns use ConsentGate (M3-2); all campaigns via M3-7 (D6)."
- (C12) "M3-2 adds a holdout skip in `RecipientMaterializer` for every campaign with that `event_id`."
- (M3 ship order) "M3-3 → M3-2 → M3-1 (or all three in one `/ship-imin`), so no prod state ever has invitation drafts without the kill switch and the holdout/cap guards."
- (D3) "Resend open/click tracking OFF for everyone" — `canTrack` is constant false; nothing here reads opens/clicks.
- (D1) "explicit consent only now (`soft-opt-in-enabled=false`)".
- (runner, Ivan 2026-09-26) No beta gating; real email/SMS sends stay behind `sends-enabled=false` (legal D9). This task adds no flag.
- (M1-9 review carry-over) "M1-9 counts as a send only `sent/delivered/opened/clicked` (CampaignVolumeGuard's predicate); `unsubscribed`/`complained` rows are not sends (ConsentGate already drops those people)." The guard uses the same predicate and the same `last_event_at` window.
- (§4.3 Erasure) "every table holding `membership_id` joins the `DsarService.executeErase` cascade in the task that creates it (… `audience_assignments` M3-2 …)". Assignments are not in the DSAR access export (§4.3 lists only `fan_features`, `import_row_provenance`, `survey_responses`).

Reproduction test: n-a (new code).

## Repos in ship order

| key | base | worktree | verification |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-2-send-guards` | `./mvnw test` |

## Affected files (per repo)

api:
- new `src/main/resources/db/migration/V136__audience_experiments.sql` (§4.3 reserved number)
- new `audienceplan/model/AudienceExperiment.java`, `AudienceAssignment.java`
- new `audienceplan/repository/AudienceExperimentRepository.java`, `AudienceAssignmentRepository.java`
- new `audienceplan/service/SendPathGuard.java`
- `marketing/repository/CampaignRecipientRepository.java` (+ `countEventSendsByMembership`, `countRecentSendsByMembership`)
- `marketing/send/RecipientMaterializer.java`, `marketing/send/EmailChannelSender.java`
- `audience/service/DsarService.java`
- tests: `audienceplan/service/SendPathGuardTest.java`, `marketing/send/SendPathGuardMaterializeTest.java`, `marketing/send/SendPathGuardPerBatchTest.java`, `audience/AudienceAssignmentErasureTest.java`, `CampaignDispatcherGatingTest` (+ arm in quiet hours)

## Ordered steps

1. Migration + entities + repositories.
2. `SendPathGuard.skipReasons(campaign, membershipIds, now)`: no-op (no query) for a campaign with neither `event_id` nor `origin='audience_plan'`; precedence holdout → event_cap → monthly_cap → consent_gate; ids chunked by 1000.
3. Materializer: guard over SendGate-sendable ids, before the frequency floor; skipped rows carry the reason and count into `exclusion_summary` / `excludedCount`.
4. EmailChannelSender: `divertGuarded` after `divertNoLongerSendable`, same shape.
5. DsarService erase deletes assignments.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-2-send-guards && ./mvnw test`

## Test impact

One test per branch:
- holdout of the event → `experiment_holdout` (manual campaign for the same event); holdout of another event → not skipped; non-holdout arm (`launch`) → not skipped; holdout of another org's experiment for the same event id → not skipped.
- event_cap: 2 sent for the event → skipped, 1 → not; `unsubscribed`/`skipped` rows are not sends.
- monthly_cap: 4 sent in 30 days → skipped, 3 → not; a send older than 30 days does not count.
- consent_gate: audience_plan campaign + ConsentGate excluded → skipped; ConsentGate mailable → not; member absent from ConsentGate → skipped; manual campaign never consults ConsentGate.
- campaign without `event_id` (manual) → unaffected, no query.
- empty assignments → no skips.
- materializer: skipped rows + summary + counts; the real ConsentGate skips a legacy member for an arm while a manual campaign sends to them.
- per batch: holdout written after materialisation → diverted, not sent.
- quiet hours still defer an arm (dispatcher, sends on).
- erase removes assignments.

## Live-test

Not needed: no endpoint, no contract; the send path is exercised end to end by the `@SpringBootTest` suite (dispatcher claim → materialize → batch) and real sends are off (`sends-enabled=false`).

## Contract impact

none

## i18n impact

none (api only; skip reasons are machine strings already shown as chips by the recipient log).

## Blast radius

`RecipientMaterializer` and `EmailChannelSender` run for every email campaign. New skips apply only to campaigns with an `event_id` (caps; holdout is a no-op until M3-1 writes assignments) and to `audience_plan` campaigns (ConsentGate). Manual event campaigns can now skip members already emailed twice about the event or four times in 30 days (`event_cap`, `monthly_cap`), which is new behaviour for live organizers. Migration is additive.

## Risks

- Event campaigns of existing orgs lose recipients who were emailed ≥ 2× about the event or ≥ 4× in 30 days; that is the rule, shown via skip reasons.
- `last_event_at` moves on delivery/open webhooks, so the 30-day window can over-count slightly (stricter, never looser).

## Notes

- Caps are check-then-send: `skipReasons` runs once at materialisation and again per claimed
  batch, but two concurrent sends can each pass the per-batch check for the same member before
  either writes its `sent` row, landing a third email past the cap. Accepted — the caps are a
  ceiling on ongoing contact, not a hard lock, and the gap closes on the next check.
- Follow-up card: a pre-send estimate of cap exclusions in the composer, so an organizer sees
  how many recipients are already at the event/monthly cap before scheduling, not just after.
- `EVENT_CAP_SENDS` / `MONTHLY_CAP_SENDS` / `MONTHLY_CAP_DAYS` are to be unified with M1-9's
  Exclusions/logic yaml once both have shipped, so the thresholds live in one place.

## Definition of done

Migration applies on H2 (suite boot); every branch above has a test; `./mvnw test` green on the rebased tree.

## Live-test evidence

n-a (see Live-test).

## Review rounds

(appended by the orchestrator)
