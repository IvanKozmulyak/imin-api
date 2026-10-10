# Send gate and static segments past 65,535 members

## Problem
Every id-keyed read on the email send path binds the whole audience in one JPQL `IN` list. Postgres
rejects a statement with more than 65,535 bind parameters, so a campaign to a static segment of
65,536+ members fails in `RecipientMaterializer.materialize` (ponytail at `marketing/send/CampaignSendUnit.java:91-92`).

Unbounded `IN` queries on that path (base `b8e37473`):
- `audience/repository/MembershipRepository.java:147-149` `findByIdsAndOrgId` — called by `SegmentService.java:222`
  (static snapshot), `SendGateService.java:80`, `EmailChannelSender.java:437`, `CampaignService.java:614`.
- `audience/repository/SuppressionRepository.java:60-62` `findMarketingSuppressedMembershipIds` — `SendGateService.java:88`.
- `audience/repository/SuppressionRepository.java:84-85` `findDeliverabilityEmailsIn` — `SendGateService.java:100`,
  `NotifyReleaseSender.java:167`.
- `audience/repository/ConsumerRepository.java:45-46` `findAllByConsumerIdIn` — `SendGateService.java:94`,
  `RecipientMaterializer.java:94`, `AudienceService.java:101,124,148`, `EmailChannelSender.java:441`.

Already bounded, left alone: `ConsentGate.reasons` (1,000-id chunks, `ConsentGate.java:102-112`),
`SendPathGuard.skipReasons` (chunks, `SendPathGuard.java:77-82`), `CampaignVolumeGuard.frequencyCapped`
(chunks, `CampaignVolumeGuard.java:44-47`), `CampaignRecipientBulkInsert` (uuid[] unnest).

## Approach
Chunk, the pattern the JPA side of the repo already uses (ConsentGate, SendPathGuard, CampaignVolumeGuard);
the only `uuid[]` use is JdbcTemplate (`CampaignRecipientBulkInsert.java:26`), and rewriting four entity
JPQL queries as native array queries would change result mapping for no gain.

- New `util/IdChunks.java`: `MAX_IDS_PER_QUERY = 10_000`; `query(ids, fn)` passes a collection of at most
  10,000 straight through (identical to today), otherwise de-duplicates in encounter order (a duplicate
  split across two chunks would otherwise return its row twice — `IN` de-duplicates on its own) and
  concatenates the per-chunk results in chunk order.
- Each of the four queries keeps its JPQL, renamed with a `Chunk` suffix and documented as bounded; the
  existing method name becomes a `default` method that routes through `IdChunks`, so every caller is fixed
  and Mockito stubs of the old name keep working. `findByIdsAndOrgId` keeps `@Param("orgId")` so
  `AudienceControllerWebTest.m4_membership_repository_has_no_unscoped_finders` still sees the org scope.
- Result identity: same rows, same sets, same reasons. Row order was unspecified (no `ORDER BY`) and stays so
  within a chunk; under 10,001 ids nothing changes at all.
- Update the ponytail at `CampaignSendUnit.java:91-92` to the new ceiling (time and heap: the whole audience
  is loaded and gated in one transaction).

## Tests
- Unit `util/IdChunksTest` (two tests, small chunk size via the package-private overload): pass-through at
  or under the limit; over it, every id queried once, chunks of at most the limit, a duplicate across chunks
  yields one row.
- Integration `audience/SendGateChunksTest` (`@IminIntegrationTest`):
  - 70,000 SQL-built members with one member per reason placed in different 10,000-chunks: `evaluate`
    returns exactly the expected sendable set and reasons; `materialize` of a static segment of them writes
    every row. Red on base (bind-parameter error), proven with the test before the fix exists. Cleans its
    members, consumers, suppressions and campaigns in `@AfterEach`; the consumer delete runs under
    `session_replication_role = replica` because `memberships.consumer_id` has no index of its own and the
    per-row FK check cost ~45 s for 70,000 rows (the memberships are already deleted by org).

## Ordered steps
1. Write `IdChunksTest` and `SendGateChunksTest`; run the size test on base → red.
2. Add `IdChunks`, route the four repository methods through it.
3. Update the ponytail.
4. Verify.

## Verification commands
- `docker info`
- `./mvnw test -Dtest='SendGate*Test,Segment*Test,BulkMaterializeTest,SendPathGuard*Test,Campaign*Test,SpringContextGuardTest,IdChunksTest,AudienceControllerWebTest'`

## Affected files
- `src/main/java/com/imin/iminapi/util/IdChunks.java` (new)
- `src/main/java/com/imin/iminapi/audience/repository/MembershipRepository.java`
- `src/main/java/com/imin/iminapi/audience/repository/SuppressionRepository.java`
- `src/main/java/com/imin/iminapi/audience/repository/ConsumerRepository.java`
- `src/main/java/com/imin/iminapi/marketing/send/CampaignSendUnit.java`
- `src/test/java/com/imin/iminapi/util/IdChunksTest.java` (new)
- `src/test/java/com/imin/iminapi/audience/SendGateChunksTest.java` (new)
