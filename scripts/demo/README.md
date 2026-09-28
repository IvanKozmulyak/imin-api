# Audience-tool demo data (local only)

A repeatable demo organizer, so the audience tool can be seen the way a real organizer would see it.
Everything here runs on your machine: Postgres in Docker on `localhost:5433`, a separate database
`imin_demo`, and an api on `:8095`. Nothing talks to Railway, production or Stripe, and no email is sent
(`RESEND_API_KEY=re_dummy`, `STRIPE_SECRET_KEY=sk_test_demo_dummy`). `mydatabase` (the normal dev
database) is never touched. All people are invented; every address ends in `@example.test`.

## What gets seeded

| | Vechirka (warm) | Salle Obscure (cold) |
|---|---|---|
| Login | `demo@imin.test` / `VechirkaDemo2026!` | `demo-cold@imin.test` / `ObscureDemo2026!` |
| Org | Metz, house & techno collective; legal name and contact set | Nancy jazz room; legal name and contact set |
| History | 27 past nights over ~2 years in Metz, Nancy, Thionville, all 8 genre buckets; 2 of them invited through the audience plan, with outcomes | 1 past night |
| Guests | 1,045 members: 951 buyers (115 of them new guests of the two invited nights), 94 without an order (imports, door and survey sign-ups) | 60 buyers, no consents |
| Upcoming | Nuit Longue (live, 4 weeks, cap 150, one arm sent), Winter Festival (live, ~11 weeks, cap 1,800), Bass Session #4 (draft, ~7 weeks, invite-on-publish stored) | Autumn Trio (live, 3 weeks) |

Vechirka, with the numbers the app computed on a full run (2026-09-28, api `9c684d7c`). Guest classes, consent and
plan figures are the same on every re-seed; the per-arm outcome counts move by a few people, because the api draws a
new split seed for each invitation.

- Guest classes (`GET /audience/metrics`): loyal 127, repeat 145, first-timer 384, lapsing 101, dormant 172,
  imported 50, none 66.
- ConsentGate: **424 plan-mailable** (all `explicit`), plus unsubscribed 32, suppressed 16, objected 8,
  legacy unproven 67 (55 older checkout sentences without a text version + 12 historical `soft_opt_in` rows),
  erase pending 3, no basis 495. The Overview shows 500 explicit and 12 soft opt-in consents, and 0 soft opt-in
  mailable: `IMIN_AUDIENCE_PLAN_SOFT_OPT_IN` stays false, as in production.
- Checkout consent with the organizer-named sentence (`checkout-org-named-2026-09`) for ~42% of the seeded buyers.
- Door scans on ~85% of tickets (some guests are frequent no-shows): showed up 83.7–85.4% (n 2,981), came back
  47.0–51.2% (n 929). 50 refunded orders (20 guests whose only order was refunded end up as class `none`).
- One Shotgun import with per-row provenance: 60 accepted, 25 rejected (10 missing proof, 8 not opted in,
  4 unsubscribed, 3 future export date).
- Door QR and survey are switched on for "Vechirka: Rentrée" (8 days ago): 23 door sign-ups (8 existing guests),
  40 survey answers (10 with an email sign-up). All 33 sign-ups are **pending** (`confirmation_required`, not
  confirmed; no confirmation endpoint exists yet), so their members stay `consent_status = never` and are not
  mailable by any path.
- Three erasure requests (`POST /audience/members/{id}/erase`): `erase_pending` for the 30-day grace period.
- 12 historical soft opt-in members (the old pre-ticked checkout box, `consent_basis = soft_opt_in`): counted as
  `legacy_unproven`.
