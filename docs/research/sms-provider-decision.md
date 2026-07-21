# SMS provider decision — France/EU marketing & notification SMS

**ClickUp:** 86cav34me · **Date:** 2026-07-21 · **Scope:** pick the outbound-SMS
aggregator for imin (French/EU event ticketing, `imin.wtf`) and record why.
Integration lands **disabled-by-default / dry-run** behind env creds — see the
billing caveat at the end.

> **Prior art.** `imin-webapp` doc `2026-07-19-sms-sending-plan-refresh.md`
> already assumed **Bird** (per marketing spec §4) and shipped
> `MarketingSmsProperties.provider=Bird`. This research **confirms Bird
> independently on France/EU grounds** rather than deviating — see "Where this
> deviates" below. Numbers are list/estimate as of 2026-07; **rates move, so
> treat the console quote as authoritative before enabling billing.**

## Decision matrix

| Criterion | **Twilio** | **Vonage** (Ericsson) | **Bird** (ex-MessageBird) |
|---|---|---|---|
| Per-SMS **France** (mobile, 1 segment) | ~$0.0798 (≈€0.073) list | ~€0.045–0.055 (EU ~15–30% under Twilio) | ~€0.04–0.06 (base from ~$0.006, FR higher) |
| Per-SMS **Germany** | €0.0070 | €0.0050 (~30% cheaper) | ~€0.05 band |
| Per-SMS **Spain** | EU band (confirm console) | EU band (confirm console) | EU band (confirm console) |
| Alphanumeric sender fee | Free | Free (register via Global Sender ID Portal) | Free (register per route) |
| Number/monthly fee (if VMN) | Intl number from $1.15/mo | Per-number | Per-number |
| FR alphanumeric sender ID | Supported | Supported, France-native | Supported, documented for FR |
| FR STOP / opt-out handling | US-shaped (STOP filtering, Advanced Opt-Out for 10DLC/short code); alpha senders are 1-way so STOP must be in body | **Opt-Out Assist** (managed keywords/blocklist) | **Auto STOP/HELP/START** honoring + reversible per-recipient suppression list, documented for FR (`STOP 36180`, queues in quiet hours) |
| FR quiet-hours enforcement | App-side | App-side | **Queues** marketing outside 08:00–22:00 / Sun / holidays |
| GDPR / EU data residency | US parent (needs SCCs/DPA) | US parent (Ericsson; needs SCCs/DPA) | **EU-domiciled (Amsterdam)** — data stays in EU by default |
| DX: Java SDK / docs | Best-in-class docs + Java SDK + community | Solid Java SDK, smaller community | Weaker Java SDK post-pivot, but clean REST |
| DX: DLR + inbound-STOP webhooks | Yes (POST `OptOutType`) | Yes (status + inbound webhooks) | Yes (status + inbound MO webhooks) |

## France compliance requirements (apply to all providers)

These are **product/regulatory constraints**, independent of the aggregator; the
integration bakes in the ones that live in our code, the provider backstops the rest.

1. **Alphanumeric sender ID.** From **March 2026** French operators require Latin
   alphanumerics only (A–Z, 0–9), must clearly identify the brand, and must **not
   look like a phone number**. Must be **registered** per operator before sending.
   imin's sender is `IMIN`.
2. **Mandatory STOP mention (marketing).** Every *marketing* SMS in France must
   carry an opt-out: **`STOP au 36180`** (36180 is the interoperable opt-out short
   code; 36179 is the older variant). Alphanumeric senders are **one-way**, so the
   opt-out cannot be an inbound reply to our sender — it must be printed in the body
   and routed through the operator short code. Aggregators auto-append if missing.
3. **Transactional vs marketing.** *Transactional* (OTP, order/ticket confirmation)
   is **exempt** from the STOP requirement and from quiet hours. *Marketing* is
   subject to both. Our `SmsSender.send(to, body)` is the **marketing** path (adds
   STOP, requires explicit consent); a transactional path would bypass both.
4. **Quiet hours.** Marketing SMS is **blocked 22:00–08:00**, **all day Sunday**,
   and **French public holidays**. Enforce app-side at dispatch (Bird also queues
   as a backstop). Timezone: Europe/Paris for a FR-first audience.
5. **GDPR consent.** Marketing SMS needs **explicit, separate opt-in** (a ticked
   box, distinct from email consent) and **frictionless opt-out** (Art. 21). imin
   already models this: `memberships.sms_consent_status/sms_consent_basis` accepts
   **`explicit` only** (no soft opt-in), captured post-purchase by `SmsConsentService`.

## The pick — **Bird (MessageBird)**

1. **EU data residency (GDPR).** Bird is Amsterdam-domiciled, so attendee phone
   numbers stay in the EU by default — the right posture for a French ticketing
   platform; Twilio/Vonage are US-parented and lean on SCCs/DPAs.
2. **France-native compliance at EU-competitive cost.** Documented alphanumeric
   sender + auto STOP/HELP/START honoring + a reversible suppression list, and it
   **queues** marketing outside the FR quiet-hours window — imin's exact rules — at
   ~€0.04–0.06/SMS in France, well under Twilio's ~$0.08 list.
3. **Fits a lean REST integration + existing scaffolding.** We integrate via a
   single plain-`RestClient` client (no heavy SDK — so Bird's weaker Java SDK is
   moot), and `MarketingSmsProperties.provider=Bird` + spec §4 already assume it, so
   there is **no cross-repo provider churn**.

**Second source (the seam):** **Vonage** is the strongest alternative — better docs,
strong EU carrier deliverability, Opt-Out Assist, ~30% under Twilio in the EU. **Twilio**
is the DX-premium option but costlier in France with US-shaped opt-out tooling. The
integration is one client class with a `// ponytail:` note marking exactly where a
second provider would introduce an interface, so switching later is cheap.

### Where this deviates from the prior plan
It **doesn't** on the provider (still Bird). It **does** simplify the send-path
approach: the 2026-07-19 refresh's headline task was "extract a `ChannelSender`
interface and wire SMS into the dispatcher." Per this task's scope, there is **no
clean SMS channel seam today** (`EmailChannelSender` is concrete; `CampaignRepository.claimDue`
filters `WHERE channel='email'`), so we ship `SmsSender` + STOP webhook + consent
gate **standalone** and document the dispatcher wiring as a follow-up rather than
forcing the refactor now.

## Billing caveat (the real gate — restated)

**SMS costs real money per segment and imin has no metering, per-org spend cap, or
invoicing for it.** *Who pays* (organizer vs platform) is an **open business
question**. Therefore this integration:

- ships **`enabled=false` whenever `imin.sms.api-key` is blank** → **dry-run** (logs
  the would-be message, never calls the provider);
- **must not** be enabled in any live campaign path by default;
- **must not** be enabled until a billing/metering model and a per-org spend cap
  exist. Building the code is safe; **enabling it is the gated step.**

## Remaining manual (out-of-band) steps before real sends
1. Create the Bird account; obtain the API access key → `IMIN_SMS_API_KEY`.
2. Register the `IMIN` alphanumeric sender ID per French operator (+ target EU
   operators). Confirm the live REST endpoint shape (`rest.messagebird.com/messages`
   vs `api.bird.com/workspaces/...`) and reconcile `BirdSmsClient` + the inbound
   webhook signature scheme against Bird's current docs.
3. Configure Bird's inbound (MO/STOP) + delivery-receipt webhooks to
   `POST /api/v1/public/webhooks/sms`; set `IMIN_SMS_WEBHOOK_SECRET`.
4. Resolve the **billing model + per-org spend cap** — then, and only then, flip
   enablement and wire the campaign dispatcher.
