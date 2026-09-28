-- Outcome history for the audience-tool demo. LOCAL ONLY, run by seed.sh after the invitations were made through
-- the api. The api refuses invitations for an event that has started and sends nothing while sends-enabled=false, so
-- this script moves two invited events into the past, records their arm campaigns as sent (and the on-sale event's
-- launch arm), and adds the orders, tickets and door scans that followed. Every person is invented (@example.test).
\set ON_ERROR_STOP on

DO $$
BEGIN
  IF current_database() <> 'imin_demo' THEN
    RAISE EXCEPTION 'refusing to seed database %: this script only runs against imin_demo', current_database();
  END IF;
  IF NOT EXISTS (SELECT 1 FROM audience_experiments WHERE event_id = md5('imin-demo-event-29')::uuid) THEN
    RAISE EXCEPTION 'no invitations on the outcome events; run scripts/demo/seed.sh, not this file';
  END IF;
END $$;

BEGIN;
DO $$ BEGIN PERFORM setseed(0.2027); END $$;

-- 29 ends 11 days ago (d7 is due), 30 ends 3 days ago (d1 is due); 26 is the on-sale event (live phase).
CREATE TEMP TABLE demo_shift (event_id uuid PRIMARY KEY, idx int, shift interval);
INSERT INTO demo_shift
SELECT md5('imin-demo-event-' || idx)::uuid, idx, (ev.starts_at::date - (current_date - ago)) * interval '1 day'
FROM (VALUES (29, 11), (30, 3)) v(idx, ago)
JOIN events ev ON ev.id = md5('imin-demo-event-' || v.idx)::uuid;

UPDATE events ev SET starts_at = starts_at - s.shift, ends_at = ends_at - s.shift, on_sale_at = on_sale_at - s.shift,
                     published_at = published_at - s.shift, created_at = created_at - s.shift, status = 'PAST'
FROM demo_shift s WHERE s.event_id = ev.id;
UPDATE ticket_tiers t SET sale_starts_at = sale_starts_at - s.shift, sale_closes_at = sale_closes_at - s.shift
FROM demo_shift s WHERE s.event_id = t.event_id;
UPDATE audience_plans p SET created_at = created_at - s.shift, today_date = today_date - s.shift,
                            event_date = event_date - s.shift, launch_date = launch_date - s.shift,
                            d3_date = d3_date - s.shift
FROM demo_shift s WHERE s.event_id = p.event_id;
UPDATE audience_experiments e SET created_at = e.created_at - s.shift FROM demo_shift s WHERE s.event_id = e.event_id;
UPDATE audience_assignments a SET assigned_at = a.assigned_at - s.shift
FROM audience_experiments e JOIN demo_shift s ON s.event_id = e.event_id WHERE e.id = a.experiment_id;
UPDATE segments g SET created_at = g.created_at - s.shift, updated_at = g.updated_at - s.shift
FROM campaigns c JOIN demo_shift s ON s.event_id = c.event_id WHERE c.segment_id = g.id;
UPDATE campaigns c SET created_at = c.created_at - s.shift, updated_at = c.updated_at - s.shift
FROM demo_shift s WHERE s.event_id = c.event_id;

-- The on-sale event was invited two days ago; its launch arm went out then, its D-3 arm waits.
UPDATE audience_experiments SET created_at = now() - interval '2 days 3 hours'
WHERE event_id = md5('imin-demo-event-26')::uuid;
UPDATE audience_assignments a SET assigned_at = now() - interval '2 days 3 hours'
FROM audience_experiments e WHERE e.id = a.experiment_id AND e.event_id = md5('imin-demo-event-26')::uuid;
UPDATE campaigns SET created_at = now() - interval '2 days 3 hours', updated_at = now() - interval '2 days 3 hours'
WHERE event_id = md5('imin-demo-event-26')::uuid AND origin = 'audience_plan';

