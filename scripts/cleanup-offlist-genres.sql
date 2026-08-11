-- Clean the four events whose genre sits outside the organizer wizard's closed
-- GENRES list, so the buyer's genre facet stops showing lowercase chips next to
-- Title-Case ones.
--
-- Evidence (queried against production 2026-08-11) — all four are PAST, PUBLIC,
-- published, not deleted, and their names leave no ambiguity:
--
--   b5ab2e54  Sunset Rooftop Sessions     house
--   ccede4c6  Neon Warehouse Night        techno
--   78ac13cf  Techno Basement 03          techno
--   a88bb358  Vechirka: Shadows & Smoke   Techno
--
-- The closed list (imin-webapp/src/features/events/schemas.ts) offers exactly
-- one bucket covering both house and techno, so the mapping is unambiguous.
--
-- updated_at is deliberately NOT bumped: the organizer PATCH path uses it as its
-- If-Match ETag, and this is a data cleanup, not the organizer's own edit.
--
-- Run:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 --single-transaction \
--        -f scripts/cleanup-offlist-genres.sql

\echo '--- before ---'
SELECT genre, count(*) AS rows
FROM events
WHERE lower(genre) IN ('techno', 'house')
GROUP BY genre
ORDER BY genre;

UPDATE events
SET genre = 'House & Techno'
WHERE lower(genre) IN ('techno', 'house');

\echo '--- after: the full genre facet ---'
SELECT genre, count(*) AS rows
FROM events
WHERE genre IS NOT NULL AND genre <> ''
GROUP BY genre
ORDER BY genre;
