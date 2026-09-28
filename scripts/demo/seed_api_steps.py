#!/usr/bin/env python3
"""Demo seed steps that go through the running api (local only): contact import, door QR sign-ups,
survey answers, erasures, invitations, complaints and unsubscribes, then a report of the audience metrics, plans and
outcomes. Standard library only."""
import base64
import datetime
import hashlib
import hmac
import json
import random
import sys
import time
import urllib.error
import urllib.request
import uuid

API = "http://localhost:8095"
UA = "imin-demo-seed/1"
# The throwaway keys run-api.sh gives the demo api; they sign nothing anywhere else.
RESEND_WEBHOOK_SECRET = "whsec_ZGVtby1vbmx5LXJlc2VuZC13ZWJob29rLXNlY3JldA=="
UNSUBSCRIBE_SECRET = b"demo-only-unsubscribe-secret-not-for-prod"
DOOR_TEXT = ("Email me about events by Vechirka. I agree to receive email marketing and can unsubscribe "
             "any time, one click in every email.")
GENRES = ["house & techno", "bass & hard dance", "club / open format", "hip-hop & r&b",
          "latin & afrobeats", "rock & alternative", "pop", "jazz & acoustic"]
COMMUNES = ["Metz", "Metz", "Metz", "Nancy", "Thionville", "Montigny-lès-Metz", "Woippy", "Pont-à-Mousson",
            "Hayange", "Longwy"]
FIRST = ["Aurélie", "Bastien", "Céline", "Damien", "Estelle", "Fabien", "Gaëlle", "Loïc", "Mélanie", "Nicolas",
         "Oleh", "Daryna", "Petro", "Zlata", "Artem", "Uliana"]
LAST = ["Gauthier", "Henry", "Jacob", "Kieffer", "Lambert", "Mercier", "Noël", "Perrin", "Humeniuk",
        "Karpenko", "Ostapchuk", "Zinchenko"]


def call(method, path, body=None, token=None, content_type="application/json", headers=None):
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", content_type)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            raw = r.read()
            try:
                return r.status, (json.loads(raw) if raw else None)
            except ValueError:
                return r.status, raw.decode(errors="replace")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw.decode(errors="replace")


def login(email, password):
    status, body = call("POST", "/api/v1/auth/login", {"email": email, "password": password})
    if status != 200:
        sys.exit(f"login {email} failed: {status} {body}")
    return body["token"]


def ascii_slug(s):
    return (s.lower().translate(str.maketrans("éèêëàâäîïôöùûüç", "eeeeaaaiioouuuc"))
            .replace(" ", "").replace("'", ""))


def person(rng, idx, domain_tag):
    f, l = rng.choice(FIRST), rng.choice(LAST)
    return f, l, f"{ascii_slug(f)}.{ascii_slug(l)}.{domain_tag}{idx}@example.test"


def import_csv(token, rng, today):
    """One import from a ticketing platform export: accepted rows with proof, plus each reject reason."""
    rows = [["email", "first_name", "last_name", "source_platform", "export_date", "events",
             "last_purchase_date", "marketing_status", "proof_ref"]]
    export_date = today
    kinds = ["accepted"] * 60 + ["missing_proof"] * 10 + ["not_opted_in"] * 8 + ["unsubscribed"] * 4 \
        + ["invalid_export_date"] * 3
    for i, kind in enumerate(kinds, start=1):
        f, l, email = person(rng, i, "imp")
        last_purchase = f"2026-0{rng.randint(1, 8)}-{rng.randint(10, 28)}"
        events = rng.choice(["Techno Bunker Metz 2025", "Rooftop House Nancy", "Techno Bunker Metz 2025; Rooftop House Nancy"])
        status, proof, platform, exp = "opted_in", f"shotgun-export-{export_date}.csv#row-{i}", "Shotgun", export_date
        if kind == "missing_proof":
            proof = ""
        elif kind == "not_opted_in":
            status = "none"
        elif kind == "unsubscribed":
            status = "unsubscribed"
        elif kind == "invalid_export_date":
            exp = "2099-01-01"
        rows.append([email, f, l, platform, exp, events, last_purchase, status, proof])
    csv = "\n".join(",".join('"' + c.replace('"', '""') + '"' for c in r) for r in rows).encode()

    boundary = "----imindemo" + uuid.uuid4().hex
    parts = []
    for name, value in [("attestation", "true"), ("attestationVersion", "2026-09-27"),
                        ("proofRef", f"Shotgun consent export {export_date}")]:
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode())
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"shotgun-export.csv\"\r\n"
                 f"Content-Type: text/csv\r\n\r\n".encode() + csv + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    status, body = call("POST", "/api/v1/audience/import", b"".join(parts), token,
                        content_type=f"multipart/form-data; boundary={boundary}")
    if status != 200:
        sys.exit(f"import failed: {status} {body}")
    print("import:", {k: body.get(k) for k in ("total", "imported", "rowsExplicit", "rowsNoBasis",
                                                 "rowsUnsubscribed", "skippedOther")})


