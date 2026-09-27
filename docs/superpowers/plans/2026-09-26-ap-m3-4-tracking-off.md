# ap-m3-4-tracking-off

Task M3-4 "Tracking off and send-time legal assertions" of `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md`. Mode: autonomous runner. Reproduction test: n-a (new rules; the open/click membership write is a decided behaviour change, not a bug).

## Goal and scope

1. A Spring Boot health indicator (`resendTracking`) that reads the marketing sending domain from the Resend domains API and reports the custom status `TRACKING_ON` when open or click tracking is on (logged at ERROR), `UP` when both are reported off, and `UNKNOWN` when it cannot tell (no API key, no from-domain, domain not in the account, key lacks the domains scope, API unreachable, tracking fields absent from the response). `application.yaml` orders `TRACKING_ON` below `UP` and maps it to 200 (restating `down`/`out-of-service` → 503, because a custom `http-mapping` replaces Boot's defaults), so the root `/actuator/health` never goes non-200 because of it. It never runs at boot and never fails boot. A probe never waits on Resend: it serves the cached verdict (5-minute TTL) and refreshes it single-flight on a background daemon thread; before the first result lands it answers `UNKNOWN` (`reason: pending`).
2. `ResendWebhookProjector` stops calling `MembershipRepository.recordEmailOpen/recordEmailClick` for everyone. `campaign_recipients.opened_at/clicked_at` writes stay.
3. Art. 14 source line: the first email an org sends to a recipient whose membership has an accepted `import_row_provenance` row carries one footer line naming the source of their address (`import_row_provenance.source_platform`); later emails do not.
4. Assert (no rebuild) that marketing email links are identical for two recipients apart from the per-recipient unsubscribe URL, and that every marketing email carries the unsubscribe link and both RFC 8058 headers.

Out of scope (ops, not code): switching tracking off on the Resend domain and reading the live domain's current state (M0-2 output). No Resend call with real keys, no Railway env change, no real sends.

Rules carried forward verbatim from the programme plan:
- D3: "Resend open/click tracking OFF for everyone; no tracking-consent box at checkout; lift from holdouts only."
- §2 D3 row: "No `tracking_consent` columns (M1-3), no tracking boxes (M3-P1, M4-3). `canTrack` is constant false."
- §4.2: "No tracking keys (D3)."
- M3-4: "open/click tracking off on the campaign sending domain (ops step using M0-2's facts) plus a startup check / health indicator via the Resend domain API that reports `DOWN` if either is on; `ResendWebhookProjector` stops calling `MembershipRepository.recordEmailOpen/recordEmailClick` (lines 129/133) for everyone. `campaign_recipients.opened_at/clicked_at` writes stay (none arrive with tracking off)."
- M3-4: "Art. 14 source line: the first email to an `imported` recipient carries one line naming the source of their address (from `import_row_provenance.source_platform`)."
- M3-4 tests: "health indicator DOWN when tracking is on (stubbed client); opened/clicked webhook no longer changes `memberships`; links in a rendered campaign are identical for two recipients (campaign-level UTM only); every marketing email has unsubscribe link and both one-click headers (the headers already exist in `CampaignEmailProvider:56-57`; assert, do not rebuild); first email to an imported member has the source line, second does not."
- §3 C13: "M1-4 never reads them; M3-4 stops the membership writes for everyone (D3); M3-R2 stops rendering; M3-R6 removes the DTO fields."
- Runner: real sends stay behind `sends-enabled=false`; no new per-feature enable flags.

Decisions made here:
- "imported recipient" = membership with at least one **accepted** `import_row_provenance` row (the rows that prove an `organizer_import_row` consent). All distinct `source_platform` values of accepted rows, oldest first, are named.
- "first email" = no earlier `campaign_recipients` row of an **email** campaign of the same org for that membership with status in `sent, delivered, complained, unsubscribed` (mail that reached the person; `opened`/`clicked` are never written as a status). A bounced earlier attempt does not count as received.
- "imported" provenance is scoped to the sending org: only rows whose `audience_imports.org_id` is the campaign's org count.
- The Art. 14 line also says IMIN processes the address on the organizer's behalf and links the buyer-site privacy notice (`<buyer-site-base-url>/legal/privacy`, the route `imin-public` serves) for retention and rights.
- Only a health indicator, no boot-time check: a boot check would call Resend from every test context and at every deploy. `management.health.resend-tracking.enabled=false` in the test profile keeps `SecurityPerimeterTest`'s `/actuator/health` probe off the network.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-4-tracking-off` | `./mvnw test` |

## Affected files

api:
- new `src/main/java/com/imin/iminapi/marketing/email/ResendTrackingHealthIndicator.java`
- new `src/main/java/com/imin/iminapi/marketing/send/AddressSourceLines.java` (first-email source-line lookup)
- `src/main/java/com/imin/iminapi/marketing/webhook/ResendWebhookProjector.java` (drop membership open/click writes)
- `src/main/java/com/imin/iminapi/audience/repository/MembershipRepository.java` (remove the now-unused `recordEmailOpen/recordEmailClick`)
- `src/main/java/com/imin/iminapi/marketing/render/CampaignEmailRenderer.java` (overload with `addressSource`, footer + text line)
- `src/main/java/com/imin/iminapi/marketing/send/EmailChannelSender.java` (resolve source lines per batch, pass to renderer)
- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRecipientRepository.java` (received-before query)
- `src/main/java/com/imin/iminapi/audienceplan/repository/ImportRowProvenanceRepository.java` (accepted rows by memberships)
- `src/test/resources/application.yaml` (`management.health.resend-tracking.enabled: false`)
- `src/main/resources/application.yaml` (`management.endpoint.health.status.order` + `http-mapping`)
- `CLAUDE.md` (resendTracking indicator, switch, TRACKING_ON mapping, frozen open/click columns)
- tests: new `marketing/email/ResendTrackingHealthIndicatorTest.java`, new `marketing/email/ResendTrackingHealthEndpointTest.java`, new `marketing/send/EmailChannelSenderAddressSourceTest.java`, new `marketing/send/MarketingEmailLegalAssertionsTest.java`; `marketing/ResendWebhookProjectorTest.java` (open/click tests now assert memberships unchanged); `marketing/render/CampaignEmailRendererTest.java` (source line rendering)

## Ordered steps

1. Baseline `./mvnw test` on the untouched worktree.
2. Health indicator with a protected network seam (`fetchTracking`) and a static JSON parser; tests per branch with a subclass stub.
3. Projector: remove both membership calls; flip the two existing tests to assert the membership stays unchanged while the recipient row is stamped.
4. Renderer overload with `addressSource`; footer HTML line + text line, escaped and single-lined.
5. `AddressSourceLines` + two repository queries; wire into `EmailChannelSender`.
6. Legal assertion tests (identical links, unsubscribe link + both headers on the real send path).
7. `./mvnw test`.

## Verification commands

- `cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-4-tracking-off && ./mvnw test`

## Test impact

- Health: bean registered by default and boots with a blank key (UNKNOWN); removed by `management.health.resend-tracking.enabled=false`; TRACKING_ON open on; TRACKING_ON click on; TRACKING_ON one on + other unreported; UP both off; UNKNOWN blank key; UNKNOWN domain not in account; UNKNOWN fetch throws; UNKNOWN + interrupt flag kept on InterruptedException; UNKNOWN tracking fields absent; cache reuses within TTL; first probe UNKNOWN `pending` with one queued refresh (single-flight); stale verdict served while one background refresh runs; default refresher never blocks the probe on a slow Resend; parser reads snake_case booleans.
- Health endpoint (full context, status order/mapping read from `src/main/resources/application.yaml`): tracking on → `GET /actuator/health` 200, root UP, component `TRACKING_ON`; a real DOWN contributor → 503.
- Projector: opened → recipient `opened_at` set, membership `last_email_open` null; clicked likewise.
- Renderer: source line + processor sentence + privacy link present in HTML and text when given; processor sentence without link when no URL; absent when null/blank; HTML-escaped (platforms and URL).
- Sender: first email to an imported member has the line; second email (after a sent one) does not; non-imported member never; rejected provenance row never; another org's earlier send does not count; an accepted row from another org's import does not count; first email carries the privacy URL.
- Legal: two recipients' rendered HTML differ only in the unsubscribe URL; every email on the send path has footer unsubscribe link, `List-Unsubscribe` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click`.

## Live-test

Not needed: no endpoint or contract change; the health indicator must not be exercised against real Resend from here (no real keys).

## Contract impact

none

## i18n impact

none (email footer is English, as the existing footer; no webapp/public strings).

## Blast radius

- `/actuator/health` aggregate: the indicator reports `TRACKING_ON`, which is ranked below `UP` and mapped to 200, so tracking left on cannot make the endpoint 503 or fail a Railway healthcheck. The status config replaces Boot's default `http-mapping`, so `down`/`out-of-service` → 503 are restated there; the endpoint test guards both.
- Every imported member's first marketing email gains one footer line (source, IMIN's processor role, privacy-notice link).
- `memberships.last_email_open/click` stop changing for everyone (the webapp drawer still renders the frozen values until M3-R2/M3-R6).

