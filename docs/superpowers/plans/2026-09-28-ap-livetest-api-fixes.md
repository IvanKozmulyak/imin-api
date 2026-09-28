# ap-livetest-api-fixes

## Goal and scope

API fixes from the final live-test of the audience-plan programme (imin-api only).

1. `GET /api/v1/audience/metrics` `complaintRatePct` was hardcoded `0.0` while complaints are stored
   (`email.complained` provider events, projected by `ResendWebhookProjector`). Compute it as complaints ÷
   delivered × 100 like `ComplaintRateBreaker` (see review round 1) and return `null` when nothing was sent
   (never a fabricated 0).
2. `unsubRatePct` stays a raw percentage double (e.g. `7.03125`); the DTO documents units (percent, 0–100,
   unrounded). Rounding is the webapp's job.
3. `GET /api/v1/audience/members/{id}/consent-history` (and the `consentHistory` in the DSAR export) gains
   `confirmationRequired`, `confirmedAt`, `eventId`, `eventName` per entry. `eventName` is resolved only
   for the caller org's events. Stored `proof_text` and the returned `proofText` stay verbatim (it is the
   legal proof); the drawer renders `eventName` instead of parsing the proof.
4. Member search (`GET /audience/members?search=` and the CSV export) also matches an exact normalized email
   (trim + lower-case) of a member of the caller org. No privacy issue: `MemberDto.email` already shows the
   same address to that organizer; the match is org-scoped and exact (no substring enumeration).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | /Users/ivan/imin/imin-api/.claude/worktrees/ap-livetest-api-fixes | `/Users/ivan/.imin-pipeline/mvnlock.sh clean test` |

## Affected files

imin-api:
- `src/main/java/com/imin/iminapi/audience/service/AudienceMetricsService.java`
- `src/main/java/com/imin/iminapi/audience/dto/AudienceMetricsDto.java`
- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRecipientRepository.java`
- `src/main/java/com/imin/iminapi/audience/dto/ConsentHistoryEntry.java`
- `src/main/java/com/imin/iminapi/audience/service/DsarService.java`
- `src/main/java/com/imin/iminapi/audience/repository/MembershipRepository.java`
- `src/main/java/com/imin/iminapi/audience/service/AudienceService.java`
- `src/main/java/com/imin/iminapi/audience/service/MemberListQuery.java`
- tests: `AudienceMetricsTest`, `AudienceDsarTest`, `AudienceControllerWebTest`, `AudiencePostgresTest` (or a new search test)

## Ordered steps

1. Reproduction tests (red on base): complaint rate with a complained recipient; consent history
   confirmation + event fields; member search by exact email (both list and CSV paths).
2. `CampaignRecipientRepository.countComplaintsByOrgId` (JPQL join to Campaign by org).
3. `AudienceMetricsDto.complaintRatePct` → `Double`; unit docs on both rates; service computes it.
4. `ConsentHistoryEntry` new fields; `DsarService.consentHistory` resolves org-owned event names.
5. Email match in `MemberListQuery` (native) and `MembershipRepository.searchByOrg` (JPQL).
6. Full suite.

## Verification commands

- `/Users/ivan/.imin-pipeline/mvnlock.sh clean test` (from the worktree root)

## Test impact

- `AudienceMetricsTest`: `complaint_rate_pct_is_zero_at_tier_c` pinned the bug → replaced by complaints
  present / absent / no-subscribers(null) / other-org isolation tests; empty-org test asserts null.
- `AudienceDsarTest`: door-QR record carries confirmationRequired/confirmedAt null/eventId/eventName; a
  checkout record carries false/null/null/null; a foreign org's event id yields eventName null.
- `AudienceControllerWebTest`: constructor calls updated; JSON carries the four new fields.
- Search: exact email match (case/whitespace-normalized), substring of email does not match, other org's
  member with that email not returned; CSV path the same.

## Live-test

Not run: all changes are read-side and covered by Spring Boot integration tests on H2; the email search
query is JPQL/native with non-null params only (no bytea trap).

## Contract impact

/api/v1 — additive fields on `ConsentHistoryEntry` (marker `confirmationRequired`); `complaintRatePct`
becomes nullable (was always 0). Webapp follow-up: render `—` for null complaint rate, use `eventName`,
show "awaiting confirmation" when `confirmationRequired && confirmedAt == null`.

## i18n impact

none (api only).

## Blast radius

Audience metrics tile, member drawer consent trail, DSAR export JSON, member list search and CSV export.

## Risks

- Webapp renders `null%` for complaint rate until its follow-up ships (currently shows a fake 0%).

## Definition of done

Reproduction tests red on base, green after; full suite green.

## Live-test evidence

No local server run. Baseline (untouched origin/master): 5439 tests, 0 failures, 3 skipped.
Reproduction red on base: AudienceMetricsTest 4 failures (expected 25.0/100.0/null, was 0.0),
AudienceMemberSearchTest 2 failures (email match empty), AudienceDsarTest compile error (no
confirmationRequired/confirmedAt/eventId/eventName). After fix: full suite 5455 tests, 0 failures,
3 skipped; AudiencePostgresTest (Testcontainers Postgres) 19/19 including the email-search cases.

## Review rounds

### Round 1 → FIX_REQUIRED

- HIGH: `complaintRatePct` divided complained recipient rows by `subscribedMailable`, a different population
  (a complainer is suppressed/unsubscribed, so the rate could exceed 100). Fix: complaints ÷ delivered, matching
  `ComplaintRateBreaker`. Numerator = distinct recipients of the org's campaigns with an `email.complained`
  `ProviderEvent` (survives a later status overwrite); denominator = the org's recipient rows in the
  `OutcomeStore.SENT_STATUSES` set; the numerator is restricted to the same set so it never exceeds 100; null
  when nothing was sent. `CampaignRecipientRepository.countComplaintsByOrgId` replaced by
  `countComplainedRecipientsByOrgId` + `countSentRecipientsByOrgId`; DTO javadoc updated.
  Tests: one complaint over N delivered (25.0, pending excluded); complainer then unsubscribed with zero
  subscribers and duplicate events (50.0, ≤100); zero when none complained; null with zero delivered;
  other org ignored.

- round 2 → SHIP (MEDIUM: numerator also joins pe.campaignId = c.id so the (campaign_id, type) index is used; webapp ships together)