def door_signups(rng, event_id, token_param, existing):
    ok = 0
    new_people = [person(rng, i, "door")[2] for i in range(1, 16)]
    for email in new_people + existing:
        status, body = call("POST", f"/api/v1/public/events/{event_id}/door-optin", {
            "token": token_param, "email": email, "consentGiven": True, "consentText": DOOR_TEXT,
            "consentTextVersion": "door-org-named-2026-09", "locale": rng.choice(["fr", "fr", "en", "uk"])})
        if status == 200:
            ok += 1
        else:
            print("door sign-up refused:", status, body)
    print(f"door sign-ups: {ok} of {len(new_people) + len(existing)} ({len(existing)} existing guests)")


def survey_answers(rng, survey_token, existing):
    ok = 0
    consenting = [person(rng, i, "survey")[2] for i in range(1, 6)] + existing
    for i in range(40):
        body = {
            "homeCommune": rng.choice(COMMUNES) if rng.random() < 0.85 else None,
            "otherGenres": rng.sample(GENRES[1:], rng.randint(0, 2)),
            "heardFrom": rng.choice(["friend", "friend", "instagram", "instagram", "tiktok", "poster", "imin", "other"]),
            "ageBand": rng.choice(["18_24", "25_34", "25_34", "35_44", "45_plus"]),
            "firstTime": rng.random() < 0.3,
            "noticeVersion": "survey-notice-2026-10",
            "locale": rng.choice(["fr", "fr", "en", "uk"]),
        }
        if i < len(consenting):
            body.update({"consentGiven": True, "email": consenting[i], "consentText": DOOR_TEXT,
                         "consentTextVersion": "survey-org-named-2026-09"})
        status, resp = call("POST", f"/api/v1/public/surveys/{survey_token}", body)
        if status == 200:
            ok += 1
        else:
            print("survey answer refused:", status, resp)
    print(f"survey answers: {ok} of 40 ({len(consenting)} with an email sign-up)")


def populate(args):
    event_id, door_token, survey_token, today = args[:4]
    existing = [e for e in args[4].split(",") if e] if len(args) > 4 else []
    rng = random.Random(2026)
    token = login("demo@imin.test", "VechirkaDemo2026!")
    import_csv(token, rng, today)
    door_signups(rng, event_id, door_token, existing[:8])
    survey_answers(rng, survey_token, existing[8:13])


def plan_of(token, event_id):
    status, plan = call("GET", f"/api/v1/events/{event_id}/audience-plan", token=token)
    if status != 200:
        sys.exit(f"plan {event_id} failed: {status} {plan}")
    return plan