-- ---------------------------------------------------------------------------------------------
-- Sends. Launch: 10:00 Paris the day after the invitation (two hours ago for the on-sale event is
-- not quiet hours-safe, so it went out two days ago at the same 10:00 rule). D-3: 18:00 Paris three days before.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE demo_arm AS
SELECT e.id AS experiment_id, e.event_id, e.arm, e.campaign_id, ev.name AS event_name,
       ev.starts_at AT TIME ZONE 'UTC' AS starts_utc,
       (SELECT min(a.assigned_at) FROM audience_assignments a WHERE a.experiment_id = e.id) AS assigned_at
FROM audience_experiments e JOIN events ev ON ev.id = e.event_id
WHERE ev.org_id = 'dec0de00-0000-4000-8000-000000000001'
  AND e.event_id IN (md5('imin-demo-event-29')::uuid, md5('imin-demo-event-30')::uuid, md5('imin-demo-event-26')::uuid);

ALTER TABLE demo_arm ADD COLUMN send_at timestamptz;
UPDATE demo_arm SET send_at = CASE
    WHEN arm = 'launch' THEN (date_trunc('day', assigned_at AT TIME ZONE 'Europe/Paris') + interval '1 day 10 hours')
                             AT TIME ZONE 'Europe/Paris'
    WHEN arm = 'd3' THEN ((starts_utc AT TIME ZONE 'Europe/Paris')::date - 3 + time '18:00') AT TIME ZONE 'Europe/Paris'
  END
WHERE arm <> 'holdout';
-- The on-sale event's launch went out two days ago; "tomorrow 10:00" would still be ahead.
UPDATE demo_arm SET send_at = (date_trunc('day', (now() - interval '2 days') AT TIME ZONE 'Europe/Paris')
                               + interval '10 hours') AT TIME ZONE 'Europe/Paris'
WHERE event_id = md5('imin-demo-event-26')::uuid AND arm = 'launch';

-- Sent = before now. The on-sale event's D-3 wave is still ahead, so it stays scheduled (nextWave).
INSERT INTO campaign_recipients (id, campaign_id, membership_id, email, status, skip_reason, provider_message_id,
                                 delivered_at, last_event_at, attempt_count, error_code)
SELECT gen_random_uuid(), d.campaign_id, a.membership_id, c.normalized_email,
       CASE WHEN r.x < 0.02 THEN 'bounced' ELSE 'delivered' END, NULL,
       'demo-' || replace(gen_random_uuid()::text, '-', ''),
       CASE WHEN r.x < 0.02 THEN NULL ELSE d.send_at + interval '1 minute' END,
       d.send_at + interval '1 minute', 1,
       CASE WHEN r.x < 0.02 THEN 'soft_bounce' END
FROM demo_arm d
JOIN audience_assignments a ON a.experiment_id = d.experiment_id
JOIN memberships m ON m.membership_id = a.membership_id
JOIN consumers c ON c.consumer_id = m.consumer_id
CROSS JOIN LATERAL (SELECT random() + 0 * length(a.membership_id::text) AS x) r
WHERE d.arm <> 'holdout' AND d.campaign_id IS NOT NULL AND d.send_at < now();

UPDATE campaigns c SET
  status = CASE WHEN d.send_at < now() THEN 'sent' ELSE 'scheduled' END,
  scheduled_at = d.send_at,
  sent_at = CASE WHEN d.send_at < now() THEN d.send_at END,
  recipient_count = CASE WHEN d.send_at < now()
                         THEN (SELECT count(*) FROM campaign_recipients r WHERE r.campaign_id = c.id) END,
  excluded_count = CASE WHEN d.send_at < now() THEN 0 END,
  attempts = CASE WHEN d.send_at < now() THEN 1 ELSE 0 END,
  subject = d.event_name || CASE WHEN d.arm = 'launch' THEN ': tickets are on sale' ELSE ': three days to go' END,
  preheader = 'Demo campaign on invented guests.',
  body_md = 'Demo campaign. ' || d.event_name || CASE WHEN d.arm = 'launch' THEN ' is on sale now.'
                                                      ELSE ' is in three days; a few tickets are left.' END,
  updated_at = least(d.send_at, now())
