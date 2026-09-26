# ap-m1-5-fan-projector: FanFeatureProjector and nightly recompute

Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md`, task M1-5. Base: `origin/master` @ `1b90d117` (M1-4 calculator), rebased onto `217f58cf` before the final gate.

## Goal and scope

Persist `fan_features` rows from the M1-4 `FanFeatureCalculator`, for **all orgs** (no beta gate), on three live paths and one nightly pass:
- purchase: after the async audience membership upsert commits;
- door scan: `TicketRedeemedEvent` (no-show count);
- consent: `ConsentChanged`, published by `ConsentService.capture` / `unsubscribe` and by the `ResendWebhookProjector` complaint branch, so an objection clears taste immediately;
- nightly: a full keyset-paged recompute chained on `AudienceBackfillCompleted`, with a 05:30 Europe/Paris fallback that runs only when some membership was not refreshed in 24 h.

No migration, no endpoint, no new config key. Reproduction test: n-a (new code).

Rules carried forward verbatim from the programme plan:
- M1-5: "Listeners `@TransactionalEventListener(AFTER_COMMIT) @Async` on `TicketsIssuedEvent`, the redeem event, `ConsentChanged`; full recompute on `AudienceBackfillCompleted` with `@SchedulerLock(name="fan_feature_recompute")`; fallback cron `0 30 5 * * *` zone Europe/Paris that runs only if no recompute finished in 24 h. Failures log and never break the source transaction."
- M1-5 files: "modify `audience/service/AudienceBackfillJob.java` (publish `AudienceBackfillCompleted` at the end of `run()`), `audience/service/ConsentService.java` (publish `ConsentChanged` after capture/unsubscribe), `AudiencePlanAccess` (+ non-throwing `isEnabled(UUID orgId)`)".
- C7: "Chain by event: backfill publishes `AudienceBackfillCompleted` at the end of `run()`; the fan-feature recompute listens. New jobs set `zone = "Europe/Paris"` explicitly."
- "**Ivan 2026-09-26 (later): no beta flag — ship straight to prod, no customers yet.** New code is not beta-gated; `AudiencePlanAccess` becomes a default-open kill switch (task M1-1b). Real sends stay behind `sends-enabled=false` until D9. Because the projector now runs for all orgs, M1-13 (privacy line + objection) moves right after M1-5."
- (M1-3 review carry-over) "M1-5: always copy fan_features.org_id from the membership row (no FK ties them)".
- (M1-13 carry-over) "M1-5: ResendWebhookProjector complaint sets objected_profiling directly — publish ConsentChanged there too (or route via ConsentService) so taste clears on complaint."
- C13: "Opens and clicks never feed features without `canTrack`" — "M1-4 never reads them". C14: "Fan features never read those columns (tags can carry identity labels)."
- D3: "Resend open/click tracking OFF for everyone; no tracking-consent box at checkout; lift from holdouts only."
- §4.3: "Migrations must stay H2/PG-compatible"; erasure: "`fan_features` M1-3" joins the `DsarService.executeErase` cascade (already shipped; the projector never writes an `erase_pending` membership).

Deviations (flagged for review):
- **Not gated** by `fan-features-all-orgs` (orchestrator instruction: ignore it). The only gate is `AudiencePlanAccess.isEnabled(orgId)` (global kill switch + optional org list, both default open). No key added.
- **Purchase trigger is `MembershipProjected`, not `TicketsIssuedEvent` directly.** `AudienceOrderProjector` creates the membership in its own `@Async` AFTER_COMMIT listener, so a second listener on `TicketsIssuedEvent` races it and finds no membership on a first purchase. `AudienceOrderProjector.onTicketsIssued` now publishes `MembershipProjected(orgId, normalizedEmail)` inside its REQUIRES_NEW transaction; the projector listens AFTER_COMMIT of that. `AudienceRedeemProjector` is untouched; the projector listens to `TicketRedeemedEvent` itself (the membership already exists by then).
- **"No recompute finished in 24 h"** is measured as `countStale(now − 24h)`: live memberships with no `fan_features` row updated since the cutoff (no new table). Every write sets `updated_at`, so an unchanged row still counts as refreshed. With a non-blank org allow-list, memberships of unlisted orgs stay stale and the fallback runs daily (harmless extra pass).
- `sends_30d` is never written here (M3 owns it); an existing value is kept.
- Consent captures from the organizer (`OPERATOR`) also publish `ConsentChanged` (cheap, keeps last-contact current).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-5-fan-projector` | `./mvnw test` |

