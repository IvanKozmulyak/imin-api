#!/usr/bin/env bash
# Rebuilds the LOCAL imin_demo database from scratch and fills it with the audience-tool demo org.
# Local only: talks to Postgres on localhost:5433 and an api it boots itself on :8095. Never Railway.
#   scripts/demo/seed.sh                 # seed, then stop the api
#   scripts/demo/seed.sh --keep-running  # seed and leave the api running on :8095
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
LOG_DIR="${IMIN_DEMO_LOG_DIR:-${TMPDIR:-/tmp}/imin-demo}"
mkdir -p "$LOG_DIR"
DB=imin_demo
export PGPASSWORD=secret
PSQL=(psql -h localhost -p 5433 -U myuser -v ON_ERROR_STOP=1 -q)
KEEP=0; [ "${1:-}" = "--keep-running" ] && KEEP=1
API_PID=""

stop_api() {
  if [ -n "$API_PID" ] && kill -0 "$API_PID" 2>/dev/null; then
    kill "$API_PID" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$API_PID" 2>/dev/null || break; sleep 1; done
  fi
  API_PID=""
}
trap '[ "$KEEP" = 1 ] || stop_api' EXIT

# Boots the api and waits for $2 (a log line); $1 names the log file.
boot_api() {
  local log="$LOG_DIR/$1.log" wait_for="$2"
  CUR_LOG="$log"
  if lsof -iTCP:8095 -sTCP:LISTEN -P >/dev/null 2>&1; then
    echo "port 8095 is busy; stop whatever runs there first" >&2; exit 1
  fi
  # The startup backfill and recompute hold ShedLock for at least a minute; a quick reboot would skip them.
  "${PSQL[@]}" -d "$DB" -c "DO \$\$ BEGIN IF to_regclass('shedlock') IS NOT NULL THEN DELETE FROM shedlock; END IF; END \$\$" >/dev/null
  "$HERE/run-api.sh" > "$log" 2>&1 &
  API_PID=$!
  for _ in $(seq 1 120); do
    if grep -q "$wait_for" "$log"; then return 0; fi
    if ! kill -0 "$API_PID" 2>/dev/null || grep -q "APPLICATION FAILED TO START" "$log"; then
      echo "api did not boot; tail of $log:" >&2; tail -40 "$log" >&2; exit 1
    fi
    sleep 2
  done
  echo "timed out waiting for '$wait_for' in $log" >&2; exit 1
}

# Waits for another log line of the running api.
wait_log() {
  for _ in $(seq 1 120); do
    if grep -q "$1" "$CUR_LOG"; then return 0; fi
    kill -0 "$API_PID" 2>/dev/null || { echo "api exited; tail of $CUR_LOG:" >&2; tail -40 "$CUR_LOG" >&2; exit 1; }
    sleep 2
  done
  echo "timed out waiting for '$1' in $CUR_LOG" >&2; exit 1
}

q() { "${PSQL[@]}" -d "$DB" -Atc "$1"; }
ORG=dec0de00-0000-4000-8000-000000000001
ev() { q "SELECT md5('imin-demo-event-$1')::uuid"; }

echo "1/8 Postgres on :5433"
if ! pg_isready -h localhost -p 5433 >/dev/null 2>&1; then
  docker start imin-postgres imin-redis >/dev/null 2>&1 || (cd "$REPO" && docker compose up -d postgres redis)
  for _ in $(seq 1 30); do pg_isready -h localhost -p 5433 >/dev/null 2>&1 && break; sleep 1; done
fi
docker start imin-redis >/dev/null 2>&1 || true

echo "2/8 drop and create $DB (mydatabase is never touched)"
"${PSQL[@]}" -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB' AND pid <> pg_backend_pid()" >/dev/null
"${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB"