def invite_actions(plan):
    """The plan's own invite suggestions, in the shape the invitations endpoint takes. A suggested holdout of 0 (a
    segment under the holdout minimum) is left out, so the api applies its default and holds nobody out."""
    out = []
    for a in plan.get("actions") or []:
        if a.get("type") != "invite" or not a.get("arms"):
            continue
        seg = {"classKey": a["classKey"], "genreFit": a["genreFit"], "arms": [x["arm"] for x in a["arms"]]}
        if a.get("holdoutPct"):
            seg["holdoutPct"] = a["holdoutPct"]
        out.append(seg)
    return out


def invite(token, event_id, segments):
    status, body = call("POST", f"/api/v1/events/{event_id}/audience-plan/invitations", {"segments": segments}, token)
    if status != 200:
        sys.exit(f"invitations {event_id} failed: {status} {body}")
    for inv in body["invitations"]:
        arms = ", ".join(f"{a['arm']} {a['members']}" for a in inv["arms"])
        held = inv["holdout"]["members"] if inv.get("holdout") else 0
        print(f"  invited {inv['classKey']}/{inv['genreFit']}: {inv['members']} members ({arms}; holdout {held})")


def stage(args):
    """Erasure requests, invitations on the two outcome events and the on-sale event, invite-on-publish on the draft."""
    past_events, live_event, draft_event = args[0].split(","), args[1], args[2]
    erase_ids = [i for i in args[3].split(",") if i] if len(args) > 3 else []
    token = login("demo@imin.test", "VechirkaDemo2026!")
    for mid in erase_ids:
        status, body = call("POST", f"/api/v1/audience/members/{mid}/erase", token=token)
        if status != 202:
            sys.exit(f"erase {mid} failed: {status} {body}")
    print(f"erasure requests: {len(erase_ids)}")
    for ev in past_events:
        segments = invite_actions(plan_of(token, ev))
        if not segments:
            sys.exit(f"plan {ev} suggests no invitation")
        print(f"invitations for {ev} (every suggested segment):")
        invite(token, ev, segments)
    two_arm = [s for s in invite_actions(plan_of(token, live_event)) if {"launch", "d3"} <= set(s["arms"])]
    if not two_arm:
        sys.exit(f"plan {live_event} suggests no launch + d3 invitation")
    print(f"invitations for {live_event} (top segment, launch + d3):")
    invite(token, live_event, two_arm[:1])
    suggested = invite_actions(plan_of(token, draft_event))
    if not suggested:
        sys.exit(f"plan {draft_event} suggests no invitation")
    status, body = call("PUT", f"/api/v1/events/{draft_event}/audience-plan/invite-on-publish",
                        {"segments": suggested[:2]}, token)
    if status != 200:
        sys.exit(f"invite-on-publish {draft_event} failed: {status} {body}")
    print(f"invite-on-publish for {draft_event}: {[s['classKey'] + '/' + s['genreFit'] for s in suggested[:2]]}")


def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def engage(args):
    """Rows kind|org|membership|campaign|provider_message_id|email|occurred_at: a spam report through the signed
    Resend webhook, or a one-click unsubscribe through the public link (seed.sh backdates those afterwards)."""
    key = base64.b64decode(RESEND_WEBHOOK_SECRET[len("whsec_"):])
    done = {"complaint": 0, "unsubscribe": 0}
    with open(args[0]) as f:
        rows = [line.rstrip("\n").split("|") for line in f if line.strip()]
    for kind, org, member, campaign, msg_id, email, at in rows:
        if kind == "complaint":
            body = json.dumps({"type": "email.complained", "created_at": at,
                               "data": {"email_id": msg_id, "to": [email]}}).encode()
            svix_id = "msg_demo_" + uuid.uuid4().hex
            ts = str(int(datetime.datetime.now(datetime.timezone.utc).timestamp()))
            sig = base64.b64encode(hmac.new(key, f"{svix_id}.{ts}.".encode() + body, hashlib.sha256).digest()).decode()
            status, resp = call("POST", "/api/v1/public/webhooks/resend", body,
                                headers={"svix-id": svix_id, "svix-timestamp": ts, "svix-signature": "v1," + sig})
        else:
            payload = b64url(f"{org}:{member}:{campaign}:email".encode())
            sig = b64url(hmac.new(UNSUBSCRIBE_SECRET, payload.encode(), hashlib.sha256).digest())
            status, resp = call("POST", f"/api/v1/public/unsubscribe/{payload}.{sig}", b"",
                                content_type="application/x-www-form-urlencoded")
        if status != 200:
            sys.exit(f"{kind} for {member} failed: {status} {resp}")
        done[kind] += 1
    print(f"complaints: {done['complaint']}, one-click unsubscribes: {done['unsubscribe']}")


