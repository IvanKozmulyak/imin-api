-- V82 normalisation — IMPACT REPORT (READ ONLY)
--
-- What it is: a dry run of `V82__normalize_event_facets.sql`. It computes exactly what that
-- migration would write and prints it, without writing anything. Every statement is a SELECT;
-- there is no transaction, no temp table, no view. It is safe to run against production with a
-- read-only role, and that is the intended way to use it.
--
--   psql "$DATABASE_URL" -f scripts/v82-normalization-impact-report.sql
--   docker exec -i imin-postgres psql -U myuser -d mydatabase < scripts/v82-normalization-impact-report.sql
--
-- The normalisation expressions below are copied verbatim from the migration, which in turn
-- mirrors com.imin.iminapi.util.EventNormalization:
--   genre     ->       trim(regexp_replace(genre,      '\s+', ' ', 'g'))   -- case preserved
--   city      ->       trim(regexp_replace(venue_city, '\s+', ' ', 'g'))   -- case preserved
--   country   -> upper(trim(venue_country)) when it is 2 characters, else NULL   -- never ''
--   city_key  -> lower(city)
--   genre_key -> lower(genre)
-- If you change one of the three, change all three.
--
-- HOW TO READ IT — the sections that can say "stop":
--   §5  city keys that merge. Check every group: two spellings of one town is the point;
--       two genuinely different towns landing on one key is a data problem, not a spelling one.
--   §6  city keys carrying more than one country. These are NOT merged by the facet (Paris/FR
--       and Paris/US stay two chips), but if this section is non-empty the frontend must send
--       `&country=` on those chips or their counts will not match their result pages.
--   §3  genre keys that merge. Confirm each group really is one genre spelled two ways.
--   §7  country values being dropped to NULL. Anything that is not obvious junk needs a look
--       before the migration runs.
--   §10 the genre facet as the buyer will see it, with the label each merged key gets.

\pset null '(null)'
\echo ''
\echo '==================================================================='
\echo ' V82 IMPACT REPORT  —  read-only dry run, nothing is written'
\echo '==================================================================='

\echo ''
\echo '--- §1  Scale: how many rows each rule touches -------------------'
WITH n AS (
  SELECT genre                                                       AS genre_before,
         trim(regexp_replace(genre, '\s+', ' ', 'g'))         AS genre_after,
         venue_city                                                  AS city_before,
         trim(regexp_replace(venue_city, '\s+', ' ', 'g'))           AS city_after,
         venue_country                                               AS country_before,
         CASE WHEN length(trim(venue_country)) = 2
              THEN upper(trim(venue_country)) END                    AS country_after
    FROM events
)
SELECT count(*)                                                             AS events_total,
       count(*) FILTER (WHERE genre_before   IS DISTINCT FROM genre_after)   AS genre_rows_changed,
       count(*) FILTER (WHERE city_before    IS DISTINCT FROM city_after)    AS city_rows_changed,
       count(*) FILTER (WHERE country_before IS DISTINCT FROM country_after) AS country_rows_changed,
       count(*) FILTER (WHERE country_before = '')                           AS country_blank_to_null,
       count(*) FILTER (WHERE country_before IS NOT NULL
                          AND country_before <> ''
                          AND length(trim(country_before)) <> 2)             AS country_junk_to_null,
       count(*) FILTER (WHERE city_after = '')                              AS rows_with_no_city
  FROM n;

\echo ''
\echo '--- §2  Genre: every distinct value that changes -----------------'
WITH n AS (
  SELECT genre AS genre_before,
         trim(regexp_replace(genre, '\s+', ' ', 'g')) AS genre_after
    FROM events
)
SELECT genre_before, genre_after, count(*) AS rows
  FROM n
 WHERE genre_before IS DISTINCT FROM genre_after
 GROUP BY 1, 2
 ORDER BY 2, 1;

\echo ''
\echo '--- §3  Genre keys that MERGE: spellings folding into one chip — REVIEW'
\echo '        (empty = no genre merges; every row here must be one genre spelled two ways)'
WITH n AS (
  SELECT trim(regexp_replace(genre, '\s+', ' ', 'g'))        AS genre_after,
         lower(trim(regexp_replace(genre, '\s+', ' ', 'g'))) AS genre_key
    FROM events
)
SELECT genre_key,
       count(DISTINCT genre_after)                                  AS distinct_spellings,
       string_agg(DISTINCT genre_after, '  |  ' ORDER BY genre_after) AS merged_from,
       count(*)                                                     AS rows
  FROM n
 WHERE genre_key <> ''
 GROUP BY 1
