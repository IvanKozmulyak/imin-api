# opened_at keeps the first open

Decision: `campaign_recipients.opened_at` / `clicked_at` hold the FIRST open/click (as ESPs report it); `last_event_at` stays the latest-arrival field.

## Affected files
- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRecipientRepository.java` (`markOpened`, `markClicked`)
- `src/test/java/com/imin/iminapi/marketing/ResendWebhookOutOfOrderTest.java`

## Ordered steps
1. `markOpened`/`markClicked`: `SET x = LEAST(COALESCE(x, :at), :at)`; `last_event_at = :at` unchanged. Plain COALESCE would keep the later instant when a later event arrives first, so LEAST is needed (Hibernate/Postgres accept it).
2. Add `aRepeatedOpenOrClickKeepsTheFirstInstant` (opened and clicked, both arrival orders): stamp = earliest, last_event_at = last arrival.

## Readers of opened_at / clicked_at (none depends on "last open")
- `CampaignRecipientRepository.java:92-114` derived finders/counts: `IS NOT NULL` only.
- `CampaignRecipientRepository.java:135-136`: `CASE WHEN ... IS NOT NULL` counts.
- `CampaignService.java:534-548, 717-718`: engagement filter and open/click counts, null-checks only.
- `RecipientDto.java:29`: exposes `openedAt`/`clickedAt` as-is to the dashboard; the value is now the first open.
- `CampaignRecipient.java:46-50`: entity columns.
- Not related: `Dispute.openedAt` (disputes); `DsarService.java:166-167` reads `Membership.lastEmailOpen/Click`, which the projector never writes (`ResendWebhookProjector.java:144-149`).
- `last_event_at` readers (`audienceplan/.../CandidateSql.java:31-37`, `OutcomeStore.java:60`) are untouched.

## Findings
`clicked_at` had the identical overwrite pattern (`markClicked`); fixed the same way.

## Verification
`./mvnw test -Dtest='ResendWebhook*Test,Campaign*Analytics*Test,SpringContextGuardTest'`, then `./mvnw test`.
Red on base: in-order open and click cases. Out-of-order cases pass on base (overwrite happens to land on the earlier instant) and are red with plain COALESCE.
