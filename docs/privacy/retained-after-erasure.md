# What survives an Art.17 erasure

Last reviewed: 2026-09-08. Authoritative for what `DsarService.executeErase` and
`BuyerAccountErasureService.erase` actually do — if the code and this file
disagree, the code is the bug.

An erasure request is not a licence to destroy records we are separately obliged
to keep. GDPR Art.17(3)(b) and (e) carve out processing required by law and the
establishment or defence of legal claims; in France, Code de commerce L123-22
requires accounting records to be kept for ten years, and a ticket sale is an
accounting record. So the honest answer to a data subject is not "everything is
gone" — it is this list.

## Erased

| Data | Where | What happens |
|---|---|---|
| Audience membership | `memberships` | Row deleted |
| Platform identity | `consumers` | Row deleted when no membership and no verified buyer-account address still anchors it |
| Consent trail | `consent_records` | Deleted (FK cascade from the membership) |
| Marketing suppression | `suppression_entries` | Deleted |
| Campaign recipient PII | `campaign_recipients` | Name/address nulled; status and skip reason kept as an anonymous delivery record |
| "Notify me" registrations | `notify_subscriptions` | Deleted (this org's rows; all orgs' rows for a buyer-account erasure) |
| Funnel beacons | `event_funnel_events` | Deleted — matched through `orders.anon_id`, which is what makes them personal data rather than audience measurement |
| Buyer account, sessions, identities, codes, reset tokens, saved events, notification preferences, push devices | `buyer_*` | Deleted (buyer-account erasure only) |

## Retained, and why

| Data | Where | Basis |
|---|---|---|
| Orders, including the buyer email | `orders` | Accounting record of a sale — Art.17(3)(b), Code de commerce L123-22 (10 years). The email is on the invoice; removing it would break the record it is part of. |
| Tickets | `tickets` | The proof of what was sold and whether it was used — same basis, and the counterpart to a refund or chargeback claim. |
| Meta CAPI send log | `meta_capi_events` | Row kept as evidence that a disclosure to Meta happened; `email_sha256`, `fbp` and `fbc` are nulled, so the row records that a send occurred without identifying who. |
| Audit trail | `audit_logs` | Deliberately immutable, and it is the proof the erasure itself was performed. `actor_email` is nulled; the action, target and timestamp remain. |
| Erasure ledger | `erased_addresses` | The normalized address only (V99). It exists solely so the nightly `AudienceBackfillJob` does not rebuild an audience profile from the retained orders — keeping it is what makes the erasure stick. |
| Marketing opt-out | `marketing_optouts` | Kept deliberately for an org-scoped erasure: dropping it would let the next purchase re-subscribe someone who had opted out. Removed for a buyer-account erasure, where the account itself is going, and by the retention purge 3 years after `created_at`. |

## Retention purge (3 years)

`AudienceRetentionPurgeJob` (04:15 Europe/Paris, behind `IMIN_AUDIENCE_RETENTION_PURGE_ENABLED`, default off)
applies the privacy policy's 3-year rule for every org. The cutoff is today in the org's timezone minus 1095 days.

| What | Removed | Kept |
|---|---|---|
| Membership with no contact since the cutoff (newest of own purchase or attendance, contact from the person, any order of the org for the address, a person-sourced email consent, an imported last purchase date; else its creation date) | The membership, its consent records and confirmation tokens, marketing suppressions, fan features, import provenance, experiment assignments; the consumer when no other membership or verified buyer-account address uses it. Queued recipient rows of the member are skipped with reason `retention_purged`, and all its rows lose `email` and `phone_e164`. | Orders, tickets, `notify_subscriptions`. No `erased_addresses` row is written: a new purchase or sign-up starts a new membership. |
| `marketing_optouts` | Rows created before the cutoff | Newer rows |
| `campaign_recipients` | `email`, `phone_e164` of non-pending rows of `sent`/`canceled`/`failed` campaigns that ended before the cutoff | Status, skip reason and timestamps (anonymous aggregate); pending rows |
| `audit_logs` | Nothing | One `AUDIENCE_RETENTION_PURGED` row per org per run with counts only |

The nightly `AudienceBackfillJob` skips an (org, address) whose newest order is older than the cutoff, and never
restores a checkout consent from such an order, so the purge is not undone from the retained orders.

## Known gaps

- **Organizer in-app notifications (`notifications`) are out of scope.** That
  table is keyed by `users.id` — an organizer account — and an audience member is
  never a `users` row. An organizer's own notifications are governed by their
  staff account, not by an audience DSAR run against an address that happens to
  match. Their `actor_email` in `audit_logs` is redacted, which is the part that
  identifies them.
- **No buyer-facing self-service DSAR route.** A guest buyer with no imin
  account must still go through the organizer. Buyer-account holders have
  `BuyerAccountErasureService`; guests do not. Tracked separately.
