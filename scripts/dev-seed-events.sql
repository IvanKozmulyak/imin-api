-- Dev seed: 10 public events across 10 European cities + 2 ticket tiers each.
-- All rows use fixed UUIDs and ON CONFLICT DO NOTHING so the script is
-- safe to re-run. Targets the existing user `ivankozmulyak@gmail.com`
-- and their org "123" in the local docker-compose Postgres.
--
-- Run with:
--   docker exec -i imin-postgres psql -U myuser -d mydatabase \
--     < imin-api/scripts/dev-seed-events.sql

\set ON_ERROR_STOP on

BEGIN;

-- Anchor "now" to a single UTC instant so all rows share a consistent timeline
-- across the inserts below.
WITH t AS (SELECT (NOW() AT TIME ZONE 'UTC') AS now_utc)
INSERT INTO events (
    id, org_id, name, slug, visibility, status, genre, type,
    starts_at, ends_at, timezone,
    venue_name, venue_street, venue_city, venue_postal_code, venue_country,
    description, currency,
    on_sale_at, sale_closes_at,
    created_by, published_at
)
SELECT * FROM (VALUES
    ('00000000-1111-1111-1111-000000000001'::uuid,
     'b927ad22-338f-466b-9504-cfa210281cf5'::uuid,
     'Void Sessions IV', 'void-sessions-iv',
     'PUBLIC', 'LIVE', 'techno', 'club',
     (SELECT now_utc + INTERVAL '5 days' FROM t),
     (SELECT now_utc + INTERVAL '5 days 6 hours' FROM t),
     'Europe/Berlin',
     'Sub Club', 'Köpenicker Straße 70', 'Berlin', '10179', 'DE',
     'Six-hour deep techno marathon in the bunker.', 'EUR',
     (SELECT now_utc - INTERVAL '7 days' FROM t),
     (SELECT now_utc + INTERVAL '5 days' FROM t),
     'da55c4e4-a201-4e7e-bb77-fa8c37ab1ecb'::uuid,
     (SELECT now_utc - INTERVAL '10 days' FROM t)),

    ('00000000-1111-1111-1111-000000000002'::uuid,
     'b927ad22-338f-466b-9504-cfa210281cf5'::uuid,
     'Canal House', 'canal-house',
     'PUBLIC', 'LIVE', 'house', 'club',
     (SELECT now_utc + INTERVAL '10 days' FROM t),
     (SELECT now_utc + INTERVAL '10 days 5 hours' FROM t),
     'Europe/Amsterdam',
     'De School', 'Doctor Jan van Breemenstraat 1', 'Amsterdam', '1056AB', 'NL',
     'Sundown house at the old school.', 'EUR',
     (SELECT now_utc - INTERVAL '7 days' FROM t),
     (SELECT now_utc + INTERVAL '10 days' FROM t),
     'da55c4e4-a201-4e7e-bb77-fa8c37ab1ecb'::uuid,
     (SELECT now_utc - INTERVAL '12 days' FROM t)),

    ('00000000-1111-1111-1111-000000000003'::uuid,
     'b927ad22-338f-466b-9504-cfa210281cf5'::uuid,
     'Warehouse 51', 'warehouse-51',
     'PUBLIC', 'LIVE', 'techno', 'warehouse',
     (SELECT now_utc + INTERVAL '14 days' FROM t),
     (SELECT now_utc + INTERVAL '14 days 8 hours' FROM t),
     'Europe/London',
     'Printworks Archive', '4 Surrey Quays Rd', 'London', 'SE16 7PJ', 'GB',
     'Industrial warehouse rave. No phones on the floor.', 'GBP',
     (SELECT now_utc - INTERVAL '5 days' FROM t),
     (SELECT now_utc + INTERVAL '14 days' FROM t),
     'da55c4e4-a201-4e7e-bb77-fa8c37ab1ecb'::uuid,
     (SELECT now_utc - INTERVAL '8 days' FROM t)),

    ('00000000-1111-1111-1111-000000000004'::uuid,
     'b927ad22-338f-466b-9504-cfa210281cf5'::uuid,
     'Bain de Soleil', 'bain-de-soleil',
     'PUBLIC', 'LIVE', 'house', 'club',
     (SELECT now_utc + INTERVAL '21 days' FROM t),
     (SELECT now_utc + INTERVAL '21 days 6 hours' FROM t),
     'Europe/Paris',
     'Concrete', '69 Port de la Rapée', 'Paris', '75012', 'FR',
     'Sunrise set on the Seine.', 'EUR',
     (SELECT now_utc - INTERVAL '4 days' FROM t),
     (SELECT now_utc + INTERVAL '21 days' FROM t),
     'da55c4e4-a201-4e7e-bb77-fa8c37ab1ecb'::uuid,
     (SELECT now_utc - INTERVAL '6 days' FROM t)),

    ('00000000-1111-1111-1111-000000000005'::uuid,
     'b927ad22-338f-466b-9504-cfa210281cf5'::uuid,
     'Solera Open Air', 'solera-open-air',
     'PUBLIC', 'LIVE', 'techno', 'festival',
     (SELECT now_utc + INTERVAL '30 days' FROM t),
     (SELECT now_utc + INTERVAL '30 days 12 hours' FROM t),
     'Europe/Madrid',
     'Mad Cool Garden', 'Av. de los Andes 5', 'Madrid', '28042', 'ES',
     'All-day open air, three stages.', 'EUR',
     (SELECT now_utc - INTERVAL '14 days' FROM t),
     (SELECT now_utc + INTERVAL '30 days' FROM t),
     'da55c4e4-a201-4e7e-bb77-fa8c37ab1ecb'::uuid,
     (SELECT now_utc - INTERVAL '20 days' FROM t)),

    ('00000000-1111-1111-1111-000000000006'::uuid,
     'b927ad22-338f-466b-9504-cfa210281cf5'::uuid,
     'Mar de Festa', 'mar-de-festa',
     'PUBLIC', 'LIVE', 'house', 'club',
     (SELECT now_utc + INTERVAL '35 days' FROM t),
     (SELECT now_utc + INTERVAL '35 days 6 hours' FROM t),
     'Europe/Madrid',
     'Razzmatazz', 'Carrer dels Almogàvers 122', 'Barcelona', '08018', 'ES',
     'Catalan house night, three floors.', 'EUR',
     (SELECT now_utc - INTERVAL '10 days' FROM t),
     (SELECT now_utc + INTERVAL '35 days' FROM t),
     'da55c4e4-a201-4e7e-bb77-fa8c37ab1ecb'::uuid,
     (SELECT now_utc - INTERVAL '15 days' FROM t)),

    ('00000000-1111-1111-1111-000000000007'::uuid,
     'b927ad22-338f-466b-9504-cfa210281cf5'::uuid,
     'Costa Drift', 'costa-drift',
     'PUBLIC', 'LIVE', 'ambient', 'beach',
     (SELECT now_utc + INTERVAL '45 days' FROM t),
     (SELECT now_utc + INTERVAL '45 days 4 hours' FROM t),
     'Europe/Lisbon',
     'Praia do Guincho', 'Estrada do Guincho', 'Lisbon', '2750-642', 'PT',
     'Beachside ambient set, sunrise close.', 'EUR',
     (SELECT now_utc - INTERVAL '20 days' FROM t),
     (SELECT now_utc + INTERVAL '45 days' FROM t),
     'da55c4e4-a201-4e7e-bb77-fa8c37ab1ecb'::uuid,
     (SELECT now_utc - INTERVAL '25 days' FROM t)),

    ('00000000-1111-1111-1111-000000000008'::uuid,
     'b927ad22-338f-466b-9504-cfa210281cf5'::uuid,
     'Iron Curtain', 'iron-curtain',
     'PUBLIC', 'LIVE', 'industrial', 'warehouse',
     (SELECT now_utc + INTERVAL '50 days' FROM t),
     (SELECT now_utc + INTERVAL '50 days 7 hours' FROM t),
     'Europe/Warsaw',
     'Smolna', 'Smolna 38', 'Warsaw', '00-375', 'PL',
     'Hardcore industrial, no opener.', 'EUR',
     (SELECT now_utc - INTERVAL '15 days' FROM t),
     (SELECT now_utc + INTERVAL '50 days' FROM t),
     'da55c4e4-a201-4e7e-bb77-fa8c37ab1ecb'::uuid,
     (SELECT now_utc - INTERVAL '22 days' FROM t)),

    ('00000000-1111-1111-1111-000000000009'::uuid,
     'b927ad22-338f-466b-9504-cfa210281cf5'::uuid,
     'Klubovna', 'klubovna',
     'PUBLIC', 'LIVE', 'techno', 'club',
     (SELECT now_utc + INTERVAL '60 days' FROM t),
     (SELECT now_utc + INTERVAL '60 days 6 hours' FROM t),
     'Europe/Prague',
     'Roxy', 'Dlouhá 33', 'Prague', '110 00', 'CZ',
     'Late-night techno on the Old Town border.', 'EUR',
     (SELECT now_utc - INTERVAL '18 days' FROM t),
     (SELECT now_utc + INTERVAL '60 days' FROM t),
     'da55c4e4-a201-4e7e-bb77-fa8c37ab1ecb'::uuid,
     (SELECT now_utc - INTERVAL '28 days' FROM t)),

    ('00000000-1111-1111-1111-000000000010'::uuid,
     'b927ad22-338f-466b-9504-cfa210281cf5'::uuid,
     'Donau Drift', 'donau-drift',
     'PUBLIC', 'LIVE', 'experimental', 'club',
     (SELECT now_utc + INTERVAL '70 days' FROM t),
     (SELECT now_utc + INTERVAL '70 days 5 hours' FROM t),
     'Europe/Vienna',
     'Grelle Forelle', 'Spittelauer Lände 12', 'Vienna', '1090', 'AT',
     'Experimental basement night, residents only.', 'EUR',
     (SELECT now_utc - INTERVAL '25 days' FROM t),
     (SELECT now_utc + INTERVAL '70 days' FROM t),
     'da55c4e4-a201-4e7e-bb77-fa8c37ab1ecb'::uuid,
     (SELECT now_utc - INTERVAL '35 days' FROM t))
) AS v
ON CONFLICT (id) DO NOTHING;

