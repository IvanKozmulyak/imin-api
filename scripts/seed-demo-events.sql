-- Demo events for the redesign. All slugs are prefixed 'demo-'.
-- REMOVE WITH:  DELETE FROM events WHERE org_id='ca2d242e-4370-463d-a7aa-501afc893322' AND slug LIKE 'demo-%';
-- (ticket_tiers cascade). Tiers have no Stripe product/price: /quote and the whole
-- buy page work, but the final redirect to Stripe will fail — syncTier only runs
-- when tiers are written through the application, not by direct SQL.
BEGIN;

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Midnight Bloom', 'demo-midnight-bloom', 'public', 'live', 'House & Techno', lower('House & Techno'), 'Club',
  (now() at time zone 'utc')::date + interval '6 days' + interval '23 hours', (now() at time zone 'utc')::date + interval '6 days' + interval '23 hours' + interval '7 hours', 'Europe/Paris', 'La Chaoué', '5 Rue du Coëtlosquet', 'Metz', lower('Metz'), '57000', 'FR', 49.1193, 6.1757,
  'Midnight Bloom at La Chaoué. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 23:00. Last entry 02:00.', 'https://images.pexels.com/photos/1105666/pexels-photo-1105666.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') - interval '30 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Early Bird', 1200, 80, 80, true, 0, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-midnight-bloom' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Standard', 1800, 150, 147, true, 1, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-midnight-bloom' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Late', 2400, 100, 12, true, 2, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-midnight-bloom' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Riverside Open Air', 'demo-riverside-open-air', 'public', 'live', 'House & Techno', lower('House & Techno'), 'Open Air',
  (now() at time zone 'utc')::date + interval '13 days' + interval '16 hours', (now() at time zone 'utc')::date + interval '13 days' + interval '16 hours' + interval '7 hours', 'Europe/Paris', 'Plan d''Eau', 'Allée du Plan d''Eau', 'Metz', lower('Metz'), '57000', 'FR', 49.1104, 6.1608,
  'Riverside Open Air at Plan d''Eau. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 16:00. Last entry 02:00.', 'https://images.pexels.com/photos/2114365/pexels-photo-2114365.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') - interval '30 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Phase 1', 800, 200, 200, true, 0, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-riverside-open-air' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Phase 2', 1400, 300, 86, true, 1, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-riverside-open-air' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Warehouse 404', 'demo-warehouse-404', 'public', 'live', 'House & Techno', lower('House & Techno'), 'Rave',
  (now() at time zone 'utc')::date + interval '20 days' + interval '23 hours', (now() at time zone 'utc')::date + interval '20 days' + interval '23 hours' + interval '7 hours', 'Europe/Paris', 'Hangar 12', '12 Rue de la Foucotte', 'Nancy', lower('Nancy'), '54000', 'FR', 48.6921, 6.1844,
  'Warehouse 404 at Hangar 12. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 23:00. Last entry 02:00.', 'https://images.pexels.com/photos/1540406/pexels-photo-1540406.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') + interval '14 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Standard', 2000, 250, 0, true, 0, (now() at time zone 'utc') + interval '14 days' FROM events WHERE slug='demo-warehouse-404' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Disco Brunch', 'demo-disco-brunch', 'public', 'live', 'Club / Open Format', lower('Club / Open Format'), 'Concert',
  (now() at time zone 'utc')::date + interval '9 days' + interval '12 hours', (now() at time zone 'utc')::date + interval '9 days' + interval '12 hours' + interval '7 hours', 'Europe/Paris', 'Le Vertigo', '29 Rue de la Visitation', 'Nancy', lower('Nancy'), '54000', 'FR', 48.6937, 6.1834,
  'Disco Brunch at Le Vertigo. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 12:00. Last entry 02:00.', 'https://images.pexels.com/photos/995301/pexels-photo-995301.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') - interval '30 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Brunch', 1000, 120, 60, true, 0, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-disco-brunch' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Brunch + Set', 2600, 60, 52, true, 1, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-disco-brunch' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Cathédrale Sessions', 'demo-cathedrale-sessions', 'public', 'live', 'Hip-Hop & R&B', lower('Hip-Hop & R&B'), 'Concert',
  (now() at time zone 'utc')::date + interval '11 days' + interval '20 hours', (now() at time zone 'utc')::date + interval '11 days' + interval '20 hours' + interval '7 hours', 'Europe/Paris', 'La Laiterie', '13 Rue du Hohwald', 'Strasbourg', lower('Strasbourg'), '67000', 'FR', 48.5819, 7.7509,
  'Cathédrale Sessions at La Laiterie. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 20:00. Last entry 02:00.', 'https://images.pexels.com/photos/1190298/pexels-photo-1190298.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') - interval '30 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Standard', 2200, 400, 180, true, 0, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-cathedrale-sessions' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Front Stage', 3800, 80, 74, true, 1, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-cathedrale-sessions' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Rooftop Sundown', 'demo-rooftop-sundown', 'public', 'live', 'Latin & Afrobeats', lower('Latin & Afrobeats'), 'Club',
  (now() at time zone 'utc')::date + interval '17 days' + interval '19 hours', (now() at time zone 'utc')::date + interval '17 days' + interval '19 hours' + interval '7 hours', 'Europe/Paris', 'Le Bunker', '5 Quai des Alpes', 'Strasbourg', lower('Strasbourg'), '67000', 'FR', 48.5734, 7.7521,
  'Rooftop Sundown at Le Bunker. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 19:00. Last entry 02:00.', 'https://images.pexels.com/photos/1763075/pexels-photo-1763075.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') - interval '30 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Standard', 1600, 180, 44, true, 0, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-rooftop-sundown' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Kirchberg Nights', 'demo-kirchberg-nights', 'public', 'live', 'House & Techno', lower('House & Techno'), 'Club',
  (now() at time zone 'utc')::date + interval '8 days' + interval '22 hours', (now() at time zone 'utc')::date + interval '8 days' + interval '22 hours' + interval '7 hours', 'Europe/Paris', 'Rotondes', '3 Place des Rotondes', 'Luxembourg', lower('Luxembourg'), '1110', 'LU', 49.6297, 6.1602,
  'Kirchberg Nights at Rotondes. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 22:00. Last entry 02:00.', 'https://images.pexels.com/photos/2263436/pexels-photo-2263436.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') - interval '30 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Standard', 2500, 300, 120, true, 0, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-kirchberg-nights' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'VIP', 4500, 50, 48, true, 1, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-kirchberg-nights' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Saar Bass Lab', 'demo-saar-bass-lab', 'public', 'live', 'Bass & Hard Dance', lower('Bass & Hard Dance'), 'Rave',
  (now() at time zone 'utc')::date + interval '15 days' + interval '23 hours', (now() at time zone 'utc')::date + interval '15 days' + interval '23 hours' + interval '7 hours', 'Europe/Paris', 'Garage', 'Bleichstraße 61a', 'Saarbrücken', lower('Saarbrücken'), '66111', 'DE', 49.2402, 6.9969,
  'Saar Bass Lab at Garage. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 23:00. Last entry 02:00.', 'https://images.pexels.com/photos/3184465/pexels-photo-3184465.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') - interval '30 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Standard', 1900, 350, 90, true, 0, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-saar-bass-lab' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Pigalle After Hours', 'demo-pigalle-after-hours', 'public', 'live', 'House & Techno', lower('House & Techno'), 'Club',
  (now() at time zone 'utc')::date + interval '4 days' + interval '23 hours', (now() at time zone 'utc')::date + interval '4 days' + interval '23 hours' + interval '7 hours', 'Europe/Paris', 'Le Rex Club', '5 Boulevard Poissonnière', 'Paris', lower('Paris'), '75002', 'FR', 48.8823, 2.3372,
  'Pigalle After Hours at Le Rex Club. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 23:00. Last entry 02:00.', 'https://images.pexels.com/photos/167636/pexels-photo-167636.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') - interval '30 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Early Bird', 2000, 100, 100, true, 0, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-pigalle-after-hours' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Standard', 2800, 400, 312, true, 1, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-pigalle-after-hours' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Canal Sessions', 'demo-canal-sessions', 'public', 'live', 'Jazz & Acoustic', lower('Jazz & Acoustic'), 'Concert',
  (now() at time zone 'utc')::date + interval '22 days' + interval '20 hours', (now() at time zone 'utc')::date + interval '22 days' + interval '20 hours' + interval '7 hours', 'Europe/Paris', 'Point Éphémère', '200 Quai de Valmy', 'Paris', lower('Paris'), '75010', 'FR', 48.872, 2.366,
  'Canal Sessions at Point Éphémère. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 20:00. Last entry 02:00.', 'https://images.pexels.com/photos/1449791/pexels-photo-1449791.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') - interval '30 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Standard', 1800, 150, 31, true, 0, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-canal-sessions' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Croix-Rousse Basement', 'demo-croix-rousse-basement', 'public', 'live', 'House & Techno', lower('House & Techno'), 'Club',
  (now() at time zone 'utc')::date + interval '26 days' + interval '23 hours', (now() at time zone 'utc')::date + interval '26 days' + interval '23 hours' + interval '7 hours', 'Europe/Paris', 'Le Sucre', '50 Quai Rambaud', 'Lyon', lower('Lyon'), '69002', 'FR', 45.7749, 4.832,
  'Croix-Rousse Basement at Le Sucre. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 23:00. Last entry 02:00.', 'https://images.pexels.com/photos/1105666/pexels-photo-1105666.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') + interval '10 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Standard', 2100, 280, 0, true, 0, (now() at time zone 'utc') + interval '10 days' FROM events WHERE slug='demo-croix-rousse-basement' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