HAVING count(DISTINCT genre_after) > 1
 ORDER BY 1;

\echo ''
\echo '--- §4  City: every distinct display value that changes ----------'
\echo '        (whitespace only — casing is never altered)'
WITH n AS (
  SELECT venue_city AS city_before,
         trim(regexp_replace(venue_city, '\s+', ' ', 'g')) AS city_after
    FROM events
)
SELECT '[' || city_before || ']' AS city_before_bracketed,
       '[' || city_after  || ']' AS city_after_bracketed,
       count(*) AS rows
  FROM n
 WHERE city_before IS DISTINCT FROM city_after
 GROUP BY 1, 2
 ORDER BY 2, 1;

\echo ''
\echo '--- §5  City keys that MERGE: spellings/countries folding into one — REVIEW'
\echo '        (two spellings of one town = the point. Two different towns = stop.)'
WITH n AS (
  SELECT trim(regexp_replace(venue_city, '\s+', ' ', 'g'))            AS city_after,
         lower(trim(regexp_replace(venue_city, '\s+', ' ', 'g')))     AS city_key,
         CASE WHEN length(trim(venue_country)) = 2
              THEN upper(trim(venue_country)) END                     AS country_after
    FROM events
)
SELECT city_key,
       count(*)                                                             AS rows,
       count(DISTINCT city_after)                                           AS spellings,
       string_agg(DISTINCT city_after, '  |  ')                             AS spelling_list,
       count(DISTINCT country_after)                                        AS known_countries,
       string_agg(DISTINCT coalesce(country_after, '(none)'), '  |  ')      AS country_list
  FROM n
 WHERE city_key <> ''
 GROUP BY 1
HAVING count(DISTINCT city_after) > 1
    OR count(DISTINCT coalesce(country_after, '(none)')) > 1
 ORDER BY 1;

\echo ''
\echo '--- §6  City keys with TWO OR MORE known countries — REVIEW ------'
\echo '        (not merged by the facet; frontend chips for these need &country=)'
WITH n AS (
  SELECT lower(trim(regexp_replace(venue_city, '\s+', ' ', 'g')))     AS city_key,
         CASE WHEN length(trim(venue_country)) = 2
              THEN upper(trim(venue_country)) END                     AS country_after
    FROM events
)
SELECT city_key,
       string_agg(DISTINCT country_after, '  |  ')                    AS countries,
       count(*)                                                       AS rows
  FROM n
 WHERE city_key <> ''
 GROUP BY 1
HAVING count(DISTINCT country_after) > 1
 ORDER BY 1;

\echo ''
\echo '--- §7  Country: every distinct value that changes ---------------'
\echo '        (rows with country_after = (null) LOSE a stored value — check they are junk)'
WITH n AS (
  SELECT venue_country AS country_before,
         CASE WHEN length(trim(venue_country)) = 2
              THEN upper(trim(venue_country)) END AS country_after
    FROM events
)
SELECT CASE WHEN country_before IS NULL THEN '(null)' ELSE '[' || country_before || ']' END
                                          AS country_before_bracketed,
       country_after,
       count(*)                           AS rows
  FROM n
 WHERE country_before IS DISTINCT FROM country_after
 GROUP BY 1, 2
 ORDER BY 1;

\echo ''
\echo '--- §8  Buyer city facet: chips BEFORE vs AFTER ------------------'
\echo '        (same eligibility as the feed: alive, PUBLIC, published, not DRAFT/CANCELLED)'
SELECT (SELECT count(*) FROM (
          SELECT DISTINCT venue_city, venue_country
            FROM events
           WHERE deleted_at IS NULL AND visibility = 'PUBLIC' AND published_at IS NOT NULL
             AND status NOT IN ('DRAFT', 'CANCELLED')
             AND venue_city IS NOT NULL AND venue_city <> '') a)          AS chips_before,
       (SELECT count(*) FROM (
          SELECT DISTINCT lower(trim(regexp_replace(venue_city, '\s+', ' ', 'g')))
            FROM events
           WHERE deleted_at IS NULL AND visibility = 'PUBLIC' AND published_at IS NOT NULL
             AND status NOT IN ('DRAFT', 'CANCELLED')
             AND trim(regexp_replace(coalesce(venue_city, ''), '\s+', ' ', 'g')) <> '') b) AS chips_after,
       (SELECT count(DISTINCT genre) FROM events
          WHERE deleted_at IS NULL AND visibility = 'PUBLIC' AND published_at IS NOT NULL
            AND status NOT IN ('DRAFT', 'CANCELLED') AND genre <> '')      AS genre_chips_before,
       (SELECT count(DISTINCT lower(trim(regexp_replace(genre, '\s+', ' ', 'g')))) FROM events
          WHERE deleted_at IS NULL AND visibility = 'PUBLIC' AND published_at IS NOT NULL
            AND status NOT IN ('DRAFT', 'CANCELLED')
            AND trim(regexp_replace(genre, '\s+', ' ', 'g')) <> '')        AS genre_chips_after;