## Affected files (per repo)

api, new:
- `audience/service/ConsentChanged.java`, `MembershipProjected.java`, `AudienceBackfillCompleted.java` (event records)
- `audienceplan/repository/FanFeatureTarget.java`
- `audienceplan/service/FanFeatureProjector.java`, `FanFeatureRecomputeJob.java`
- tests: `audienceplan/service/FanFeatureFixtures.java`, `FanFeatureProjectorTest.java`, `FanFeatureTriggerEventsTest.java`, `FanFeatureRecomputeJobTest.java`, `FanFeatureRecomputeQueriesContract.java` + `FanFeatureRecomputeH2Test.java` + `FanFeatureRecomputePostgresTest.java`

api, modified:
- `audience/service/ConsentService.java` (publisher; `ConsentChanged` after capture and unsubscribe)
- `marketing/webhook/ResendWebhookProjector.java` (publisher; `ConsentChanged` in the complaint branch)
- `audience/service/AudienceBackfillJob.java` (publisher; `AudienceBackfillCompleted` at the end of `run()`)
- `audience/service/AudienceOrderProjector.java` (publisher; `MembershipProjected` after the upsert)
- `audienceplan/config/AudiencePlanAccess.java` (`isEnabled(orgId)`; `requireEnabled` delegates)
- `audienceplan/repository/FanFeatureRepository.java` (target keyset queries, `findTarget`, `countStale`)
- `repository/OrderRepository.java` (`findByOrgIdAndNormalizedEmailIn`), `audience/repository/ConsentRecordRepository.java` (`findByMembershipIdIn`)
- tests: `AudiencePlanAccessTest`, `AudienceBackfillStartupTest`, `ResendWebhookProjectorTest`, `MarketingOptInWriteTest` (constructor arg)

## Ordered steps

