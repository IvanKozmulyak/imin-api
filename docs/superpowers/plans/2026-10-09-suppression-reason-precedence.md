# Fix: a buyer's spam complaint is always the recorded marketing suppression reason

## Goal

A complaint (`spam`) and a third soft bounce (`soft-bounce`) can race for the same marketing
suppression slot (`(scope, org_id, membership_id)`). Since ab8cbe17 the projector's system write is
`INSERT … ON CONFLICT DO NOTHING`, so whichever writer commits first keeps its `reason`. Organizers
see that reason (`MemberDto.java:67`, `SuppressionInfo(scope, reason, since)`). A buyer's own spam
complaint is their objection, so it must win over a system soft-bounce escalation, whichever of
the two commits first. Decision approved by the user ("пофікси розсилку").

## Writers of a marketing suppression row (base 8632624d)

| writer | file:line | reason | who sets it |
|---|---|---|---|
| Resend complaint | `ResendWebhookProjector.java:130` → `SuppressionService.addMarketingIfAbsent` | `spam` | the person (system records it) |
| Soft-bounce escalation (3rd strike) | `ResendWebhookProjector.java:185` → `addMarketingIfAbsent` | `soft-bounce` | system |
| Import provenance | `ImportProvenanceWriter.java:51` → `SuppressionService.addMarketing` | `unsubscribe` | the person, recorded by the organizer's import |
| (no writer today) | V50 comment `V50__audience_suppression.sql:18`, `addMarketing` javadoc | `manual`, `hard-bounce` | organizer / legacy |

No HTTP path adds a marketing suppression (`AudienceController.java:220` only reads them), and no code
in `src/main` has ever written a marketing-scope `hard-bounce` (`git log -S` over `addMarketing`).

## Precedence table (stored reason × incoming system write)

| stored \ incoming | `spam` (complaint) | `soft-bounce` (escalation) |
|---|---|---|
| (none) | insert `spam`, audit | insert `soft-bounce`, audit |
| `soft-bounce` | **upgrade to `spam`**, no audit | untouched |
| `spam` | untouched | untouched |
| `unsubscribe` | untouched | untouched |
| `manual` | untouched | untouched |
| `hard-bounce` (legacy only) | untouched | untouched |
| anything else | untouched | untouched |

The rule is minimal: spam replaces only the one system-derived reason that a writer produces today.
A person's or organizer's reason (`unsubscribe`, `manual`) is never rewritten. The organizer-facing
`addMarketing` keeps read-then-insert and never rewrites a row.

## Change

1. `SuppressionRepository.insertMarketingIfAbsent` becomes `upsertMarketing`:
   `INSERT … ON CONFLICT (scope, org_id, membership_id) DO UPDATE SET reason = EXCLUDED.reason
   WHERE EXCLUDED.reason = 'spam' AND suppression_entries.reason = 'soft-bounce' RETURNING (xmax = 0)`.
   It returns `[true]` when inserted, `[false]` when upgraded, and empty when untouched. `xmax = 0`
   holds only for a freshly inserted tuple. Against an in-flight conflicting writer, Postgres waits;
   once that writer commits, it re-evaluates the `WHERE` on the committed row (READ COMMITTED), so
   the race resolves the same way as the sequential order. `since`, `channel` and `system_owned`
   are untouched on upgrade: the member has been suppressed since the first write.
2. `SuppressionService.addMarketingIfAbsent` audits `SUPPRESSION_ADDED` only when the row was
   inserted. **Audit on upgrade: none.** `AuditActions` has no suppression-change action (only
   `SUPPRESSION_ADDED`, `AuditActions.java:40`), and reusing `SUPPRESSION_ADDED` would claim a
   second suppression that does not exist. So no enum was invented; see Risks.
3. Deliverability scope is untouched. Its only writer is the permanent bounce with `hard-bounce`
   (`ResendWebhookProjector.java:108-109`). Complaints never write deliverability (they are
   org-scoped marketing rows), so no second reason competes for that slot.

## Tests (`ResendWebhookOutOfOrderTest`, `@IminIntegrationTest`, no new context)

- `aComplaintQueuedBehindASoftBounceSuppressionRecordsSpam`: two prior strikes, then
  `PgFaults.pauseWrites(audit_logs, target_id, membershipId)` holds the third soft bounce after
  its suppression INSERT. The complaint is fired and awaited on the slot
  (`PgLocks.awaitLockWait("insert into suppression_entries")`), then the pause is released.
  - Asserts: both requests 200, reason `spam`, exactly one `SUPPRESSION_ADDED` whose summary says
    `reason=soft-bounce`, and the membership is `objected_profiling`.
  - Red on base: `["soft-bounce"]`.
- `theStoredSuppressionReasonAfterASecondWriter`, parameterized over the stored reason (seeded via
  the organizer `addMarketing`) and the incoming event:
  - spam then soft bounce → `spam`
  - soft-bounce then complaint → `spam` (red on base)
  - unsubscribe then soft bounce → `unsubscribe`
  - unsubscribe then complaint → `unsubscribe`
  - manual then soft bounce → `manual`
  - manual then complaint → `manual`

  Each case asserts exactly one `SUPPRESSION_ADDED`.

Guards proven by mutation:
- Drop `AND suppression_entries.reason = 'soft-bounce'` → cases [4] and [6] red (spam overwrote
  unsubscribe/manual).
- Drop the whole `WHERE` → cases [1] [3] [4] [5] [6] red.
- Audit on any returned row (`!written.isEmpty()`) → case [2] and the race test red (2 audit rows).

## Risks

- After an upgrade, the trail shows one `SUPPRESSION_ADDED` with `reason=soft-bounce` while the row
  says `spam`. The complaint itself is still visible on the recipient (`complained`) and on the
  membership (`objected_profiling`). A dedicated audit action is a follow-up product call.
- `RETURNING (xmax = 0)` relies on Postgres's tuple header semantics for `ON CONFLICT DO UPDATE`.
  These are stable, and the race and sequential tests pin them.

## Affected files

- `src/main/java/com/imin/iminapi/audience/repository/SuppressionRepository.java`
- `src/main/java/com/imin/iminapi/audience/service/SuppressionService.java`
- `src/test/java/com/imin/iminapi/marketing/ResendWebhookOutOfOrderTest.java`
- `docs/superpowers/plans/2026-10-09-suppression-reason-precedence.md`

## Verification commands

- `docker info`
- `./mvnw test -Dtest='ResendWebhook*Test,AudienceSuppressionUniquenessTest,AudienceSendGateConsentSuppressionTest,Suppression*Test,SpringContextGuardTest'`
- `./mvnw test` (no skipped tests)