# Rebuilt when missing or older than the sources (run-api.sh checks the same), outside any boot timeout.
JAR="$(ls "$REPO"/target/imin-api-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ] || [ -n "$(find "$REPO/src/main" "$REPO/pom.xml" -newer "$JAR" -print -quit)" ]; then
  (cd "$REPO" && ./mvnw -q -DskipTests package)
  JAR="$(ls "$REPO"/target/imin-api-*.jar | head -1)"
fi

# The local-only outcome trigger, compiled against this build onto a loader path used by the last boot only.
LOADER="$LOG_DIR/demo-loader"
rm -rf "$LOADER" "$LOG_DIR/demo-lib" "$LOG_DIR/demo-classes"
mkdir -p "$LOADER" "$LOG_DIR/demo-lib" "$LOG_DIR/demo-classes"
unzip -q -o -j "$JAR" 'BOOT-INF/lib/*' -d "$LOG_DIR/demo-lib"
javac -nowarn -proc:none -cp "$REPO/target/classes:$LOG_DIR/demo-lib/*" -d "$LOG_DIR/demo-classes" "$HERE/DemoOutcomeRun.java"
jar cf "$LOADER/demo-outcome-run.jar" -C "$LOG_DIR/demo-classes" .

echo "3/8 first boot: Flyway migrations and the app's own city_open_data seeder"
boot_api boot1-migrate "CityOpenDataSeeder:"
stop_api

echo "4/8 demo org, events, orders, tickets, consents"
"${PSQL[@]}" -d "$DB" -f "$HERE/seed-audience-demo.sql"

# A re-seed within 15 minutes would otherwise hit the public door/survey rate limits of the last run.
docker exec imin-redis redis-cli -n 5 FLUSHDB >/dev/null

echo "5/8 second boot: backfill; import, door QR, survey, erasures, invitations and invite-on-publish through the api"
boot_api boot2-populate "FanFeatureRecomputeJob: done"
DOOR_EVENT=$(q "SELECT id FROM events WHERE door_optin_token = 'vechirkademodoor2026'")
EXISTING=$(q "SELECT string_agg(email, ',') FROM (SELECT DISTINCT c.normalized_email AS email
  FROM memberships m JOIN consumers c ON c.consumer_id = m.consumer_id
  JOIN orders o ON o.email_normalized = c.normalized_email AND o.event_id = '$DOOR_EVENT'
  WHERE m.org_id = '$ORG' AND m.consent_status = 'never'
  ORDER BY 1 LIMIT 13) s")
python3 "$HERE/seed_api_steps.py" populate "$DOOR_EVENT" vechirkademodoor2026 vechirkademosurvey2026 \
  "$(date +%F)" "$EXISTING"
# Erasure requests (30-day grace, so they stay erase_pending): two subscribed guests and one without consent.
ERASE=$(q "SELECT string_agg(id::text, ',') FROM (
  (SELECT m.membership_id AS id FROM memberships m JOIN consumers c ON c.consumer_id = m.consumer_id
    WHERE m.org_id = '$ORG' AND m.consent_status = 'subscribed' AND m.consent_basis = 'explicit'
    ORDER BY c.normalized_email DESC LIMIT 2)
  UNION ALL
  (SELECT m.membership_id FROM memberships m JOIN consumers c ON c.consumer_id = m.consumer_id
    WHERE m.org_id = '$ORG' AND m.consent_status = 'never' AND c.normalized_email LIKE '%.%.%@example.test'
    ORDER BY c.normalized_email DESC LIMIT 1)) s")
python3 "$HERE/seed_api_steps.py" stage "$(ev 29),$(ev 30)" "$(ev 26)" "$(ev 28)" "$ERASE"
stop_api

echo "6/8 outcome history: move the invited events into the past, record the sends, then the orders that followed"
"${PSQL[@]}" -d "$DB" -f "$HERE/seed-audience-outcomes.sql"

