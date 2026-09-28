# Campaign list rows carry segment and event names

## Goal and scope
GET /api/v1/marketing/campaigns list rows (`CampaignSummary`) gain three additive, nullable fields:
`segmentName`, `eventName`, `eventTimezone`. Resolution matches campaign detail: segment by
id within the caller's org (hidden plan/Momentum origins included); event by id within the org,
non-deleted (cancelled still resolves, as `findActive` does). Batch-loaded per page, one query
per kind, no N+1. Reproduction test: n-a (new fields).

## Repos in ship order
| key | base | worktree | verification |
|---|---|---|---|
| api | master | imin-api/.claude/worktrees/ap-campaign-list-names | `mvnlock.sh clean test` |

## Affected files
- `audience/repository/SegmentRepository.java` — `findByOrgIdAndIdIn`
- `repository/EventRepository.java` — `findActiveByOrgAndIds`
- `audience/service/SegmentService.java` — `namesByIds`
- `marketing/dto/CampaignSummary.java` — three fields + 5-arg `from`
- `marketing/service/CampaignService.java` — `list` batches names/events
- tests: `CampaignListNamesTest` (H2), `CampaignListNamesPostgresTest`, `CampaignDtoTest`

## Ordered steps
1. Repository IN queries (org-scoped; event query also `deletedAt IS NULL`).
2. `SegmentService.namesByIds` (empty set → empty map, no query).
3. `CampaignSummary` fields; old `from` overloads pass nulls.
4. `CampaignService.list` collects non-null ids from the page, one lookup each, maps per row.

## Verification commands
`rm -rf target/classes/db && /Users/ivan/.imin-pipeline/mvnlock.sh clean test`

## Test impact
Branches: own hidden segment → name; foreign segment → null; unknown segment → null; no segment →
null; own event → name + zone; cancelled event → name + zone; deleted event → null; foreign event →
null; no event → null (empty-id branch); multi-row page; `namesByIds` empty and org filter; DTO
overloads. Postgres variant covers both IN queries.

## Live-test
Not needed: read-only projection covered by H2 and Postgres integration tests.

## Contract impact
/api/v1 — additive. Marker: `components.schemas.CampaignSummary.properties.eventName`
(field names alone are not unique in the spec; `segmentName:` count goes 1 → 2).

## i18n impact
None.

## Blast radius
List endpoint only; two extra read queries per page (skipped when no ids).

## Risks
Cancelled events still show a name, same as detail's zone — intentional parity.

## Definition of done
Full suite green; fields present in OpenAPI CampaignSummary.

## Live-test evidence
n-a

## Review rounds
