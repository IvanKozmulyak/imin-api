-- V88__backfill_marketing_optouts.sql
-- Backfill the sticky opt-out table for unsubscribes that predate it.
--
-- WHY THIS EXISTS. marketing_optouts was created empty by V85 on 2026-08-12,
-- and the write path that fills it (ConsentService.recordStickyOptOut, gated on
-- ConsentOrigin.DATA_SUBJECT) landed the same day. But the endpoints that let a
-- buyer unsubscribe themselves — the RFC 8058 one-click header and the footer
-- link in PublicUnsubscribeController — have been live since the Marketing
-- Campaigns epic in July. Every buyer who used one in that window has
-- memberships.consent_status = 'unsubscribed' and NO sticky row.
--
-- Without this backfill, the Release-2 account toggle (§4.4) reads those
-- memberships as "unsubscribed, nothing pinning them" and re-subscribes them
-- the first time somebody switches organizer updates ON — appending an
-- immutable consent_records row asserting lawful_basis = 'explicit' with proof
-- text claiming they consented to that organizer by name. That is precisely the
-- consent laundering the whole design exists to prevent; the sticky table was
-- shipped early to buy coverage, and it bought one day rather than the history.
--
-- WHAT IT SELECTS. Only unsubscribes whose source is one the data subject
-- themselves drove. ConsentOrigin is not stored on consent_records — only the
-- source string is — so this is the one place an allow-list over source is
-- unavoidable. It is deliberately narrow: an organizer tidying their own list
-- (ConsentOrigin.OPERATOR) must NOT be pinned shut, because that would bar the
-- buyer from ever opting back in through their own preference centre.
--
--   one_click    RFC 8058 header, the recipient's own mail client
--   footer_link  the buyer followed the unsubscribe link themselves
--   dsar_object  an Art. 21 objection, recorded on their behalf but theirs
--
-- SMS STOP is deliberately absent. Its source string is passed through from the
-- inbound webhook and is not a fixed vocabulary — that is the documented reason
-- stickiness cannot be inferred from source at all (§16 / ConsentOrigin). A STOP
-- sender is suppressed on the sms channel, which this email-channel toggle never
-- reads, so nothing is lost by leaving those rows out rather than guessing.
--
-- IDEMPOTENT AND H2-SAFE. NOT EXISTS rather than ON CONFLICT, and a plain
-- GROUP BY rather than DISTINCT ON: the test suite runs this same script against
-- H2 in PostgreSQL mode, which has neither reliably. Re-running it inserts
-- nothing.

INSERT INTO marketing_optouts (email_normalized, org_id, channel, source, created_at)
SELECT c.normalized_email,
       m.org_id,
       cr.channel,
       -- MIN over a two-value set: whichever real source this membership used.
       -- Not a synthetic 'backfill' marker, because V85 fixes the vocabulary and
       -- every value here is one the buyer genuinely went through.
       MIN(cr.source),
       MIN(cr.occurred_at)
  FROM consent_records cr
  JOIN memberships m ON m.membership_id = cr.membership_id
  JOIN consumers   c ON c.consumer_id  = m.consumer_id
 WHERE cr.status = 'unsubscribed'
   AND cr.source IN ('one_click', 'footer_link', 'dsar_object')
   AND cr.channel = 'email'
   -- Still unsubscribed today. Somebody who unsubscribed in July and then
   -- deliberately re-subscribed through the organizer's own form in August is
   -- subscribed, and pinning them shut would be its own kind of wrong.
   AND m.consent_status = 'unsubscribed'
   AND NOT EXISTS (
         SELECT 1
           FROM marketing_optouts mo
          WHERE mo.email_normalized = c.normalized_email
            AND mo.org_id           = m.org_id
            AND mo.channel          = cr.channel)
 GROUP BY c.normalized_email, m.org_id, cr.channel;