INSERT INTO events (id, org_id, name, slug, visibility, status, genre, genre_key, type, starts_at, ends_at, timezone,
  venue_name, venue_street, venue_city, venue_city_key, venue_postal_code, venue_country, venue_latitude, venue_longitude,
  description, poster_url, currency, on_sale_at, created_by, published_at)
VALUES (gen_random_uuid(), 'ca2d242e-4370-463d-a7aa-501afc893322', 'Brussels Warehouse', 'demo-brussels-warehouse', 'public', 'live', 'Bass & Hard Dance', lower('Bass & Hard Dance'), 'Rave',
  (now() at time zone 'utc')::date + interval '29 days' + interval '23 hours', (now() at time zone 'utc')::date + interval '29 days' + interval '23 hours' + interval '7 hours', 'Europe/Paris', 'Fuse', '208 Rue Blaes', 'Brussels', lower('Brussels'), '1000', 'BE', 50.8503, 4.3517,
  'Brussels Warehouse at Fuse. A night built around sound: long sets, a room that stays dark, and a crowd that came to dance.

Doors 23:00. Last entry 02:00.', 'https://images.pexels.com/photos/2114365/pexels-photo-2114365.jpeg?auto=compress&cs=tinysrgb&w=1600', 'EUR', (now() at time zone 'utc') - interval '30 days', '3898b976-7b33-4605-9dec-5ffe2adfa71a', (now() at time zone 'utc') - interval '20 days');
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Standard', 2300, 500, 140, true, 0, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-brussels-warehouse' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';
INSERT INTO ticket_tiers (id, event_id, name, price_minor, quantity, sold, enabled, sort_order, sale_starts_at)
SELECT gen_random_uuid(), id, 'Late', 2900, 120, 0, true, 1, (now() at time zone 'utc') - interval '30 days' FROM events WHERE slug='demo-brussels-warehouse' AND org_id='ca2d242e-4370-463d-a7aa-501afc893322';

COMMIT;
