# No codes in human-readable server text (2026-10-09)

Owner rule: the only human reference to an order is `OrderNumber.display(orderId)`. No ticket ids/tokens, full order UUIDs, refund/dispute/request ids as text.

## Audit result (grep of every `Notification` writer, `ApiException` message, audit summary, Stripe description, push/SMS/ICS/wallet text)

Already clean (no change): `DisputeNotifier` (dispute.opened), `OrganizerPayoutNotifier`, `SalesMilestoneNotifier`, `MomentumNotifier`, `ReforecastAlertNotifier` in-app rows; push in `NotifyReleaseSender.java:253`; wallet pass fields `AppleWalletPassService.java:269-316`; all order/ticket/refund `ApiException` messages (`RefundService`, `RefundRequestService`, `PublicOrder*`). Refund-request creation writes no Notification row.

## Surfaces changed

| # | file:line | before | after |
|---|---|---|---|
| 1 | `controller/event/TicketRedeemController.java:127` audit summary (visible via `GET /org/audit`) | `event <uuid>, ticket <uuid>, session, actor` | `order #xxxxxxxx, session, actor` (target ticket id stays in the machine `targetId` column) |
| 2 | `marketing/service/CampaignService.java:305` audit summary | `Duplicated from <campaign uuid>` | `Duplicated from "<campaign name>"` |
| 3 | `audience/service/DsarService.java:379` audit summary | `DSAR erase executed — org=<uuid>` | `DSAR erase executed` |
| 4 | `service/event/TicketTierService.java:220-221` ApiException message + field text | `Tier id <uuid> does not belong to event <uuid>` | `This tier does not belong to this event` |
| 5 | `controller/publicapi/PublicTicketAssetController.java:128` pkpass download filename the buyer sees | `imin-ticket-<ticket token>.pkpass` | `imin-ticket.pkpass` (helper `safeFilenamePart` removed) |
| 6 | `payout/PostEventPayoutService.java:452` Stripe payout description (shown on the organizer's bank statement) | `imin event payout <event uuid>` | `imin payout · <event name>`, name cut so the string is <=250 chars (event_id stays in metadata); frozen on the run in payout_runs.stripe_description (V180) so a replay after an event rename sends identical params |

## Left alone, with reason
- URLs carrying `order.getToken()`/`ticket.getToken()` (emails, wallet "Manage this ticket"): functional links, not displayed text.
- `RefundService.java:180` `ticketId` in the details map, JSON ids: machine fields.
- Gate audit summary keeps the gate session id (forensic "which door", not an order/ticket reference).
- Logs: no tokens found.

## Tests
- `TicketRedeemGateAuthTest`: summary has `OrderNumber.display(order)`, no ticket id/token/event id (red on base).
- `PublicTicketAssetControllerWalletTest`: filename has no token (updated pin).
- CampaignService duplicate and tier-mismatch message: one assertion each in existing test classes if present; payout description / DSAR text are single-string edits with no test class owning them (not tested per CLAUDE.md "constant echoes").
