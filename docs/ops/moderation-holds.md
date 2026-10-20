# Moderation holds (sales suspension, payout holds)

How imin staff suspend an organizer's ticket sales and/or hold their payouts while a report, breach or
authority order is reviewed (acceptable-use §4(3), organizer terms clause 6), and how to lift it.
Every call below is a production write: get Ivan's OK first.

## Before the first hold

- `IMIN_MODERATION_STAFF_USER_IDS` lists the `users.id` of staff allowed in (comma-separated). Blank means the
  endpoints answer 404 to everyone. A malformed id stops the API from booting: copy ids from `users.id`.
- Do not set the list in production until the notice emails and the dashboard banner are live too.
  A hold the organizer is never told about breaks the terms (notice with reason, §4(5)).
- `IMIN_MODERATION_APPEAL_EMAIL` (default `hello@imin.wtf`) is the appeal address the notice names.

## Payout holds need manual payouts

A payout hold is refused (409 `INVALID_STATE`) unless both the platform (`STRIPE_PAYOUT_SCHEDULE_MANUAL=true`) and
the org (`organizations.stripe_payout_schedule_manual = true`) are on manual payouts: otherwise Stripe pays the
balance out on its own schedule and the hold would promise something imin cannot do. To get an org there:

1. Check `select stripe_payout_schedule_manual, stripe_payouts_enabled, stripe_connect_state from organizations where id = '<org uuid>';`
2. If it is false while the platform flag is on, `PayoutScheduleBackfillSweeper` flips payout-eligible accounts to
   manual every 10 minutes; wait for one tick and re-check. An account that is not payout-eligible yet has no
   payouts to hold.
3. If you cannot wait, pause payouts for that connected account in the Stripe Dashboard, place a sales-only hold
   now, and add the payout hold once the flag reads true.

## Your token

Log in to the dashboard (`https://dashboard.imin.wtf`) with your own staff account, open the browser console and
run `localStorage.getItem('imin.token')`. Use it as `TOKEN` below; it expires like any dashboard session.

```bash
API=https://api.imin.wtf/api/v1/internal/moderation/holds
```

## Place a hold

`orgId` is required; add `eventId` to hold one event instead of the whole org (future events included).
At least one of `suspendSales` / `holdPayouts` must be true. `ground` is one of `aup_breach`, `terms_breach`,
`fraud`, `safety`, `authority_order`, `reported_event`. `facts` (1–2000 characters, one language is fine) is shown
to the organizer word for word unless `reasonWithheld` is true (only when the law or an authority forbids telling them).

```bash
curl -sS -X POST "$API" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "orgId": "<org uuid>", "eventId": "<event uuid or omit>",
  "suspendSales": true, "holdPayouts": true,
  "ground": "reported_event", "facts": "<what was reported and what we are checking>",
  "reasonWithheld": false
}'
```

201 returns `{hold, warnings}`. Read the warning before you leave:

- `payout_in_flight` — a payout run is already PLANNED, SUBMITTED or RETRYING. A submitted payout cannot be
  recalled; a planned or retrying one whose answer was lost is settled from Stripe's payout list (adopted if Stripe
  made it, else failed) and never sent again while the hold is on. Check it in Stripe.

What the hold does: quote, hosted checkout (paid and free) and the app's payment intent answer 409
`SALES_SUSPENDED`; the event leaves the public listing and facets; its page serves `salesSuspended: true` with no
tiers; release emails wait; the organizer's marketing campaigns are not sent (org hold: all of them; event hold:
those for that event; any measure) and stay scheduled. The notice tells the organizer so. Tickets already sold, orders, refunds and door scanning are unchanged.
A checkout already open at Stripe can still complete for up to `STRIPE_CHECKOUT_SESSION_TTL_MINUTES` (30).
The 03:00 payout run skips held events and writes nothing; recoveries owed to imin still run.

## Check a hold and its notice

```bash
curl -sS "$API?orgId=<org uuid>" -H "Authorization: Bearer $TOKEN"
```

Newest first, lifted ones included. `noticeSentAt` (and `liftNoticeSentAt` after a lift) is set once the email to
`organizations.contact_email` went out; null means it was not sent (no contact email, or the send failed: tell the
organizer another way and note it).

## Lift a hold

```bash
curl -sS -X POST "$API/<hold id>/lift" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"note": "<why it ends, at most 1000 characters>"}'
```

200 with the closed hold; 409 `INVALID_STATE` when it was already lifted (the first lifter is kept).

Before you lift, look at the org's waiting campaigns: once no hold covers them the dispatcher sends them on its next
pass, with no check of their date, so a "last chance" email scheduled for a date long gone goes out as it is.

```sql
select id, name, status, scheduled_at, event_id from campaigns
 where org_id = '<org uuid>' and channel = 'email'
   and status in ('scheduled', 'sending') order by scheduled_at;
```

Ask the organizer to cancel or reschedule stale `scheduled` ones first (the lift notice also asks them to check).
Organizers can cancel only `scheduled` and `sending` campaigns (`POST /api/v1/marketing/campaigns/{id}/cancel`,
409 from any other status). A `failed` campaign is never retried by the dispatcher; it waits for the organizer's Retry and needs nothing here. Lifting closes
that one hold only: other holds, open disputes, open refunds and a cancelled event keep blocking as before.
Payouts restart from the next 03:00 run if the usual payout conditions are met.

## Review dates

- Review every active hold at day 60 and decide: lift, or record why it continues.
- Funds held at Stripe for ~90 days start to be a problem (Stripe's balance retention); act before day 75.
- The hold row is the audit trail (who, when, why, notice stamps). It is never deleted, also after the org is.