def summarize_plan(p):
    keys = ("mode", "capacity", "targetTickets", "mailable", "expected", "coverage", "gap",
            "gapExceedsTribe", "exclusions")
    out = {k: p.get(k) for k in keys if k in p}
    segs = p.get("segments") or []
    out["segments"] = [{k: s.get(k) for k in ("classKey", "genreFit", "mailable", "expected", "confidence")}
                       for s in segs]
    summary = p.get("summary")
    out["summary"] = None if not summary else {k: summary.get(k) for k in ("headline", "aiGenerated", "locale")}
    return out


def summarize_outcome(o):
    out = {k: o.get(k) for k in ("phase", "invited", "doorClosesAt", "computedAt", "newGuests", "nextWave",
                                 "minimumForLift")}
    out["segments"] = [{"segment": f"{s.get('classKey')}/{s.get('genreFit')}", "plannedRate": s.get("plannedRate"),
                        "plannedConfidence": s.get("plannedConfidence"),
                        "arms": [{k: a.get(k) for k in ("arm", "members", "sent", "bought", "tickets", "attended",
                                                        "unsubscribed", "complained", "responseRate", "lift", "liftStatus")}
                                 for a in s.get("arms") or []]}
                       for s in o.get("segments") or []]
    return out


def report(args):
    warm_events = args[0].split(",")
    cold_event = args[1]
    outcome_events = [e for e in args[2].split(",") if e] if len(args) > 2 else []
    draft_event = args[3] if len(args) > 3 else None
    for who, pw, events in [("demo@imin.test", "VechirkaDemo2026!", warm_events),
                            ("demo-cold@imin.test", "ObscureDemo2026!", [cold_event])]:
        token = login(who, pw)
        status, metrics = call("GET", "/api/v1/audience/metrics", token=token)
        print(f"== {who}: GET /api/v1/audience/metrics -> {status}")
        print(json.dumps(metrics, indent=1, ensure_ascii=False)[:3000])
        for ev in events:
            # The summary is written after the first GET commits, so the second one carries it.
            call("GET", f"/api/v1/events/{ev}/audience-plan", token=token)
            status, plan = None, None
            for _ in range(15):
                status, plan = call("GET", f"/api/v1/events/{ev}/audience-plan", token=token)
                if status != 200 or plan.get("summary"):
                    break
                time.sleep(1)
            print(f"== {who}: GET /api/v1/events/{ev}/audience-plan -> {status}")
            print(json.dumps(summarize_plan(plan) if status == 200 else plan, indent=1, ensure_ascii=False))
        if who != "demo@imin.test":
            continue
        for ev in outcome_events:
            status, outcome = call("GET", f"/api/v1/events/{ev}/audience-plan/outcome", token=token)
            print(f"== {who}: GET /api/v1/events/{ev}/audience-plan/outcome -> {status}")
            print(json.dumps(summarize_outcome(outcome) if status == 200 else outcome, indent=1, ensure_ascii=False))
        if draft_event:
            status, body = call("GET", f"/api/v1/events/{draft_event}/audience-plan/invite-on-publish", token=token)
            print(f"== {who}: GET /api/v1/events/{draft_event}/audience-plan/invite-on-publish -> {status}")
            print(json.dumps(body, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    {"populate": populate, "stage": stage, "engage": engage, "report": report}[sys.argv[1]](sys.argv[2:])