FROM demo_arm d WHERE d.campaign_id = c.id AND d.arm <> 'holdout';

-- ---------------------------------------------------------------------------------------------
-- Orders after the invitation, near what the plan predicted: an emailed guest buys at the segment's planned mid rate
-- (x1.3 for launch), the holdout and bounced guests (who never got the email) at half of it.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE demo_buy (email text, event_id uuid, created_at timestamptz);

INSERT INTO demo_buy
SELECT c.normalized_email, d.event_id,
       CASE WHEN r.status = 'delivered'
            THEN d.send_at + (least(d.send_at + interval '6 days', least(now(), d.starts_utc - interval '3 hours'))
                              - d.send_at) * power(x.t, 2)
            ELSE a.assigned_at + (least(now(), d.starts_utc - interval '3 hours') - a.assigned_at) * x.t END
FROM demo_arm d
JOIN audience_assignments a ON a.experiment_id = d.experiment_id
JOIN memberships m ON m.membership_id = a.membership_id
JOIN consumers c ON c.consumer_id = m.consumer_id
LEFT JOIN audience_plan_segments ps ON ps.id = (SELECT e.plan_segment_id FROM audience_experiments e WHERE e.id = d.experiment_id)
LEFT JOIN campaign_recipients r ON r.campaign_id = d.campaign_id AND r.membership_id = a.membership_id
CROSS JOIN LATERAL (SELECT random() + 0 * length(a.membership_id::text) AS p,
                           random() + 0 * length(a.membership_id::text) AS t) x
WHERE x.p < coalesce(ps.rate_mid, 0.05)
            * CASE WHEN r.status = 'delivered' THEN CASE d.arm WHEN 'launch' THEN 1.3 ELSE 1.0 END ELSE 0.5 END
            -- The on-sale event is two days into its launch: only the first buyers are in.
            * CASE WHEN d.event_id = md5('imin-demo-event-26')::uuid THEN 0.6 ELSE 1 END;

-- Walk-up demand on the past events: guests nobody invited, and people new to Vechirka.
INSERT INTO demo_buy
SELECT c.normalized_email, s.event_id,
       (ev.on_sale_at AT TIME ZONE 'UTC') + ((ev.starts_at - ev.on_sale_at) - interval '3 hours') * random()
FROM demo_shift s JOIN events ev ON ev.id = s.event_id
CROSS JOIN LATERAL (
  SELECT c.normalized_email FROM memberships m JOIN consumers c ON c.consumer_id = m.consumer_id
  WHERE m.org_id = ev.org_id AND m.status <> 'erase_pending' AND c.normalized_email LIKE '%@example.test'
    AND NOT EXISTS (SELECT 1 FROM audience_assignments a JOIN audience_experiments e ON e.id = a.experiment_id
                    WHERE e.event_id = s.event_id AND a.membership_id = m.membership_id)
  ORDER BY md5(c.normalized_email || s.idx) LIMIT 35) c;

INSERT INTO demo_buy
SELECT 'new.guest.' || s.idx || '.' || lpad(g::text, 3, '0') || '@example.test', s.event_id,
       (ev.on_sale_at AT TIME ZONE 'UTC') + ((ev.starts_at - ev.on_sale_at) - interval '3 hours') * random()
FROM demo_shift s JOIN events ev ON ev.id = s.event_id
CROSS JOIN generate_series(1, CASE s.idx WHEN 29 THEN 70 ELSE 45 END) g;

CREATE TEMP TABLE demo_new_orders AS
SELECT gen_random_uuid() AS id, b.email, b.event_id, ev.org_id, b.created_at,
       CASE WHEN r.q < 0.6 THEN 1 WHEN r.q < 0.95 THEN 2 ELSE 3 END AS qty,
       (SELECT t.id FROM ticket_tiers t WHERE t.event_id = b.event_id
         ORDER BY t.sort_order OFFSET CASE WHEN r.tier < 0.4 THEN 0 ELSE 1 END LIMIT 1) AS tier_id
