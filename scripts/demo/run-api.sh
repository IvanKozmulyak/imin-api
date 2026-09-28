#!/usr/bin/env bash
# Boots the api on :8095 against the local imin_demo database. Local only: never point this at Railway.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# The main checkout's .env.local (a worktree has none of its own).
MAIN="$(cd "$(git -C "$REPO" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)/.." 2>/dev/null && pwd || echo "$REPO")"
ENV_FILE="${IMIN_ENV_FILE:-$MAIN/.env.local}"

# .env.local supplies the R2 settings the app needs to boot (MEDIA_ENABLED=false does not boot today).
# Only KEY=VALUE lines are read; the file is not sourced, so a stray line cannot break the boot.
if [ -f "$ENV_FILE" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || continue
    key="${line%%=*}"; val="${line#*=}"
    val="${val%\"}"; val="${val#\"}"; val="${val%\'}"; val="${val#\'}"
    export "$key=$val"
  done < "$ENV_FILE"
fi

export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/imin_demo"
export SPRING_DATASOURCE_USERNAME=myuser SPRING_DATASOURCE_PASSWORD=secret
export SERVER_PORT=8095
# Rate-limit buckets live in Redis; logical db 5 keeps the demo apart from the regular dev api (db 0).
export REDIS_URL="redis://localhost:6380/5"
export SPRING_DOCKER_COMPOSE_ENABLED=false
export SPRING_FLYWAY_OUT_OF_ORDER=true
# Throwaway secrets: no real email is sent and no Stripe call can succeed.
export IMIN_TICKET_SIGNING_SECRET="demo-only-ticket-signing-secret-not-for-prod-000000"
export RESEND_API_KEY=re_dummy
export STRIPE_SECRET_KEY=sk_test_demo_dummy
export IMIN_API_PUBLIC_BASE_URL="http://localhost:8095"
# Real sends stay off whatever .env.local says; the seed writes sent/scheduled states in SQL instead.
export IMIN_AUDIENCE_PLAN_SENDS_ENABLED=false
# Cap 0 = the summary template (aiGenerated=false) with no model call. IMIN_DEMO_SUMMARY_CAP=50 tries the model.
export IMIN_AUDIENCE_PLAN_SUMMARY_DAILY_CAP="${IMIN_DEMO_SUMMARY_CAP:-0}"
# Throwaway keys the seed signs its local Resend complaint webhooks and one-click unsubscribes with.
export RESEND_WEBHOOK_SECRET="whsec_ZGVtby1vbmx5LXJlc2VuZC13ZWJob29rLXNlY3JldA=="
export IMIN_MARKETING_UNSUBSCRIBE_SECRET="demo-only-unsubscribe-secret-not-for-prod"
# Links the api builds (door QR and survey URLs, dashboard links) point at the local dev servers.
export IMIN_BUYER_SITE_BASE_URL="http://localhost:3000"
export IMIN_APP_BASE_URL="http://localhost:5173"

JAR="$(ls "$REPO"/target/imin-api-*.jar 2>/dev/null | head -1 || true)"
# Rebuilt when missing or older than the sources, so a rebased checkout never boots yesterday's code.
if [ -z "$JAR" ] || [ -n "$(find "$REPO/src/main" "$REPO/pom.xml" -newer "$JAR" -print -quit)" ]; then
  (cd "$REPO" && ./mvnw -q -DskipTests package)
  JAR="$(ls "$REPO"/target/imin-api-*.jar | head -1)"
fi
# IMIN_DEMO_LOADER_PATH adds seed.sh's local-only classes (DemoOutcomeRun); never set outside the demo.
if [ -n "${IMIN_DEMO_LOADER_PATH:-}" ]; then
  exec java -Dloader.path="$IMIN_DEMO_LOADER_PATH" -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher
fi
exec java -jar "$JAR"