- Invitations (`POST /events/{id}/audience-plan/invitations`, the plan's own suggested segments and arms):
  - **Vechirka: Late Summer Session** (ended 11 days ago, outcome phase **d7**): loyal/same 59, first-timer/same 116
    (holdout 17), repeat/same 51; launch and D-3 arms sent. About 29 invited guests bought, 0 of the holdout;
    82 new guests.
  - **Bassline Thionville II** (ended 3 days ago, phase **d1**): loyal/other 59, first-timer/same 26; about 6 bought;
    54 new guests.
  - **Nuit Longue** (on sale, phase **live**): loyal/same 32; launch arm (16) sent two days ago, 7 of them bought
    since (3 of the not-yet-emailed D-3 arm too); the D-3 arm is scheduled for 18:00 Paris three days before the
    night (`nextWave`).
  - Across them: 3 spam complaints (signed Resend `email.complained` webhook) and 8 one-click unsubscribes
    (`POST /public/unsubscribe/{token}`), each a day after its send.
- Calibration: the collection writes `response_calibration` from the two past events, so the upcoming plans' segments
  now read confidence `own` where a class × fit was invited, `prior` elsewhere.
- Plans: Nuit Longue warm/`medium` (13–39 of 128), Winter Festival warm/`weak` with `gapExceedsTribe: true`,
  Bass Session #4 warm/`weak`. Salle Obscure's plan is `cold`. Every plan carries the **template summary**
  (`aiGenerated: false`): the demo api runs with `IMIN_AUDIENCE_PLAN_SUMMARY_DAILY_CAP=0`, so no model is called.
- Lift is never a range here: it needs 60 in both the arm and the holdout, and this org's biggest mailable segment is
  116, so the outcome shows `too_few` (first-timer/same) and `no_holdout` (segments under 60, which get no holdout).

Dates are relative to the day you seed, so the classes and phases stay the same whenever you re-run it.

## How it is built

`seed.sh` does, in order:

1. Starts the `imin-postgres` / `imin-redis` containers if they are down.
2. Drops and recreates `imin_demo`. Rebuilds `target/imin-api-*.jar` when it is missing or older than `src/main`,
   and compiles `DemoOutcomeRun.java` against it into `$LOG_DIR/demo-loader/` (see below).
3. Boots the api once: Flyway builds the schema and the app's own `CityOpenDataSeeder` fills
   `city_open_data` (Metz, Nancy, Thionville, plus the Luxembourg and Saarbrücken centroids).
4. Runs `seed-audience-demo.sql`: orgs, logins, events, tiers, orders, tickets, door scans, refunds,
   Vechirka's consumers and memberships, checkout consents (and the 12 historical soft opt-ins), unsubscribes,
   suppressions, objections, and switches door QR + survey on. The two outcome nights are created **upcoming**
   (30 days ahead), because the api refuses invitations for an event that has started. It refuses to run on any
   database other than `imin_demo`.
5. Boots the api again. The startup audience backfill recomputes every membership from the orders (and creates
   Salle Obscure's), then the fan-feature recompute runs. Through the api it then posts the CSV import, the door QR
   sign-ups, the survey answers, three erasure requests, the invitations for the two outcome nights and Nuit Longue,
   and the invite-on-publish intent for the draft (`PUT /events/{id}/audience-plan/invite-on-publish`).
6. Runs `seed-audience-outcomes.sql` with the api stopped. Sends stay off (`IMIN_AUDIENCE_PLAN_SENDS_ENABLED=false`),
   so nothing is mailed. The SQL moves the two outcome nights into the past (event, tiers, plan dates, experiments,
   assignments, arm segments and campaigns shift together, so each invitation sits 30 days before its night). It
   then records the arm campaigns as sent: launch at 10:00 Paris the day after the invitation, D-3 at 18:00 Paris
   three days before the night, recipients `delivered` (about 2% soft bounces). Nuit Longue's launch went out two
   days ago and its D-3 arm stays `scheduled`. Last come the orders that followed: an emailed guest buys at the plan
   segment's own mid rate (x1.3 for launch), the holdout and bounced guests at half of it, plus walk-up buyers and
   new guests on the past nights, with door scans on about 9 in 10 tickets.
7. Boots a third time and posts the spam complaints as signed Resend webhooks (`/public/webhooks/resend`), which
   mark the recipient, add the `spam` marketing suppression and the objection, as in production. It also posts the
   one-click unsubscribes (`/public/unsubscribe/{token}`). A one-click unsubscribe is stamped with the request time,
   so seed.sh then moves those consent, opt-out and recipient timestamps to a day after the send; otherwise they
   would fall outside the d1/d7 windows.
8. Boots a fourth time with the local-only loader path. The fan-feature recompute runs, and `DemoOutcomeRun` calls
   `OutcomeCollector.run()` once at startup. That is the same code as the 08:00 Paris job: it stores d1/d7 outcomes
   and rebuilds `response_calibration`. seed.sh then logs in as both demo users and calls `GET /audience/metrics`,
   `GET /events/{id}/audience-plan` for every upcoming event (twice, so the summary written after the first GET is
   there), `GET /events/{id}/audience-plan/outcome` for the three invited events and the draft's invite-on-publish.
   It prints the class counts and the calibration rows.

Fan features, classes, the ConsentGate, invitations, holdouts, outcomes, calibration, plans and summaries are
computed by the app, not written by SQL. SQL writes only what no endpoint can while sends are off: past-dated
history, the sent/scheduled campaign states and their recipients, and orders (checkout needs Stripe).

**`DemoOutcomeRun.java` is local-only.** It is not under `src/main`, it is not in the jar, and it adds no endpoint.
seed.sh compiles it into `$LOG_DIR/demo-loader/demo-outcome-run.jar`, and only the fourth boot puts that directory on
the classpath (`IMIN_DEMO_LOADER_PATH`, Spring Boot's `PropertiesLauncher`). A plain `run-api.sh` never loads it.

Before each boot, `seed.sh` clears `shedlock` in `imin_demo`: the startup backfill and the collector hold their locks
for a minute, so a quick reboot would otherwise skip them. The demo api keeps its rate-limit buckets in Redis
database 5, and `seed.sh` empties that database, so a re-seed is not throttled by the last run.

## Commands

All from the imin-api worktree root (`/Users/ivan/imin/imin-api/.claude/worktrees/ap-demo-seed`, or any
checkout of the branch that carries `scripts/demo/`).

```bash
# Seed (2-3 minutes, four api boots; rebuilds target/imin-api-*.jar first when it is missing or older than src/main).
# Logs, the report and engage.txt go to $TMPDIR/imin-demo/ (override with IMIN_DEMO_LOG_DIR).
scripts/demo/seed.sh                   # seed, then stop the api
scripts/demo/seed.sh --keep-running    # seed and leave the api on :8095

# Boot the api on :8095 against imin_demo later (Ctrl-C to stop).
scripts/demo/run-api.sh
```

`run-api.sh` reads only `KEY=VALUE` lines from the main imin-api checkout's `.env.local` (override with
`IMIN_ENV_FILE`), because the app does not boot with `MEDIA_ENABLED=false` today and needs the R2 settings
there. It then forces `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/imin_demo`, `SERVER_PORT=8095`,
`SPRING_DOCKER_COMPOSE_ENABLED=false`, `SPRING_FLYWAY_OUT_OF_ORDER=true`, a throwaway
`IMIN_TICKET_SIGNING_SECRET`, `RESEND_API_KEY=re_dummy`, `STRIPE_SECRET_KEY=sk_test_demo_dummy`,
`REDIS_URL=redis://localhost:6380/5`, and localhost URLs for the buyer site (`:3000`) and dashboard
(`:5173`). It also pins `IMIN_AUDIENCE_PLAN_SENDS_ENABLED=false` whatever `.env.local` says,
`IMIN_AUDIENCE_PLAN_SUMMARY_DAILY_CAP=0` (template summaries, no model call; `IMIN_DEMO_SUMMARY_CAP=50` lets the
model run if `.env.local` has an OpenRouter key), and throwaway `RESEND_WEBHOOK_SECRET` /
`IMIN_MARKETING_UNSUBSCRIBE_SECRET` values that `seed_api_steps.py` signs the local complaints and unsubscribes with.
With the dummy Stripe key, `PaidFulfilmentReconciler` logs an `Invalid API Key` ERROR every
few minutes; that is expected and harmless.

Quick api check with the api running:

```bash
TOKEN=$(curl -s -A imin-demo -H 'Content-Type: application/json' \
  -d '{"email":"demo@imin.test","password":"VechirkaDemo2026!"}' \
  http://localhost:8095/api/v1/auth/login | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s -A imin-demo -H "Authorization: Bearer $TOKEN" http://localhost:8095/api/v1/audience/metrics
curl -s -A imin-demo -H "Authorization: Bearer $TOKEN" \
  http://localhost:8095/api/v1/events/c07916f2-69be-d05f-6a4e-1f6c39320f08/audience-plan
```

### Dashboard (imin-webapp) against the demo api

Use a clean checkout of `origin/main` (for example a worktree), not a checkout on a feature branch:

```bash
cd /Users/ivan/imin/imin-webapp
git fetch origin main && git worktree add .claude/worktrees/demo-view origin/main --detach
cd .claude/worktrees/demo-view && npm ci
VITE_API_BACKEND=http://localhost:8095 VITE_PUBLIC_BASE_URL=http://localhost:3000 npm run dev   # :5173
```

Log in at http://localhost:5173/login as `demo@imin.test` / `VechirkaDemo2026!`.

### Buyer site (imin-public) for the door and survey pages

```bash
cd /Users/ivan/imin/imin-public
git fetch origin main && git worktree add .claude/worktrees/demo-view origin/main --detach
cd .claude/worktrees/demo-view && corepack pnpm install --frozen-lockfile
NEXT_PUBLIC_API_BASE=http://localhost:8095 corepack pnpm dev   # :3000
```

## Screens to open

Event ids are fixed, so these links work after every re-seed.

Dashboard as `demo@imin.test`:

- Audience: http://localhost:5173/audience, tabs **Overview** (can-email 424, guest classes, taste, show-up and
  came-back ranges; the metrics carry 12 soft opt-in consents and 0 soft opt-in mailable), **Members** (open a loyal, an imported, an unsubscribed,
  an `erase_pending` and a soft opt-in member in the drawer; the consent trail is in the drawer; a door sign-up such as
  `adrien.thomas.105@example.test` has an unconfirmed `door_qr` record and stays not mailable), **Segments**,
  **Imports** (the Shotgun import with its accepted and rejected rows) and **Suppression** (the 3 `spam` rows from
  the complaints next to the manual and hard-bounce ones). The upcoming-plans list reads `GET /audience-plans`.
- Outcome, phase **d7**, three invited segments, a holdout (`baseline`) and `too_few` lift:
  http://localhost:5173/events/23a79022-2c63-ddf8-4ba0-310631f92e5f/audience (Vechirka: Late Summer Session)
- Outcome, phase **d1**, two segments without a holdout (`no_holdout`), a complaint:
  http://localhost:5173/events/cdb1d0a1-e535-9e1f-7093-5a185b36a66e/audience (Bassline Thionville II)
- Event Audience tab, warm and `medium`, calibrated (`own`) segments, template summary, and the **live** outcome
  with one sent arm and `nextWave`:
  http://localhost:5173/events/c07916f2-69be-d05f-6a4e-1f6c39320f08/audience (Nuit Longue)
- Event Audience tab, warm with the gap beyond the local crowd (`gapExceedsTribe`, `rethink_target`):
  http://localhost:5173/events/4987ee23-d53b-1814-d9a4-cb312af9461e/audience (Winter Festival)
- Builder step 4 on the draft, with the stored invite-on-publish intent (loyal/other and first-timer/same):
  http://localhost:5173/events/new/fa8ae451-7ad4-1dfe-86a9-378b58e40140/preview (Bass Session #4). The plan now shows
  first-timer/other rather than first-timer/same, so the intent names a segment the current plan no longer shows; on
  publish that segment is skipped (live-test item for stored invitations).
- Door QR and survey switches with their links and counts (Audience tab of the past event):
  http://localhost:5173/events/81038411-d7fc-d676-f489-61158914db66/audience (Vechirka: Rentrée)
- Marketing: http://localhost:5173/marketing lists the arm campaigns (`Audience plan · <class>/<fit> · <arm> · <event>`):
  `sent` for both past nights and Nuit Longue's launch, `scheduled` for Nuit Longue's D-3. Open one for its recipient
  counts. Sends are off, so approving a draft arm answers `409 AUDIENCE_SENDS_DISABLED`.

Dashboard as `demo-cold@imin.test` (log out first):

- Event Audience tab, cold, template summary: http://localhost:5173/events/0cd65594-05b2-ab15-d708-9f84074497a7/audience
  (Autumn Trio)

Buyer site:

- Door QR page: http://localhost:3000/e/81038411-d7fc-d676-f489-61158914db66/door?t=vechirkademodoor2026
- Survey page: http://localhost:3000/e/81038411-d7fc-d676-f489-61158914db66/survey?t=vechirkademosurvey2026

API checks with the api running (after the login in "Commands"):

```bash
curl -s -A imin-demo -H "Authorization: Bearer $TOKEN" \
  http://localhost:8095/api/v1/events/23a79022-2c63-ddf8-4ba0-310631f92e5f/audience-plan/outcome   # d7
curl -s -A imin-demo -H "Authorization: Bearer $TOKEN" \
  http://localhost:8095/api/v1/events/cdb1d0a1-e535-9e1f-7093-5a185b36a66e/audience-plan/outcome   # d1
curl -s -A imin-demo -H "Authorization: Bearer $TOKEN" \
  http://localhost:8095/api/v1/events/c07916f2-69be-d05f-6a4e-1f6c39320f08/audience-plan/outcome   # live, nextWave
```

## Removing it

```bash
PGPASSWORD=secret psql -h localhost -p 5433 -U myuser -d postgres -c "DROP DATABASE imin_demo"
docker exec imin-redis redis-cli -n 5 FLUSHDB
```
