-- Synthetic fixture for the V82 impact-report dry run (scripts/v82-normalization-impact-report.sql).
-- NOT a dev seed and NOT production data: 23 invented rows that reproduce the drift patterns
-- reported from prod, so the report can be exercised without touching a live database.
--
--   docker exec imin-postgres psql -U myuser -d postgres -c 'CREATE DATABASE v82_fixture;'
--   docker exec -i imin-postgres psql -U myuser -d v82_fixture < scripts/v82-normalization-impact-fixture.sql
--   docker exec -i imin-postgres psql -U myuser -d v82_fixture < scripts/v82-normalization-impact-report.sql
--
-- Column types match events (venue_country is varchar(2), so >2-char junk cannot exist).
CREATE TABLE events (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  genre         text        NOT NULL DEFAULT '',
  venue_city    text        NOT NULL DEFAULT '',
  venue_country varchar(2),
  visibility    varchar(16) NOT NULL DEFAULT 'PUBLIC',
  status        varchar(16) NOT NULL DEFAULT 'LIVE',
  published_at  timestamptz DEFAULT now(),
  deleted_at    timestamptz
);

INSERT INTO events (genre, venue_city, venue_country, visibility, status, published_at, deleted_at) VALUES
  -- the reported Metz split: FR / null / '' + a shouted spelling
  ('techno',        'Metz',        'FR',  'PUBLIC', 'LIVE', now(), NULL),
  ('Techno',        'Metz',        'FR',  'PUBLIC', 'LIVE', now(), NULL),
  ('techno',        'Metz',        NULL,  'PUBLIC', 'LIVE', now(), NULL),
  ('TECHNO',        'METZ',        '',    'PUBLIC', 'LIVE', now(), NULL),
  -- genre casing on other cities
  ('Techno',        'Nancy',       'FR',  'PUBLIC', 'LIVE', now(), NULL),
  ('techno',        'Nancy',       'fr',  'PUBLIC', 'LIVE', now(), NULL),
  ('House & Techno','Lille',       'FR',  'PUBLIC', 'LIVE', now(), NULL),
  ('house & techno','Lille',       'FR',  'PUBLIC', 'LIVE', now(), NULL),
  ('  Drum   &  Bass ', 'Lyon',    'FR',  'PUBLIC', 'LIVE', now(), NULL),
  ('drum & bass',   'Lyon',        'FR',  'PUBLIC', 'LIVE', now(), NULL),
  -- whitespace damage in a city
  ('techno',        'Le  Mans',    'FR',  'PUBLIC', 'LIVE', now(), NULL),
  ('techno',        ' Le Mans ',   'FR',  'PUBLIC', 'LIVE', now(), NULL),
  -- names that must survive untouched
  ('pop',           '''s-Hertogenbosch', 'NL', 'PUBLIC', 'LIVE', now(), NULL),
  ('pop',           'L''Aquila',   'IT',  'PUBLIC', 'LIVE', now(), NULL),
  -- one-character country: junk, dropped to NULL
  ('pop',           'Nice',        'F',   'PUBLIC', 'LIVE', now(), NULL),
  -- same city name, two real countries (must NOT merge)
  ('pop',           'Paris',       'FR',  'PUBLIC', 'LIVE', now(), NULL),
  ('pop',           'PARIS',       'FR',  'PUBLIC', 'LIVE', now(), NULL),
  ('hip-hop',       'Paris',       'US',  'PUBLIC', 'LIVE', now(), NULL),
  -- non-eligible rows: must not reach the facet
  ('Techno',        'Hidden',      'FR',  'PUBLIC', 'DRAFT',     NULL,  NULL),
  ('Techno',        'Cancelled',   'FR',  'PUBLIC', 'CANCELLED', now(), NULL),
  ('Techno',        'Deleted',     'FR',  'PUBLIC', 'LIVE',      now(), now()),
  ('Techno',        'Private',     'FR',  'PRIVATE','LIVE',      now(), NULL),
  -- an event with no city at all
  ('techno',        '',            'FR',  'PUBLIC', 'LIVE', now(), NULL);
