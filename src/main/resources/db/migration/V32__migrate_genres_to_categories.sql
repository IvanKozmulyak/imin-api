-- Collapse the open-ended genre vocabulary into a fixed 8-bucket taxonomy
-- shared by the organizer wizard (imin-webapp) and the buyer filter pills
-- (imin-public). Old values were free-form (organizer-typed or LLM-generated);
-- we map known terms case-insensitively and bucket anything unrecognized into
-- "Club / Open Format" as a catch-all so no row keeps a stale value.
--
-- New buckets:
--   House & Techno · Bass & Hard Dance · Club / Open Format · Hip-Hop & R&B
--   Latin & Afrobeats · Rock & Alternative · Pop · Jazz & Acoustic

UPDATE events
   SET genre = CASE
       WHEN LOWER(TRIM(genre)) IN (
           'house & techno', 'house and techno',
           'techno', 'house', 'deep house', 'tech house', 'afro house',
           'minimal', 'minimal techno', 'progressive house', 'melodic house',
           'melodic techno', 'trance', 'psytrance', 'electronic', 'edm'
       ) THEN 'House & Techno'
       WHEN LOWER(TRIM(genre)) IN (
           'bass & hard dance', 'bass and hard dance',
           'drum & bass', 'drum and bass', 'dnb', 'd&b', 'dubstep', 'jungle',
           'hardstyle', 'hardcore', 'breaks', 'bass'
       ) THEN 'Bass & Hard Dance'
       WHEN LOWER(TRIM(genre)) IN (
           'club / open format', 'club/open format', 'open format', 'club',
           'ambient', 'experimental', 'industrial', 'dub', 'disco', 'nu-disco',
           'nu disco', 'electronica', 'idm', 'leftfield'
       ) THEN 'Club / Open Format'
       WHEN LOWER(TRIM(genre)) IN (
           'hip-hop & r&b', 'hip hop & r&b', 'hip-hop and r&b',
           'hip-hop', 'hip hop', 'hiphop', 'rap', 'r&b', 'rnb', 'trap',
           'urban'
       ) THEN 'Hip-Hop & R&B'
       WHEN LOWER(TRIM(genre)) IN (
           'latin & afrobeats', 'latin and afrobeats',
           'latin', 'reggaeton', 'afrobeat', 'afrobeats', 'amapiano',
           'salsa', 'bachata', 'cumbia'
       ) THEN 'Latin & Afrobeats'
       WHEN LOWER(TRIM(genre)) IN (
           'rock & alternative', 'rock and alternative',
           'rock', 'indie', 'alternative', 'alt', 'metal', 'punk',
           'post-punk', 'post punk', 'shoegaze', 'grunge'
       ) THEN 'Rock & Alternative'
       WHEN LOWER(TRIM(genre)) IN ('pop', 'mainstream', 'top 40') THEN 'Pop'
       WHEN LOWER(TRIM(genre)) IN (
           'jazz & acoustic', 'jazz and acoustic',
           'jazz', 'funk', 'soul', 'classical', 'acoustic', 'blues',
           'singer-songwriter', 'folk'
       ) THEN 'Jazz & Acoustic'
       WHEN genre IS NULL OR TRIM(genre) = '' THEN ''
       ELSE 'Club / Open Format'
   END
 WHERE genre IS NOT NULL;

UPDATE generated_event
   SET genre = CASE
       WHEN LOWER(TRIM(genre)) IN (
           'house & techno', 'house and techno',
           'techno', 'house', 'deep house', 'tech house', 'afro house',
           'minimal', 'minimal techno', 'progressive house', 'melodic house',
           'melodic techno', 'trance', 'psytrance', 'electronic', 'edm'
       ) THEN 'House & Techno'
       WHEN LOWER(TRIM(genre)) IN (
           'bass & hard dance', 'bass and hard dance',
           'drum & bass', 'drum and bass', 'dnb', 'd&b', 'dubstep', 'jungle',
           'hardstyle', 'hardcore', 'breaks', 'bass'
       ) THEN 'Bass & Hard Dance'
       WHEN LOWER(TRIM(genre)) IN (
           'club / open format', 'club/open format', 'open format', 'club',
           'ambient', 'experimental', 'industrial', 'dub', 'disco', 'nu-disco',
           'nu disco', 'electronica', 'idm', 'leftfield'
       ) THEN 'Club / Open Format'
       WHEN LOWER(TRIM(genre)) IN (
           'hip-hop & r&b', 'hip hop & r&b', 'hip-hop and r&b',
           'hip-hop', 'hip hop', 'hiphop', 'rap', 'r&b', 'rnb', 'trap',
           'urban'
       ) THEN 'Hip-Hop & R&B'
       WHEN LOWER(TRIM(genre)) IN (
           'latin & afrobeats', 'latin and afrobeats',
           'latin', 'reggaeton', 'afrobeat', 'afrobeats', 'amapiano',
           'salsa', 'bachata', 'cumbia'
       ) THEN 'Latin & Afrobeats'
       WHEN LOWER(TRIM(genre)) IN (
           'rock & alternative', 'rock and alternative',
           'rock', 'indie', 'alternative', 'alt', 'metal', 'punk',
           'post-punk', 'post punk', 'shoegaze', 'grunge'
       ) THEN 'Rock & Alternative'
       WHEN LOWER(TRIM(genre)) IN ('pop', 'mainstream', 'top 40') THEN 'Pop'
       WHEN LOWER(TRIM(genre)) IN (
           'jazz & acoustic', 'jazz and acoustic',
           'jazz', 'funk', 'soul', 'classical', 'acoustic', 'blues',
           'singer-songwriter', 'folk'
       ) THEN 'Jazz & Acoustic'
       WHEN TRIM(genre) = '' THEN ''
       ELSE 'Club / Open Format'
   END
 WHERE genre IS NOT NULL;