echo "7/8 third boot: complaints (signed Resend webhook) and one-click unsubscribes through the api"
boot_api boot3-engage "FanFeatureRecomputeJob: done"
# Per invited event: a spam report and a few unsubscribes from delivered recipients who did not buy, a day after the send.
q "WITH r AS (
  SELECT cp.event_id, r.campaign_id, r.membership_id, r.email, r.provider_message_id, cp.sent_at,
         row_number() OVER (PARTITION BY cp.event_id ORDER BY c.normalized_email) AS n
  FROM campaign_recipients r JOIN campaigns cp ON cp.id = r.campaign_id
  JOIN memberships m ON m.membership_id = r.membership_id JOIN consumers c ON c.consumer_id = m.consumer_id
  WHERE cp.org_id = '$ORG' AND cp.status = 'sent' AND r.status = 'delivered'
    AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.event_id = cp.event_id AND o.email_normalized = c.normalized_email))
  SELECT CASE WHEN n = 1 THEN 'complaint' ELSE 'unsubscribe' END, '$ORG', membership_id, campaign_id,
         provider_message_id, email,
         to_char((sent_at + interval '20 hours') AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')
  FROM r WHERE n <= CASE WHEN event_id = '$(ev 26)' THEN 3 ELSE 4 END" > "$LOG_DIR/engage.txt"
python3 "$HERE/seed_api_steps.py" engage "$LOG_DIR/engage.txt"
# One-click unsubscribes are stamped with the request time; move each to its day-after-the-send time.
awk -F'|' '$1 == "unsubscribe" {
  printf "UPDATE consent_records SET occurred_at = '"'"'%s'"'"' WHERE membership_id = '"'"'%s'"'"' AND status = '"'"'unsubscribed'"'"' AND source = '"'"'one_click'"'"';\n", $7, $3;
  printf "UPDATE marketing_optouts SET created_at = '"'"'%s'"'"' WHERE org_id = '"'"'%s'"'"' AND email_normalized = '"'"'%s'"'"';\n", $7, $2, $6;
  printf "UPDATE campaign_recipients SET last_event_at = '"'"'%s'"'"' WHERE campaign_id = '"'"'%s'"'"' AND membership_id = '"'"'%s'"'"';\n", $7, $4, $3;
}' "$LOG_DIR/engage.txt" | "${PSQL[@]}" -d "$DB" >/dev/null
stop_api

echo "8/8 fourth boot: fan-feature recompute and the outcome collection (DemoOutcomeRun, local only), then the report"
IMIN_DEMO_LOADER_PATH="$LOADER" boot_api boot4-report "FanFeatureRecomputeJob: done"
wait_log "DemoOutcomeRun: "
if grep -q "DemoOutcomeRun: failed" "$CUR_LOG"; then grep "DemoOutcomeRun" "$CUR_LOG" >&2; exit 1; fi
WARM=$(q "SELECT string_agg(id::text, ',' ORDER BY starts_at) FROM events
  WHERE org_id = '$ORG' AND status IN ('LIVE', 'DRAFT')")
COLD=$(q "SELECT id FROM events WHERE org_id = 'dec0de00-0000-4000-8000-000000000002' AND status = 'LIVE'")
python3 "$HERE/seed_api_steps.py" report "$WARM" "$COLD" "$(ev 29),$(ev 30),$(ev 26)" "$(ev 28)" \
  | tee "$LOG_DIR/report.txt"
q "SELECT class, count(*) FROM fan_features WHERE org_id = '$ORG' GROUP BY 1 ORDER BY 2 DESC" \
  | sed 's/^/fan_features class|count: /'
q "SELECT scope, class, genre_fit, arm, n, bought, events FROM response_calibration ORDER BY 1, 2, 3, 4" \
  | sed 's/^/response_calibration scope|class|fit|arm|n|bought|events: /'

echo "Logs in $LOG_DIR"
if [ "$KEEP" = 1 ]; then
  echo "api left running on :8095 (pid $API_PID); stop it with: kill $API_PID"
fi