-- Two tiers per event: an "Early Bird" with some early sales, plus "Standard".
INSERT INTO ticket_tiers (
    id, event_id, name, kind, price_minor, quantity, sold, enabled, sort_order
) VALUES
    ('00000000-2222-2222-2222-000000000001', '00000000-1111-1111-1111-000000000001', 'Early Bird', 'EARLY_BIRD', 1500,  80, 42, true, 0),
    ('00000000-2222-2222-2222-000000000002', '00000000-1111-1111-1111-000000000001', 'Standard',   'STANDARD',  2200, 200, 18, true, 1),

    ('00000000-2222-2222-2222-000000000003', '00000000-1111-1111-1111-000000000002', 'Early Bird', 'EARLY_BIRD', 1800,  60, 35, true, 0),
    ('00000000-2222-2222-2222-000000000004', '00000000-1111-1111-1111-000000000002', 'Standard',   'STANDARD',  2500, 240,  8, true, 1),

    ('00000000-2222-2222-2222-000000000005', '00000000-1111-1111-1111-000000000003', 'Early Bird', 'EARLY_BIRD', 2000, 100, 78, true, 0),
    ('00000000-2222-2222-2222-000000000006', '00000000-1111-1111-1111-000000000003', 'Standard',   'STANDARD',  3000, 400, 22, true, 1),

    ('00000000-2222-2222-2222-000000000007', '00000000-1111-1111-1111-000000000004', 'Early Bird', 'EARLY_BIRD', 1700,  90, 27, true, 0),
    ('00000000-2222-2222-2222-000000000008', '00000000-1111-1111-1111-000000000004', 'Standard',   'STANDARD',  2400, 250,  4, true, 1),

    ('00000000-2222-2222-2222-000000000009', '00000000-1111-1111-1111-000000000005', 'Early Bird', 'EARLY_BIRD', 3500, 250, 198, true, 0),
    ('00000000-2222-2222-2222-000000000010', '00000000-1111-1111-1111-000000000005', 'Standard',   'STANDARD',  5500, 800,  62, true, 1),

    ('00000000-2222-2222-2222-000000000011', '00000000-1111-1111-1111-000000000006', 'Early Bird', 'EARLY_BIRD', 1600,  70, 12, true, 0),
    ('00000000-2222-2222-2222-000000000012', '00000000-1111-1111-1111-000000000006', 'Standard',   'STANDARD',  2300, 220,  3, true, 1),

    ('00000000-2222-2222-2222-000000000013', '00000000-1111-1111-1111-000000000007', 'Early Bird', 'EARLY_BIRD', 1200,  50,  9, true, 0),
    ('00000000-2222-2222-2222-000000000014', '00000000-1111-1111-1111-000000000007', 'Standard',   'STANDARD',  1900, 180,  0, true, 1),

    ('00000000-2222-2222-2222-000000000015', '00000000-1111-1111-1111-000000000008', 'Early Bird', 'EARLY_BIRD', 1400,  60,  6, true, 0),
    ('00000000-2222-2222-2222-000000000016', '00000000-1111-1111-1111-000000000008', 'Standard',   'STANDARD',  2100, 180,  0, true, 1),

    ('00000000-2222-2222-2222-000000000017', '00000000-1111-1111-1111-000000000009', 'Early Bird', 'EARLY_BIRD', 1500,  70,  4, true, 0),
    ('00000000-2222-2222-2222-000000000018', '00000000-1111-1111-1111-000000000009', 'Standard',   'STANDARD',  2200, 200,  0, true, 1),

    ('00000000-2222-2222-2222-000000000019', '00000000-1111-1111-1111-000000000010', 'Early Bird', 'EARLY_BIRD', 1800,  60,  0, true, 0),
    ('00000000-2222-2222-2222-000000000020', '00000000-1111-1111-1111-000000000010', 'Standard',   'STANDARD',  2600, 200,  0, true, 1)