\echo ''
\echo '--- §9  The city facet as the buyer will see it after V82 --------'
\echo '        (mirrors PublicEventService.listCities: label = most common spelling, ties to the'
\echo '         least-shouted then alphabetical; a key with 2+ known countries stays split)'
WITH eligible AS (
  SELECT trim(regexp_replace(venue_city, '\s+', ' ', 'g'))            AS city_after,
         lower(trim(regexp_replace(venue_city, '\s+', ' ', 'g')))     AS city_key,
         CASE WHEN length(trim(venue_country)) = 2
              THEN upper(trim(venue_country)) END                     AS country_after
    FROM events
   WHERE deleted_at IS NULL AND visibility = 'PUBLIC' AND published_at IS NOT NULL
     AND status NOT IN ('DRAFT', 'CANCELLED')
     AND trim(regexp_replace(venue_city, '\s+', ' ', 'g')) <> ''
), ambiguous AS (
  SELECT city_key FROM eligible GROUP BY 1 HAVING count(DISTINCT country_after) > 1
), bucketed AS (
  -- One bucket per key, except for ambiguous keys which get one bucket per stored country.
  SELECT e.city_key, e.city_after, e.country_after,
         CASE WHEN a.city_key IS NULL THEN '' ELSE coalesce(e.country_after, '(unknown)') END AS bucket
    FROM eligible e LEFT JOIN ambiguous a ON a.city_key = e.city_key
), ranked AS (
  SELECT city_key, bucket, city_after,
         row_number() OVER (PARTITION BY city_key, bucket
                            ORDER BY count(*) DESC,
                                     length(regexp_replace(city_after, '[^A-Z]', '', 'g')) ASC,
                                     city_after ASC) AS rn
    FROM bucketed GROUP BY 1, 2, 3
)
SELECT r.city_after                                       AS chip_label,
       CASE WHEN count(DISTINCT b.country_after) = 1
            THEN max(b.country_after) END                 AS chip_country,
       count(*)                                           AS event_count
  FROM ranked r
  JOIN bucketed b ON b.city_key = r.city_key AND b.bucket = r.bucket
 WHERE r.rn = 1
 GROUP BY r.city_key, r.bucket, r.city_after
 ORDER BY 1, 2;

\echo ''
\echo '--- §10 The genre facet as the buyer will see it after V82 -------'
\echo '        (mirrors PublicEventService.listGenres: label = most common spelling, ties to the'
\echo '         least-shouted then alphabetical. One chip per key — never a folded invention.)'
WITH eligible AS (
  SELECT trim(regexp_replace(genre, '\s+', ' ', 'g'))        AS genre_after,
         lower(trim(regexp_replace(genre, '\s+', ' ', 'g'))) AS genre_key
    FROM events
   WHERE deleted_at IS NULL AND visibility = 'PUBLIC' AND published_at IS NOT NULL
     AND status NOT IN ('DRAFT', 'CANCELLED')
     AND trim(regexp_replace(genre, '\s+', ' ', 'g')) <> ''
), ranked AS (
  SELECT genre_key, genre_after, count(*) AS rows,
         row_number() OVER (PARTITION BY genre_key
                            ORDER BY count(*) DESC,
                                     length(regexp_replace(genre_after, '[^A-Z]', '', 'g')) ASC,
                                     genre_after ASC) AS rn
    FROM eligible GROUP BY 1, 2
)
SELECT r.genre_after                                      AS chip_label,
       (SELECT count(*) FROM eligible e
         WHERE e.genre_key = r.genre_key)                 AS event_count,
       (SELECT count(DISTINCT e.genre_after) FROM eligible e
         WHERE e.genre_key = r.genre_key)                 AS spellings_merged
  FROM ranked r
 WHERE r.rn = 1
 ORDER BY lower(r.genre_after), r.genre_after;

\echo ''
\echo '=== end of report — nothing was written ==========================='
\echo ''