FROM demo_buy b JOIN events ev ON ev.id = b.event_id
CROSS JOIN LATERAL (SELECT random() + 0 * length(b.email) AS q, random() + 0 * length(b.email) AS tier) r;

INSERT INTO orders (id, token, event_id, org_id, email, email_normalized, total_minor, currency, payment_method,
                    stripe_session_id, stripe_payment_intent_id, application_fee_minor, created_at, buyer_locale,
                    terms_accepted_at, test_mode)
SELECT o.id, replace(gen_random_uuid()::text, '-', ''), o.event_id, o.org_id, o.email, o.email,
       o.qty * t.price_minor + round(o.qty * t.price_minor * 0.05) + 99 * o.qty, 'EUR', 'stripe',
       'cs_demo_' || replace(o.id::text, '-', ''), 'pi_demo_' || replace(o.id::text, '-', ''),
       round(o.qty * t.price_minor * 0.05) + 99 * o.qty, o.created_at, 'fr', o.created_at, false
FROM demo_new_orders o JOIN ticket_tiers t ON t.id = o.tier_id;

-- Door scans on the past events (about nine in ten tickets); the on-sale event's tickets are just issued.
INSERT INTO tickets (id, token, order_id, event_id, tier_id, tier_name, state, created_at, redeemed_at,
                     redeemed_by_user_id, price_minor)
SELECT gen_random_uuid(), replace(gen_random_uuid()::text, '-', ''), o.id, o.event_id, o.tier_id, t.name,
       CASE WHEN ev.starts_at < now() AT TIME ZONE 'UTC' AND random() + 0 * n < 0.88 THEN 'redeemed' ELSE 'issued' END,
       o.created_at, NULL, NULL, t.price_minor
FROM demo_new_orders o JOIN ticket_tiers t ON t.id = o.tier_id JOIN events ev ON ev.id = o.event_id
CROSS JOIN LATERAL generate_series(1, o.qty) n;

UPDATE tickets tk SET redeemed_at = (ev.starts_at AT TIME ZONE 'UTC') + interval '1 minute' * floor(60 + random() * 180),
                      redeemed_by_user_id = 'dec0de00-0000-4000-8000-000000000011'
FROM events ev, demo_new_orders o
WHERE o.id = tk.order_id AND ev.id = tk.event_id AND tk.state = 'redeemed';

-- Counters from the tickets that were not refunded.
UPDATE ticket_tiers t SET sold = s.sold, quantity = greatest(t.quantity, s.sold)
FROM (SELECT tier_id, count(*) AS sold FROM tickets WHERE state <> 'refunded' GROUP BY tier_id) s
WHERE s.tier_id = t.id AND t.event_id IN (SELECT DISTINCT event_id FROM demo_new_orders);
UPDATE events ev SET sold = s.sold, revenue_minor = s.revenue
FROM (SELECT event_id, count(*) AS sold, sum(price_minor) AS revenue FROM tickets WHERE state <> 'refunded'
      GROUP BY event_id) s
WHERE s.event_id = ev.id AND ev.id IN (SELECT DISTINCT event_id FROM demo_new_orders);

COMMIT;

-- Summary for the seed log.
SELECT ev.name, e.arm, count(DISTINCT a.membership_id) AS members,
       count(DISTINCT r.membership_id) FILTER (WHERE r.status = 'delivered') AS delivered,
       count(DISTINCT o.email) AS bought
FROM audience_experiments e JOIN events ev ON ev.id = e.event_id
JOIN audience_assignments a ON a.experiment_id = e.id
JOIN memberships m ON m.membership_id = a.membership_id JOIN consumers c ON c.consumer_id = m.consumer_id
LEFT JOIN campaign_recipients r ON r.campaign_id = e.campaign_id AND r.membership_id = a.membership_id
LEFT JOIN demo_new_orders o ON o.event_id = e.event_id AND o.email = c.normalized_email
WHERE ev.org_id = 'dec0de00-0000-4000-8000-000000000001'
GROUP BY ev.name, ev.starts_at, e.arm ORDER BY ev.starts_at, e.arm;
