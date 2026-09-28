-- Local demo data for the audience tool: one warm organizer ("Vechirka", Metz) and one cold one ("Salle Obscure").
-- LOCAL ONLY. Refuses to run outside the imin_demo database. Run through scripts/demo/seed.sh, not by hand:
-- the schema must already be migrated by the app, and fan features / plans are computed by the app afterwards.
-- Every person here is invented; every address is @example.test.
\set ON_ERROR_STOP on

DO $$
BEGIN
  IF current_database() <> 'imin_demo' THEN
    RAISE EXCEPTION 'refusing to seed database %: this script only runs against imin_demo', current_database();
  END IF;
  IF EXISTS (SELECT 1 FROM organizations WHERE slug = 'vechirka-demo') THEN
    RAISE EXCEPTION 'imin_demo is already seeded; run scripts/demo/seed.sh to drop and recreate it';
  END IF;
END $$;

-- Password hashing for the two demo logins (bcrypt, which Spring's BCryptPasswordEncoder verifies).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

BEGIN;

-- Same random() stream on every run, so counts are stable; ids still differ run to run.
DO $$ BEGIN PERFORM setseed(0.2026); END $$;

-- ---------------------------------------------------------------------------------------------
-- Organizations and owner logins
-- ---------------------------------------------------------------------------------------------
INSERT INTO organizations (id, name, contact_email, country, timezone, slug, brand_name, legal_name, legal_contact)
VALUES
  ('dec0de00-0000-4000-8000-000000000001', 'Vechirka', 'vechirka@example.test', 'FR', 'Europe/Paris',
   'vechirka-demo', 'Vechirka', 'Vechirka Collectif (association loi 1901, demo)', 'vechirka-legal@example.test'),
  ('dec0de00-0000-4000-8000-000000000002', 'Salle Obscure', 'salle-obscure@example.test', 'FR', 'Europe/Paris',
   'salle-obscure-demo', 'Salle Obscure', 'Salle Obscure SAS (demo)', 'salle-obscure-legal@example.test');

INSERT INTO users (id, org_id, email, email_lower, password_hash, role, avatar_initials, verified_at,
                   first_name, last_name, locale, terms_accepted_at, terms_version, created_at)
VALUES
  ('dec0de00-0000-4000-8000-000000000011', 'dec0de00-0000-4000-8000-000000000001', 'demo@imin.test',
   'demo@imin.test', crypt('VechirkaDemo2026!', gen_salt('bf', 12)), 'OWNER', 'DV', now() - interval '730 days',
   'Demo', 'Vechirka', 'en', now() - interval '730 days', 'demo', now() - interval '730 days'),
  ('dec0de00-0000-4000-8000-000000000012', 'dec0de00-0000-4000-8000-000000000002', 'demo-cold@imin.test',
   'demo-cold@imin.test', crypt('ObscureDemo2026!', gen_salt('bf', 12)), 'OWNER', 'SO', now() - interval '60 days',
   'Demo', 'Obscure', 'en', now() - interval '60 days', 'demo', now() - interval '60 days');

-- ---------------------------------------------------------------------------------------------
-- Events: ago > 0 = days in the past, ago < 0 = days ahead. Dates are relative to today, so a re-seed
-- next month yields the same guest classes.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE demo_events (
  idx int PRIMARY KEY, id uuid NOT NULL DEFAULT gen_random_uuid(), org_id uuid NOT NULL,
  name text NOT NULL, genre_key text NOT NULL, type text NOT NULL, place text NOT NULL, ago int NOT NULL,
  status text NOT NULL, p1 int NOT NULL, p2 int NOT NULL, p3 int, q1 int, q2 int, q3 int
);

INSERT INTO demo_events (idx, org_id, name, genre_key, type, place, ago, status, p1, p2, p3, q1, q2, q3) VALUES
  ( 1, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka #1: Première',       'house & techno',     'Club',      'metz_club',   680, 'PAST', 1000, 1400, NULL, NULL, NULL, NULL),
  ( 2, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka #2',                 'house & techno',     'Club',      'metz_club',   610, 'PAST', 1000, 1500, NULL, NULL, NULL, NULL),
  ( 3, 'dec0de00-0000-4000-8000-000000000001', 'Open Format Nancy',           'club / open format', 'Club',      'nancy',       575, 'PAST', 1000, 1500, NULL, NULL, NULL, NULL),
  ( 4, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka Warehouse',          'house & techno',     'Warehouse', 'metz_hangar', 540, 'PAST', 1200, 1800, NULL, NULL, NULL, NULL),
  ( 5, 'dec0de00-0000-4000-8000-000000000001', 'Bassline Thionville',         'bass & hard dance',  'Rave',      'thionville',  505, 'PAST', 1200, 1600, NULL, NULL, NULL, NULL),
  ( 6, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka #6: Deep Winter',    'house & techno',     'Club',      'metz_club',   470, 'PAST', 1200, 1600, NULL, NULL, NULL, NULL),
  ( 7, 'dec0de00-0000-4000-8000-000000000001', 'Block Party Nancy',           'hip-hop & r&b',      'Club',      'nancy',       435, 'PAST', 1000, 1500, NULL, NULL, NULL, NULL),
  ( 8, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka Open Air',           'house & techno',     'Open Air',  'metz_lake',   400, 'PAST',  900, 1400, NULL, NULL, NULL, NULL),
  ( 9, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka: One Year',          'house & techno',     'Club',      'metz_club',   365, 'PAST', 1200, 1800, NULL, NULL, NULL, NULL),
  (10, 'dec0de00-0000-4000-8000-000000000001', 'Sunday Jazz Session',         'jazz & acoustic',    'Concert',   'metz_club',   337, 'PAST',  800, 1200, NULL, NULL, NULL, NULL),
  (11, 'dec0de00-0000-4000-8000-000000000001', 'Open Format Metz',            'club / open format', 'Club',      'metz_club',   309, 'PAST', 1000, 1500, NULL, NULL, NULL, NULL),
  (12, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka Nancy',              'house & techno',     'Club',      'nancy',       281, 'PAST', 1200, 1600, NULL, NULL, NULL, NULL),
  (13, 'dec0de00-0000-4000-8000-000000000001', 'Afro Latin Night',            'latin & afrobeats',  'Club',      'metz_club',   253, 'PAST', 1000, 1500, NULL, NULL, NULL, NULL),
  (14, 'dec0de00-0000-4000-8000-000000000001', 'Warehouse Thionville',        'house & techno',     'Warehouse', 'thionville',  225, 'PAST', 1200, 1800, NULL, NULL, NULL, NULL),
  (15, 'dec0de00-0000-4000-8000-000000000001', 'Hard Dance Metz',             'bass & hard dance',  'Rave',      'metz_hangar', 197, 'PAST', 1200, 1800, NULL, NULL, NULL, NULL),
  (16, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka #16: Spring',        'house & techno',     'Club',      'metz_club',   169, 'PAST', 1200, 1600, NULL, NULL, NULL, NULL),
  (17, 'dec0de00-0000-4000-8000-000000000001', 'Pop Rave Nancy',              'pop',                'Club',      'nancy',       148, 'PAST', 1000, 1400, NULL, NULL, NULL, NULL),
  (18, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka Open Air II',        'house & techno',     'Open Air',  'metz_lake',   120, 'PAST', 1000, 1500, NULL, NULL, NULL, NULL),
  (19, 'dec0de00-0000-4000-8000-000000000001', 'Indie Night Thionville',      'rock & alternative', 'Concert',   'thionville',   99, 'PAST', 1000, 1400, NULL, NULL, NULL, NULL),
  (20, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka #20: Summer',        'house & techno',     'Club',      'metz_club',    78, 'PAST', 1200, 1800, NULL, NULL, NULL, NULL),
  (21, 'dec0de00-0000-4000-8000-000000000001', 'Open Format Summer',          'club / open format', 'Club',      'nancy',        57, 'PAST', 1000, 1500, NULL, NULL, NULL, NULL),
  (22, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka Open Air III',       'house & techno',     'Open Air',  'metz_lake',    43, 'PAST', 1000, 1600, NULL, NULL, NULL, NULL),
  (23, 'dec0de00-0000-4000-8000-000000000001', 'Hard Dance Metz II',          'bass & hard dance',  'Rave',      'metz_hangar',  29, 'PAST', 1200, 1800, NULL, NULL, NULL, NULL),
  (24, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka #24',                'house & techno',     'Club',      'metz_club',    15, 'PAST', 1200, 1800, NULL, NULL, NULL, NULL),
  -- Door QR and survey are switched on for this one (see below).
  (25, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka: Rentrée',           'house & techno',     'Club',      'metz_club',     8, 'PAST', 1200, 1800, NULL, NULL, NULL, NULL),
  -- Upcoming: live at 4 weeks, live at ~11 weeks with a big target (gap beyond the local crowd), and a draft.
  (26, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka: Nuit Longue',       'house & techno',     'Club',      'metz_club',   -28, 'LIVE', 1400, 1800, 2200,  50,  70, 30),
  (27, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka Winter Festival',    'house & techno',     'Festival',  'metz_expo',   -75, 'LIVE', 2500, 3200, 3900, 400, 900, 500),
  (28, 'dec0de00-0000-4000-8000-000000000001', 'Bass Session #4',             'bass & hard dance',  'Rave',      'nancy',       -50, 'DRAFT', 1200, 1600, NULL, 150, 250, NULL),
  -- Outcome demo: seeded upcoming so invitations go through the api, then moved into the past by
  -- seed-audience-outcomes.sql (29 ends 11 days ago = d7 phase, 30 ends 3 days ago = d1 phase).
  -- A negative ago keeps them out of every order-history pick above, so guest classes do not move.
  (29, 'dec0de00-0000-4000-8000-000000000001', 'Vechirka: Late Summer Session','house & techno',    'Club',      'metz_club',   -30, 'LIVE', 1200, 1600, NULL, 120, 180, NULL),
  (30, 'dec0de00-0000-4000-8000-000000000001', 'Bassline Thionville II',      'bass & hard dance',  'Rave',      'thionville',  -30, 'LIVE', 1000, 1400, NULL, 100, 150, NULL),
  -- Cold organizer: one small past night, one upcoming, no consented guests at all.
  (41, 'dec0de00-0000-4000-8000-000000000002', 'Salle Obscure: Late Jazz',    'jazz & acoustic',    'Concert',   'nancy_jazz',   40, 'PAST', 1500, 1500, NULL, NULL, NULL, NULL),
  (42, 'dec0de00-0000-4000-8000-000000000002', 'Salle Obscure: Autumn Trio',  'jazz & acoustic',    'Concert',   'nancy_jazz',  -21, 'LIVE', 1500, 2000, NULL, 80, 120, NULL);

-- Stable event ids, so the demo URLs in README.md survive a re-seed.
UPDATE demo_events SET id = md5('imin-demo-event-' || idx)::uuid;

CREATE TEMP TABLE demo_places (place text PRIMARY KEY, venue text, street text, city text, postal text, lat float8, lon float8);
INSERT INTO demo_places VALUES
  ('metz_club',   'La Friche 57',          '12 Rue des Jardins',        'Metz',       '57000', 49.1193, 6.1757),
  ('metz_hangar', 'Hangar Sablon',         '4 Rue de la Gare',          'Metz',       '57000', 49.1005, 6.1850),
  ('metz_lake',   'Plan d''Eau',           'Allée du Plan d''Eau',      'Metz',       '57000', 49.1150, 6.1640),
  ('metz_expo',   'Hall Grigy',            '1 Rue de la Grange aux Bois','Metz',      '57070', 49.0870, 6.2180),
  ('nancy',       'Le Bloc',               '8 Rue Saint-Georges',       'Nancy',      '54000', 48.6921, 6.1844),
  ('nancy_jazz',  'Salle Obscure',         '3 Rue des Carmes',          'Nancy',      '54000', 48.6905, 6.1810),
  ('thionville',  'Salle du Beffroi',      '2 Place du Marché',         'Thionville', '57100', 49.3580, 6.1680);

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
                    venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country,
                    venue_latitude, venue_longitude, description, currency, on_sale_at, created_by,
                    created_at, updated_at, published_at)
SELECT e.id, e.org_id, e.name,
       'demo-' || e.idx || '-' || regexp_replace(lower(translate(e.name, 'éèàç', 'eeac')), '[^a-z0-9]+', '-', 'g'),
       'PUBLIC', e.status,
       CASE e.genre_key
         WHEN 'house & techno' THEN 'House & Techno' WHEN 'bass & hard dance' THEN 'Bass & Hard Dance'
         WHEN 'club / open format' THEN 'Club / Open Format' WHEN 'hip-hop & r&b' THEN 'Hip-Hop & R&B'
         WHEN 'latin & afrobeats' THEN 'Latin & Afrobeats' WHEN 'rock & alternative' THEN 'Rock & Alternative'
         WHEN 'pop' THEN 'Pop' ELSE 'Jazz & Acoustic' END,
       e.genre_key, e.type,
       (current_date - e.ago) + time '20:00',
       (current_date - e.ago) + time '20:00' + interval '7 hours',
       'Europe/Paris', p.venue, p.street, p.city, lower(p.city), p.postal, 'FR', p.lat, p.lon,
       'Demo event. ' || e.name || ' at ' || p.venue || ', ' || p.city || '.', 'EUR',
       CASE WHEN e.status = 'DRAFT' THEN NULL
            WHEN e.ago < 0 THEN now()::timestamp - interval '12 days' * (1 + (e.idx = 27)::int)
            ELSE (current_date - e.ago - 35) + time '12:00' END,
       CASE WHEN e.org_id = 'dec0de00-0000-4000-8000-000000000001' THEN 'dec0de00-0000-4000-8000-000000000011'::uuid
            ELSE 'dec0de00-0000-4000-8000-000000000012'::uuid END,
       (current_date - greatest(e.ago, 0) - 40)::timestamp, now()::timestamp,
       CASE WHEN e.status = 'DRAFT' THEN NULL
            WHEN e.ago < 0 THEN now()::timestamp - interval '13 days' * (1 + (e.idx = 27)::int)
            ELSE (current_date - e.ago - 36) + time '12:00' END
FROM demo_events e JOIN demo_places p ON p.place = e.place;

-- Tiers: past events get their quantities fitted to what sold afterwards.
CREATE TEMP TABLE demo_tiers (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), event_idx int, sort int, name text, price int, qty int);
INSERT INTO demo_tiers (event_idx, sort, name, price, qty)
SELECT idx, 0, 'Early Bird', p1, coalesce(q1, 0) FROM demo_events
UNION ALL SELECT idx, 1, 'Standard', p2, coalesce(q2, 0) FROM demo_events
UNION ALL SELECT idx, 2, 'Late', p3, q3 FROM demo_events WHERE p3 IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- People. Persona decides the order history; the app derives the guest class from it.
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE demo_people (
  idx int PRIMARY KEY, org_id uuid NOT NULL, first text, last text, email text UNIQUE, persona text,
  fav text, flaky boolean, locale text, consent text
);

WITH names AS (
  SELECT ARRAY['Camille','Lucas','Chloé','Théo','Manon','Hugo','Léa','Nathan','Inès','Mathis','Sarah','Julien',
               'Clara','Antoine','Émilie','Maxime','Pauline','Romain','Océane','Quentin','Margaux','Baptiste',
               'Justine','Kévin','Élodie','Florian','Lucie','Thomas','Anaïs','Adrien'] AS fr_first,
         ARRAY['Martin','Bernard','Thomas','Petit','Robert','Richard','Durand','Dubois','Moreau','Laurent',
               'Simon','Michel','Lefebvre','Leroy','Roux','Fournier','Girard','Schmitt','Muller','Weber',
               'Klein','Meyer','Hoffmann','Wagner','Becker'] AS fr_last,
         ARRAY['Oksana','Taras','Mykola','Iryna','Bohdana','Andrii','Olena','Yaroslav','Dmytro','Sofiia',
               'Kateryna','Nazar','Marta','Ostap','Yuliia','Vasyl','Halyna','Roman','Svitlana','Maksym'] AS ua_first,
         ARRAY['Kovalenko','Shevchuk','Bondarenko','Tkachenko','Kravets','Melnyk','Hnatiuk','Lysenko',
               'Savchuk','Moroz','Oliinyk','Pavliuk','Boiko','Marchenko','Rudenko'] AS ua_last
), draws AS (
  SELECT g AS idx, random() < 0.3 AS ua, random() AS a, random() AS b, random() AS fav_r, random() AS flaky_r,
         random() AS loc_r
  FROM generate_series(1, 885) g
), named AS (
  SELECT d.*,
         CASE WHEN d.ua THEN n.ua_first[1 + floor(d.a * array_length(n.ua_first, 1))::int]
              ELSE n.fr_first[1 + floor(d.a * array_length(n.fr_first, 1))::int] END AS first,
         CASE WHEN d.ua THEN n.ua_last[1 + floor(d.b * array_length(n.ua_last, 1))::int]
              ELSE n.fr_last[1 + floor(d.b * array_length(n.fr_last, 1))::int] END AS last
  FROM draws d CROSS JOIN names n
)
INSERT INTO demo_people (idx, org_id, first, last, email, persona, fav, flaky, locale)
SELECT idx,
       CASE WHEN idx <= 825 THEN 'dec0de00-0000-4000-8000-000000000001'::uuid
            ELSE 'dec0de00-0000-4000-8000-000000000002'::uuid END,
       first, last,
       lower(translate(first, 'éèêëàâäîïôöùûüçÉ', 'eeeeaaaiioouuucE')) || '.'
         || lower(translate(last, 'éèêëàâäîïôöùûüç', 'eeeeaaaiioouuuc')) || '.' || idx || '@example.test',
       CASE WHEN idx <= 90 THEN 'loyal' WHEN idx <= 210 THEN 'repeat' WHEN idx <= 540 THEN 'first_timer'
            WHEN idx <= 630 THEN 'lapsing' WHEN idx <= 805 THEN 'dormant' WHEN idx <= 825 THEN 'refunded_only'
            ELSE 'cold_org' END,
       CASE WHEN fav_r < 0.60 THEN 'house & techno' WHEN fav_r < 0.75 THEN 'club / open format'
            WHEN fav_r < 0.85 THEN 'bass & hard dance' WHEN fav_r < 0.90 THEN 'hip-hop & r&b'
            WHEN fav_r < 0.94 THEN 'latin & afrobeats' WHEN fav_r < 0.97 THEN 'pop'
            WHEN fav_r < 0.99 THEN 'rock & alternative' ELSE 'jazz & acoustic' END,
       flaky_r < 0.10,
       CASE WHEN ua AND loc_r < 0.5 THEN 'uk' WHEN loc_r < 0.8 THEN 'fr' ELSE 'en' END
FROM named;

-- Checkout marketing consent: 'named' = the organizer-named sentence (plan-mailable), 'legacy' = an older
-- sentence without a version (the gate shows it as unproven). Only Vechirka buyers; the cold org has none.
UPDATE demo_people SET consent = CASE
    WHEN r < CASE persona WHEN 'loyal' THEN 0.70 WHEN 'repeat' THEN 0.65 WHEN 'first_timer' THEN 0.60
                          WHEN 'lapsing' THEN 0.30 WHEN 'dormant' THEN 0.20 ELSE 0 END THEN 'named'
    WHEN r > 0.92 AND persona NOT IN ('refunded_only', 'cold_org') THEN 'legacy'
    ELSE NULL END
FROM (SELECT idx AS i, random() AS r FROM demo_people) x WHERE x.i = demo_people.idx;

-- ---------------------------------------------------------------------------------------------
-- Orders and tickets
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE demo_orders (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(), person int, event_idx int, tier_id uuid, qty int, price int,
  created_at timestamptz, refunded boolean NOT NULL DEFAULT false
);

CREATE FUNCTION pg_temp.pick(p_org uuid, p_fav text, lo int, hi int, k int, p_not int[]) RETURNS int[]
LANGUAGE sql AS $$
  SELECT coalesce(array_agg(idx), '{}') FROM (
    SELECT idx FROM demo_events
     WHERE org_id = p_org AND ago BETWEEN lo AND hi AND NOT (idx = ANY (p_not))
     ORDER BY random() * CASE WHEN genre_key = p_fav THEN 0.35 ELSE 1 END
     LIMIT k) s
$$;

DO $$
DECLARE
  p record;
  chosen int[];
  ev int;
  r float8;
  q int;
  srt int;
  t record;
  e record;
BEGIN
  FOR p IN SELECT * FROM demo_people ORDER BY idx LOOP
    chosen := '{}';
    IF p.persona = 'loyal' THEN
      chosen := pg_temp.pick(p.org_id, p.fav, 1, 90, 1, chosen);
      chosen := chosen || pg_temp.pick(p.org_id, p.fav, 1, 9999, 2 + floor(random() * 5)::int, chosen);
    ELSIF p.persona = 'repeat' THEN
      chosen := pg_temp.pick(p.org_id, p.fav, 1, 90, 1, chosen);
      chosen := chosen || pg_temp.pick(p.org_id, p.fav, 1, 9999, 1, chosen);
    ELSIF p.persona IN ('first_timer', 'refunded_only', 'cold_org') THEN
      chosen := pg_temp.pick(p.org_id, p.fav, 1, 90, 1, chosen);
    ELSIF p.persona = 'lapsing' THEN
      chosen := pg_temp.pick(p.org_id, p.fav, 91, 180, 1, chosen);
      chosen := chosen || pg_temp.pick(p.org_id, p.fav, 181, 9999, floor(random() * 3)::int, chosen);
    ELSIF p.persona = 'dormant' THEN
      chosen := pg_temp.pick(p.org_id, p.fav, 181, 9999, 1 + floor(random() * 4)::int, chosen);
    END IF;

    FOREACH ev IN ARRAY chosen LOOP
      SELECT * INTO e FROM demo_events WHERE idx = ev;
      r := random();
      q := CASE WHEN r < 0.55 THEN 1 WHEN r < 0.85 THEN 2 WHEN r < 0.95 THEN 3 ELSE 4 END;
      -- Drawn once: random() inside the WHERE would be re-evaluated per row.
      srt := CASE WHEN random() < 0.4 THEN 0 ELSE 1 END;
      SELECT * INTO t FROM demo_tiers WHERE event_idx = ev AND sort = srt;
      INSERT INTO demo_orders (person, event_idx, tier_id, qty, price, created_at, refunded)
      VALUES (p.idx, ev, t.id, q, t.price,
              ((current_date - e.ago) + time '20:00') AT TIME ZONE 'UTC' - interval '1 hour' * floor(24 + random() * 24 * 27),
              p.persona = 'refunded_only');
    END LOOP;
  END LOOP;
END $$;

-- Tickets already bought for the upcoming live events (these guests are excluded from those plans as
-- "bought this event"). Nuit Longue: loyal and repeat guests; Winter Festival: loyal and first-timers.
INSERT INTO demo_orders (person, event_idx, tier_id, qty, price, created_at)
SELECT s.idx, s.ev, t.id, s.qty, t.price, now() - interval '1 hour' * floor(1 + random() * 24 * 11)
FROM (
  SELECT idx, 26 AS ev, CASE WHEN random() < 0.6 THEN 1 ELSE 2 END AS qty, CASE WHEN random() < 0.7 THEN 0 ELSE 1 END AS sort
    FROM (SELECT idx FROM demo_people WHERE persona = 'loyal' ORDER BY random() LIMIT 30) a
  UNION ALL
  SELECT idx, 26, CASE WHEN random() < 0.6 THEN 1 ELSE 2 END, CASE WHEN random() < 0.5 THEN 0 ELSE 1 END
    FROM (SELECT idx FROM demo_people WHERE persona = 'repeat' ORDER BY random() LIMIT 15) b
  UNION ALL
  SELECT idx, 27, CASE WHEN random() < 0.5 THEN 1 ELSE 2 END, 0
    FROM (SELECT idx FROM demo_people WHERE persona = 'loyal' ORDER BY random() LIMIT 25) c
  UNION ALL
  SELECT idx, 27, 1, 0
    FROM (SELECT idx FROM demo_people WHERE persona = 'first_timer' ORDER BY random() LIMIT 20) d
  UNION ALL
  SELECT idx, 42, 1, 0
    FROM (SELECT idx FROM demo_people WHERE persona = 'cold_org' ORDER BY random() LIMIT 12) f
) s JOIN demo_tiers t ON t.event_idx = s.ev AND t.sort = s.sort;

-- A few ordinary refunds on older nights.
UPDATE demo_orders SET refunded = true
WHERE id IN (SELECT o.id FROM demo_orders o JOIN demo_events e ON e.idx = o.event_idx
              WHERE e.ago > 90 ORDER BY random() LIMIT 30);

INSERT INTO orders (id, token, event_id, org_id, email, email_normalized, total_minor, currency, payment_method,
                    stripe_session_id, stripe_payment_intent_id, application_fee_minor, created_at, buyer_locale,
                    terms_accepted_at, test_mode)
SELECT o.id, replace(gen_random_uuid()::text, '-', ''), e.id, e.org_id, p.email, p.email,
       o.qty * o.price + round(o.qty * o.price * 0.05) + 99 * o.qty, 'EUR', 'stripe',
       'cs_demo_' || replace(o.id::text, '-', ''), 'pi_demo_' || replace(o.id::text, '-', ''),
       round(o.qty * o.price * 0.05) + 99 * o.qty, o.created_at, p.locale, o.created_at, false
FROM demo_orders o JOIN demo_people p ON p.idx = o.person JOIN demo_events e ON e.idx = o.event_idx;

-- Door scans: most tickets to past nights were scanned; "flaky" guests often did not turn up.
INSERT INTO tickets (id, token, order_id, event_id, tier_id, tier_name, state, created_at, redeemed_at,
                     redeemed_by_user_id, price_minor)
SELECT gen_random_uuid(), replace(gen_random_uuid()::text, '-', ''), o.id, e.id, o.tier_id, t.name,
       CASE WHEN o.refunded THEN 'refunded'
            WHEN e.ago <= 0 THEN 'issued'
            WHEN random() < CASE WHEN p.flaky THEN 0.5 ELSE 0.9 END THEN 'redeemed'
            ELSE 'issued' END,
       o.created_at, NULL,
       NULL, o.price
FROM demo_orders o
JOIN demo_events e ON e.idx = o.event_idx
JOIN demo_people p ON p.idx = o.person
JOIN demo_tiers t ON t.id = o.tier_id
CROSS JOIN LATERAL generate_series(1, o.qty) n;

UPDATE tickets tk SET redeemed_at = (ev.starts_at AT TIME ZONE 'UTC') + interval '1 minute' * floor(60 + random() * 180),
                      redeemed_by_user_id = CASE WHEN ev.org_id = 'dec0de00-0000-4000-8000-000000000001'
                                                 THEN 'dec0de00-0000-4000-8000-000000000011'::uuid
                                                 ELSE 'dec0de00-0000-4000-8000-000000000012'::uuid END
FROM events ev WHERE ev.id = tk.event_id AND tk.state = 'redeemed';

INSERT INTO refunds (id, order_id, stripe_refund_id, stripe_charge_id, stripe_payment_intent_id, amount_minor,
                     currency, application_fee_refund_minor, reason, status, initiated_by_user_id,
                     idempotency_key, created_at, updated_at)
SELECT gen_random_uuid(), o.id, 're_demo_' || replace(o.id::text, '-', ''), 'ch_demo_' || replace(o.id::text, '-', ''),
       ord.stripe_payment_intent_id, o.qty * o.price, 'EUR', 0, 'REQUESTED_BY_CUSTOMER', 'SUCCEEDED',
       CASE WHEN ord.org_id = 'dec0de00-0000-4000-8000-000000000001' THEN 'dec0de00-0000-4000-8000-000000000011'::uuid
            ELSE 'dec0de00-0000-4000-8000-000000000012'::uuid END,
       'demo-refund-' || o.id, o.created_at + interval '2 days', o.created_at + interval '2 days'
FROM demo_orders o JOIN orders ord ON ord.id = o.id WHERE o.refunded;

INSERT INTO refund_tickets (refund_id, ticket_id)
SELECT r.id, t.id FROM refunds r JOIN tickets t ON t.order_id = r.order_id
WHERE r.idempotency_key LIKE 'demo-refund-%';

-- Tier and event counters from the tickets that were not refunded; past tiers fitted to what sold.
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT t.id, e.id, t.name, t.price,
       greatest(t.qty, coalesce(s.sold, 0) + CASE WHEN e.ago > 0 THEN floor(random() * 30)::int ELSE 0 END),
       coalesce(s.sold, 0), true, t.sort, ev.on_sale_at
FROM demo_tiers t
JOIN demo_events e ON e.idx = t.event_idx
JOIN events ev ON ev.id = e.id
LEFT JOIN (SELECT tier_id, count(*) AS sold FROM tickets WHERE state <> 'refunded' GROUP BY tier_id) s ON s.tier_id = t.id;

UPDATE events ev SET sold = s.sold, revenue_minor = s.revenue
FROM (SELECT event_id, count(*) AS sold, sum(price_minor) AS revenue FROM tickets WHERE state <> 'refunded' GROUP BY event_id) s
WHERE s.event_id = ev.id;

-- ---------------------------------------------------------------------------------------------
-- Consumers and memberships for Vechirka (the app's startup backfill fills every aggregate from the
-- orders above; the cold org's memberships are created by that backfill alone).
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE demo_members AS
SELECT p.idx, p.email, p.first || ' ' || p.last AS display_name, p.consent, p.persona,
       gen_random_uuid() AS consumer_id, gen_random_uuid() AS membership_id,
       (SELECT min(o.created_at) FROM demo_orders o WHERE o.person = p.idx) AS first_seen,
       (SELECT o.id FROM demo_orders o WHERE o.person = p.idx AND NOT o.refunded ORDER BY o.created_at DESC LIMIT 1) AS last_order_id,
       (SELECT max(o.created_at) FROM demo_orders o WHERE o.person = p.idx AND NOT o.refunded) AS last_order_at
FROM demo_people p WHERE p.org_id = 'dec0de00-0000-4000-8000-000000000001';

INSERT INTO consumers (consumer_id, normalized_email, display_name, created_at)
SELECT consumer_id, email, display_name, coalesce(first_seen, now()) FROM demo_members;

INSERT INTO memberships (membership_id, org_id, consumer_id, display_name, first_touch_src, consent_status,
                         consent_basis, created_at, updated_at)
SELECT membership_id, 'dec0de00-0000-4000-8000-000000000001', consumer_id, display_name, 'organic',
       CASE WHEN consent IS NOT NULL AND last_order_id IS NOT NULL THEN 'subscribed' ELSE 'never' END,
       CASE WHEN consent IS NOT NULL AND last_order_id IS NOT NULL THEN 'explicit' END,
       coalesce(first_seen, now()), now()
FROM demo_members;

-- Checkout consent on the guest's latest order, as the order projector records it.
UPDATE orders o SET marketing_opt_in = true,
       marketing_opt_in_proof = CASE WHEN m.consent = 'named'
         THEN 'Email me about events by Vechirka. I agree to receive email marketing and can unsubscribe any time, one click in every email.'
         ELSE 'Keep me posted about upcoming events.' END,
       marketing_opt_in_text_version = CASE WHEN m.consent = 'named' THEN 'checkout-org-named-2026-09' END
FROM demo_members m WHERE m.last_order_id = o.id AND m.consent IS NOT NULL;

INSERT INTO consent_records (id, membership_id, channel, status, lawful_basis, source, proof_text, occurred_at,
                             text_version, order_id)
SELECT gen_random_uuid(), m.membership_id, 'email', 'subscribed', 'explicit', 'checkout',
       'Ticked the marketing opt-in at checkout next to: "' || o.marketing_opt_in_proof || '", order ' || o.id,
       o.created_at, o.marketing_opt_in_text_version, o.id
FROM demo_members m JOIN orders o ON o.id = m.last_order_id
WHERE m.consent IS NOT NULL;

-- Unsubscribes: one-click from an email, which is also an objection to profiling and a sticky opt-out.
CREATE TEMP TABLE demo_unsub AS
SELECT m.membership_id, m.email, least(m.last_order_at + interval '1 day' * floor(10 + random() * 110), now() - interval '1 day') AS at
FROM demo_members m WHERE m.consent = 'named' AND m.persona IN ('dormant', 'lapsing', 'repeat')
ORDER BY random() LIMIT 25;

INSERT INTO consent_records (id, membership_id, channel, status, lawful_basis, source, occurred_at)
SELECT gen_random_uuid(), membership_id, 'email', 'unsubscribed', NULL, 'one_click', at FROM demo_unsub;
UPDATE memberships m SET consent_status = 'unsubscribed', consent_basis = NULL, objected_profiling = true
FROM demo_unsub u WHERE u.membership_id = m.membership_id;
INSERT INTO marketing_optouts (email_normalized, org_id, channel, source, created_at)
SELECT email, 'dec0de00-0000-4000-8000-000000000001', 'email', 'one_click', at FROM demo_unsub;

-- Suppressions: platform hard bounces and organizer-added marketing suppressions.
CREATE TEMP TABLE demo_supp AS
SELECT m.membership_id, m.email, row_number() OVER () AS n
FROM demo_members m
WHERE m.consent = 'named' AND m.membership_id NOT IN (SELECT membership_id FROM demo_unsub)
ORDER BY random() LIMIT 10;

INSERT INTO suppression_entries (id, scope, org_id, membership_id, normalized_email, reason, since, system_owned, channel)
SELECT gen_random_uuid(), 'deliverability', NULL, NULL, email, 'hard-bounce', now() - interval '20 days', true, 'email'
FROM demo_supp WHERE n <= 6;
INSERT INTO suppression_entries (id, scope, org_id, membership_id, normalized_email, reason, since, system_owned, channel)
SELECT gen_random_uuid(), 'marketing', 'dec0de00-0000-4000-8000-000000000001', membership_id, NULL, 'manual',
       now() - interval '45 days', false, 'email'
FROM demo_supp WHERE n > 6;

-- Objections to profiling that did not unsubscribe.
UPDATE memberships SET objected_profiling = true
WHERE membership_id IN (
  SELECT m.membership_id FROM demo_members m
  WHERE m.consent = 'named'
    AND m.membership_id NOT IN (SELECT membership_id FROM demo_unsub)
    AND m.membership_id NOT IN (SELECT membership_id FROM demo_supp)
  ORDER BY random() LIMIT 8);

-- Historical soft opt-in: the old pre-ticked checkout box, written before explicit consent became the only basis.
-- With soft-opt-in-enabled=false (the default) the ConsentGate reports these as legacy_unproven, not mailable.
CREATE TEMP TABLE demo_soft AS
SELECT m.membership_id, m.last_order_id FROM demo_members m
WHERE m.consent IS NULL AND m.last_order_id IS NOT NULL AND m.persona IN ('repeat', 'first_timer', 'lapsing')
  AND m.last_order_at < now() - interval '21 days'
ORDER BY m.idx LIMIT 12;

UPDATE orders o SET marketing_opt_in = true, marketing_opt_in_proof = 'Keep me posted about upcoming events.'
FROM demo_soft d WHERE d.last_order_id = o.id;
UPDATE memberships m SET consent_status = 'subscribed', consent_basis = 'soft_opt_in'
FROM demo_soft d WHERE d.membership_id = m.membership_id;
INSERT INTO consent_records (id, membership_id, channel, status, lawful_basis, source, proof_text, occurred_at, order_id)
SELECT gen_random_uuid(), d.membership_id, 'email', 'subscribed', 'soft_opt_in', 'checkout',
       'Pre-ticked box at checkout (old flow): "Keep me posted about upcoming events.", order ' || o.id,
       o.created_at, o.id
FROM demo_soft d JOIN orders o ON o.id = d.last_order_id;

-- Door QR and survey on the most recent past night; the sign-ups arrive through the public API (seed.sh).
UPDATE events SET door_optin_enabled = true, door_optin_token = 'vechirkademodoor2026',
                  survey_enabled = true, survey_token = 'vechirkademosurvey2026'
WHERE id = (SELECT id FROM demo_events WHERE idx = 25);

COMMIT;

-- Summary for the seed log.
SELECT p.persona, count(DISTINCT p.idx) AS people, count(o.id) AS orders,
       count(DISTINCT p.idx) FILTER (WHERE p.consent = 'named') AS named_consent,
       count(DISTINCT p.idx) FILTER (WHERE p.consent = 'legacy') AS legacy_consent
FROM demo_people p LEFT JOIN demo_orders o ON o.person = p.idx
GROUP BY p.persona ORDER BY p.persona;
