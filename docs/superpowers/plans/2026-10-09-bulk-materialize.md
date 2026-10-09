# Bulk recipient materialization

Repo: `imin-api` · worktree `.claude/worktrees/bulk-materialize` · base `origin/master` e1a08052.

## Problem

`RecipientMaterializer.materialize` writes the campaign snapshot one member at a time:

- `recipients.save(r)` per sendable member (`RecipientMaterializer.java:127`) and per excluded member (`:140`).
  `CampaignRecipient` assigns its own UUID (`:100`, `:133`) and is not `Persistable`
  (`CampaignRecipient.java:17-23`), so Spring Data `save` merges: one SELECT plus one INSERT per row.
- `volumeGuard.isFrequencyCapped(mid, now)` per sendable member without a guard reason (`RecipientMaterializer.java:111`),
  one `countRecentSendsForMembership` query each (`CampaignVolumeGuard.java:31-35`, `CampaignRecipientRepository.java:197-204`).

Measured earlier today at ~19 ms/member on the test container. The drive runs `materialize` whole and outside the
dispatcher's run budget (`CampaignSendUnit.java:91-93`), under a 10-minute ShedLock with an 8-minute budget
(`CampaignDispatcher.java:48-49`): ~6k members eat the margin, ~30k outlast the lock.

Everything upstream is already set-based: `SendGateService.evaluate` (`SendGateService.java:80-100`) and
`SendPathGuard.skipReasons` (`SendPathGuard.java:63-97`, chunked at `MAX_IDS_PER_QUERY = 1000`, `:45`).

## Change

1. `CampaignRecipientRepository`: add `findRecentlySentMembershipIds(Collection<UUID> ids, Instant since)` — the same
   predicate as `countRecentSendsForMembership` (`:197-204`: any channel, status in sent/delivered/opened/clicked,
   `last_event_at >= since`) as `select distinct r.membershipId … where r.membershipId in :ids`. Remove the per-member
   query (its only caller is `CampaignVolumeGuard.java:34`).
2. `CampaignVolumeGuard`: replace `isFrequencyCapped(UUID, Instant)` with `frequencyCapped(Collection<UUID>, Instant)`
   returning the capped ids, chunked at 1000 ids per query (same chunk as `SendPathGuard.MAX_IDS_PER_QUERY`).
3. New `CampaignRecipientBulkInsert` (`marketing/repository`, `JdbcTemplate`): one
   `INSERT … SELECT FROM unnest(uuid[], uuid[], varchar[], varchar[], varchar[]) ON CONFLICT (campaign_id, membership_id) DO NOTHING`
   per 1000 rows, ids generated in Java as before, `last_event_at` the one `now`. Runs on the materializer's
   REQUIRES_NEW transaction (JpaTransactionManager exposes its connection to `JdbcTemplate`).
4. `RecipientMaterializer.materialize`: keep `lockForMaterialize` (`:69`), the count no-op (`:70-73`) and the canceled
   re-read under the lock (`:74-75`). Compute `frequencyCapped` once over the sendable members with no guard reason
   (the old loop only asked those, `:105-111`), build the row list in the same branch order (guard → frequency →
   canceled → pending; then gate exclusions), insert in batches, then `recordMaterialized` (`:148`) unchanged.
   `recipientCount`, `excludedCount`, `exclusionSummary` computed exactly as before. No audit row is written today.
5. Ponytail at `CampaignSendUnit.java:91-92`: restate the ceiling with the measured rate.

Equivalence notes: the frequency floor reads only rows in sent statuses; rows written by this materialize are
pending/skipped, so reading the floor before the inserts (instead of interleaved) sees the same data. Row order is
irrelevant: the drain claims `ORDER BY id` (`CampaignRecipientRepository.java:170`) and ids are random both before
and after. `ON CONFLICT DO NOTHING` cannot fire behind the count no-op under the row lock; it replaces a
unique-violation abort on a path that is unreachable today.

## Tests (integration, `@IminIntegrationTest`, new class `BulkMaterializeTest`)

- Equivalence: one audience with every outcome — pending, gate exclusions (`marketing_unsubscribed`,
  `marketing_suppressed`, `deliverability_suppressed`, `no_lawful_basis`), guard reasons
  (`experiment_holdout`, `event_cap`, `monthly_cap`, `consent_gate`) and `frequency_capped` — on a consent-gated
  event campaign. Expected rows/statuses/reasons, `recipientCount`, `excludedCount`, `exclusionSummary` captured by
  running the test against the unchanged implementation first. Same audience on a campaign canceled before
  materialize: guard/frequency/gate skips keep their reasons, the rest are `skipped/campaign_canceled`, counted in
  `recipientCount`.
- Size: 5,000 gate-sendable members (fixture via SQL) materialize within 30 s, with INSERT statements on
  `campaign_recipients` for this campaign ≤ 10 (statement-level trigger with a transition table) and
  Hibernate-prepared statements < 100 (runtime-enabled `Statistics`, restored after). Old code: ≥ 5,000 of each.
- Idempotency: materialize, mark one row sent now, materialize again → same row count, same counts and summary
  (without the no-op the sent row would frequency-cap its own member).
- `no_email` is unreachable through fixtures (`consumers.normalized_email` NOT NULL, `V47__audience_consumers.sql:5`),
  so `CampaignRecipientBulkInsertTest` writes a null-email row directly: stored as SQL NULL, other rows intact.
- `CampaignVolumeGuardTest`: switch its two calls to `frequencyCapped`; add 1,001 ids with the recently sent member
  at index 1,000, so only a query of the second chunk finds it.

Red proofs: size test red against the base; idempotency red with the count no-op removed; frequency set query
red against `frequencyCapped` returning empty; per-row insert / per-member cap query each red on the size test.

## Verification

`docker info`; `./mvnw test -Dtest='*Materializ*Test,Campaign*Test,CampaignDispatcher*Test,AudiencePlan*Dispatch*Test,SpringContextGuardTest,BulkMaterializeTest,SendPathGuardMaterializeTest'`.

## Out of scope

`SendGateService.evaluate` and `SegmentService.resolveMembers` bind the whole audience in one IN list
(`MembershipRepository.java:147-148`, `SuppressionRepository.java:60-61,84-85`, `ConsumerRepository.java:45-46`);
past 65,535 ids that exceeds the Postgres bind-parameter limit.