1. Event records; publish from ConsentService (both mutators), ResendWebhookProjector complaint, AudienceBackfillJob end of run, AudienceOrderProjector after upsert.
2. `AudiencePlanAccess.isEnabled`.
3. Repository queries. Keyset over `membership_id` with separate first-page / after-cursor methods (no nullable bound parameter, the H2-vs-PG bytea trap); joins `consumers` for the normalized email; `status <> 'erase_pending'`.
4. `FanFeatureProjector`: per page, per org: org timezone (UTC when missing or unreadable), orders by `lower(email) IN`, tickets by order ids, events by ids, consents by membership ids, all IN lists chunked at 1000; calculator per membership; each write in its own transaction (a failure skips only that row). `org_id` always from the membership row; `updated_at` set every pass.
5. `FanFeatureRecomputeJob`: `@EventListener @Async` on backfill completion → `recomputeAll()` through the proxy; `@Scheduled(cron="0 30 5 * * *", zone="Europe/Paris")` fallback; `@SchedulerLock(name="fan_feature_recompute", lockAtMostFor="PT2H", lockAtLeastFor="PT1M")`; page size 500.
6. Tests (below), rebase onto latest origin/master, full gate.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-5-fan-projector && ./mvnw test`

## Test impact

One test per branch:
- `AudiencePlanAccessTest` (+4): `isEnabled` default true; kill switch off false; null org false; non-blank list only listed orgs.
- `FanFeatureProjectorTest` (16, H2, plain instance so listeners run synchronously): purchase writes every column (org id from membership, class, taste/cities/formats JSON, no-shows, group size, last contact, logic version, sends_30d 0, updated_at); membership only in another org → no row; unknown email no-op; redeem recomputes no-show 1 → 0; unknown order no-op; objection (`ConsentService.unsubscribe` DATA_SUBJECT) + `ConsentChanged` clears taste/cities/formats but keeps paid orders; kill switch off → no row; org off a non-blank list → no row; erase_pending skipped; wrong org for the membership → no row; batch with a failing target writes the others; unreadable timezone → UTC; existing row takes org id from membership and keeps sends_30d; unchanged values still refresh updated_at; listener failures swallowed; listeners are AFTER_COMMIT + @Async.
- `FanFeatureTriggerEventsTest` (4): capture publishes `ConsentChanged`; unsubscribe publishes it; tickets issued publishes `MembershipProjected` with the normalized email; unknown order publishes nothing.
- `ResendWebhookProjectorTest` (+1, +1 assertion): complaint publishes `ConsentChanged`; delivered publishes none.
- Inline objection clear: `FanFeatureProjectorTest` DATA_SUBJECT unsubscribe clears taste before commit with the live queue full, OPERATOR unsubscribe leaves it; `ResendWebhookProjectorTest` complaint clears taste before commit; `AudienceDsarTest` request/execute erase lock the membership before deleting `fan_features`.
- `AudienceBackfillStartupTest` (+1): `run()` publishes `AudienceBackfillCompleted(processed, skippedErased)`.
- `FanFeatureRecomputeJobTest` (9, unit): backfill completion runs one locked recompute; its failure is swallowed; fallback skips when nothing stale; runs when stale; empty registry writes nothing; full page continues after its last id until a short page; exact multiple stops on the empty page; page size 500; cron/zone/lock name/listener annotations pinned.
- `FanFeatureRecomputeQueriesContract` run on H2 (`FanFeatureRecomputeH2Test`, 5) and on Postgres 17 via Testcontainers (`FanFeatureRecomputePostgresTest`, 5 + sanity): keyset pages cover every live membership once (erase_pending excluded, objection flag carried); `findTarget` scoped to org and live status; orders IN-batch case-insensitive within the org; full pass across orgs (loyal + taste + consent-based last contact; prospect `none` with `{}`; erase_pending no row); `countStale` counts missing and old rows, not erase_pending.

## Live-test

Not needed: no endpoint or contract; the job and its queries run end to end against a real Postgres 17 in `FanFeatureRecomputePostgresTest`.

## Contract impact

none

## i18n impact

none

## Blast radius

- Every `ConsentService.capture/unsubscribe` and every spam complaint now publishes an event; the listener is AFTER_COMMIT, hands off to a dedicated executor and swallows failures, so no caller's transaction or response changes.
- Live recomputes run on `fanFeatureExecutor` (2 threads, queue 1000, coalesced per key, drop on overflow); full passes on `fanFeatureRecomputeExecutor` (1 thread, queue 1). Neither touches the shared default async pool that carries the audience projections.
- Every `fan_features` write takes the membership row lock first (`lockByIdAndOrgId`), then writes the feature row: projector, objection paths, `requestErase` and `executeErase` all use this order, so concurrent writers serialise with short waits instead of deadlocking.
- An objection clears taste/cities/formats inline: a DATA_SUBJECT unsubscribe (`ConsentService`, reached by one-click, `DsarService.object` and `requestErase`) and a spam complaint (`ResendWebhookProjector`) lock the membership and empty those columns in the same transaction that sets `objected_profiling`. The `ConsentChanged` recompute is a refresh, not the safeguard, so a full or dropped queue cannot leave profiling data behind.
- The backfill now triggers a full recompute on every startup and nightly (async, ShedLock-serialised across replicas). Cost at current scale: a few queries per 500 memberships per org.
- The fallback runs inline on the shared 4-thread scheduler pool when triggered.

## Risks

- Runs for all orgs with no profiling-notice gate: the programme plan made M1-13 (privacy line) follow M1-5 for this reason; M1-13 shipped (`87682652`) for the objection path; confirm the public privacy line is live before calling the feature done.
- (M1-4 follow-up) An organizer can still type a reserved `source` such as `checkout` on `POST /consent/capture`, which would refresh last contact; the consent-hardening task closes it.
- Concurrent writers (a live listener, the nightly pass, an objection, an erasure) serialise on the membership row lock, so there is no primary-key collision on a brand-new row; the cost is a short wait on that lock.
- A live queue overflow drops only a refresh; the nightly pass rewrites the row, and objections never depend on the queue.

## Definition of done

`./mvnw test` green on the rebased worktree, including the Postgres Testcontainers class; no new config key; no migration.

## Live-test evidence

n-a (see Live-test).

## Review rounds

(appended by the orchestrator)