ON CONFLICT (id) DO NOTHING;

-- Pair each seeded event with one of the AI-poster reference images that
-- the public app serves from /posters/<file>. Run with UPDATE so re-runs
-- pick up edits even though the INSERT above is no-op the second time.
UPDATE events SET
    poster_url = 'http://localhost:3000/posters/' || ref.file
FROM (VALUES
    ('00000000-1111-1111-1111-000000000001'::uuid, 'neon_underground_01_friday_night.png'),
    ('00000000-1111-1111-1111-000000000002'::uuid, 'chrome_tropical_01_spring_break.png'),
    ('00000000-1111-1111-1111-000000000003'::uuid, 'industrial_minimal_01_dj_kelci.png'),
    ('00000000-1111-1111-1111-000000000004'::uuid, 'neon_underground_02_party_night_green.png'),
    ('00000000-1111-1111-1111-000000000005'::uuid, 'sunset_silhouette_01_chillout.png'),
    ('00000000-1111-1111-1111-000000000006'::uuid, 'chrome_tropical_02_summer_edition.png'),
    ('00000000-1111-1111-1111-000000000007'::uuid, 'aquatic_distressed_01_pool_party.png'),
    ('00000000-1111-1111-1111-000000000008'::uuid, 'flat_graphic_01_epic_sunday.png'),
    ('00000000-1111-1111-1111-000000000009'::uuid, 'neon_underground_03_karaoke_night.png'),
    ('00000000-1111-1111-1111-000000000010'::uuid, 'golden_editorial_01_good_vibes.png')
) AS ref(id, file)
WHERE events.id = ref.id;

COMMIT;

-- Quick verification:
SELECT venue_city, venue_country, status, starts_at::date, poster_url
FROM events
WHERE id::text LIKE '00000000-1111-1111-1111-%'
ORDER BY starts_at;