## Risks

- Resend's GET domain response may not carry `open_tracking`/`click_tracking`; then the indicator stays `UNKNOWN` (honest) and never `UP`.
- Race: two campaigns sending to the same imported member in parallel batches could both carry the line. Harmless (the line is true).

## Definition of done

`./mvnw test` green; every branch above has a test; no boot-time Resend call; no new flags.

## Live-test evidence

n-a. Verification: baseline `./mvnw test` on untouched base green (exit 0); after the change `./mvnw test` → Tests run: 4369, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS. Round 1 fixes, rebased on origin/master 6050365c: `./mvnw test` → Tests run: 4503, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.

## Review rounds

(appended by /do-task)

### Round 1 → FIX_REQUIRED (fixes applied)
- HIGH: tracking on no longer reports DOWN; custom status `TRACKING_ON`, ordered `down,out-of-service,up,tracking-on,unknown`, mapped to 200 with down/out-of-service restated as 503 (a custom mapping drops Boot's defaults, found by the new endpoint test). ERROR log kept. Endpoint test: 200 + component TRACKING_ON; a real DOWN elsewhere still 503.
- MEDIUM: probe never blocks; cached verdict served, single-flight background refresh on a daemon thread, UNKNOWN `pending` before the first result. Tests for pending, single-flight, stale-while-refresh and a slow Resend.
- MEDIUM Art. 14: line now adds "IMIN processes it on the organizer's behalf" and links `<buyer-site>/legal/privacy` for retention and rights (HTML link, URL in text).
- LOW: interrupt flag restored; `findAcceptedByMembershipIds` scoped by org through `audience_imports` with a cross-org test; never-written `opened`/`clicked` dropped from the received-before query (`unsubscribed` kept, it is written); CLAUDE.md lines added.
- Programme plan: follow-up cards for footer localisation and the Resend domain field/scope check.
