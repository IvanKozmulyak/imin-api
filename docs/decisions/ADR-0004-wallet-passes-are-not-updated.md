# ADR-0004: Wallet passes are minted, never updated

Status: Accepted · **dark in production** — every `APPLE_WALLET_*` value is unset and `GOOGLE_WALLET_ENABLED` defaults `false`
Date: 2026-08-16

Implements the 2026-08-15 plan `docs/superpowers/plans/2026-08-15-wallet-passes.md` (branch `feat/wallet-apple-hygiene`).
Referenced from `AppleWalletPassService`, `WalletEligibility`, `GoogleWalletModels` and `GoogleWalletProvisioner`.

## Context

imin issues a QR ticket. The buyer holds it as an emailed PNG, as a web page on `app.imin.wtf`, and — after
this work — as an OS-owned wallet pass: a signed `.pkpass` for Apple Wallet and an Event Ticket object for
Google Wallet. Two endpoints serve them, both unauthenticated because the 24-byte ticket token *is* the
credential:

```
GET /api/v1/public/tickets/{token}/apple-wallet.pkpass  → 200 application/vnd.apple.pkpass
GET /api/v1/public/tickets/{token}/google-wallet        → 302 https://pay.google.com/gp/v/save/<jwt>
```

The point of a wallet pass is durability: OS-owned storage that WebKit's 7-day script-storage eviction cannot
touch, a lock-screen surface at door time, and a barcode that works with the radio off. Durability cuts both
ways — a pass on a device is a copy we no longer control. Both platforms offer a way to reach back into it
(Apple: a device-registration web service plus APNs; Google: a REST `PATCH` on the object), and this ADR
records that we build neither, together with four other decisions taken during implementation whose reasons
are not recoverable from the code.

Everything below was decided against a repository that **has never talked to a real Apple certificate or a
real Google issuer**. See §What is actually proven before treating any of it as verified behaviour.

## Decision

### 1. No pass updates, on either platform

**The QR is a pure function of the ticket token.** `QrPayloadSigner` emits
`imin1.<ticketToken>.<first-16-bytes-of-HMAC-SHA256>` — no `iat`, no `exp`, no embedded state. A pass therefore
never becomes *invalid*; it stays a correct pointer to a row. The authority is `TicketRedeemService.redeem()`,
which re-reads `ticket.state` **inside a transaction**, once before the atomic UPDATE and once after to close
the race, and returns `REFUNDED` / `REVOKED` regardless of what the buyer is holding. `imin-tickets-gate` paints
both red and refuses admission. **A stale pass scans and is correctly rejected — the door consults the database,
not the pass.**

Not updating is also not a new failure mode: the emailed PNG, the screenshot our own copy tells buyers to take,
and a printed ticket are all equally unrecallable. A wallet pass is the fourth copy of a thing that was never
revocable.

**Apple's own guidance points the same way.** Pass updates ride APNs; delivery is not guaranteed, multiple
pushes from one source are coalesced, and the documented design is to update your own database and check at
redemption. Building the web service would add a second, less reliable opinion beside the one that already
decides.

**What we do instead, which is not nothing.** `WalletEligibility` refuses to mint a pass for a dead ticket:
`refunded` ⇒ `409 TICKET_ALREADY_REFUNDED`, `revoked` ⇒ `409 INVALID_STATE`, on both wallets, checked **before**
the config gate so the refusal holds with the wallet switched off. `redeemed` is deliberately allowed — the door
paints it amber, and a buyer whose phone died in the queue must not be locked out of their own record. The OS
ages the rest out on its own: Apple `expirationDate` (event end + 12h) and `relevantDates`; Google `state` and
its validity window. That covers the overwhelmingly common staleness — a pass stale because the night is over,
not because of a refund.

**The counter-argument, kept rather than dropped.** The two platforms are *not* symmetric in cost. Google's
update is **one authenticated `PATCH`** on an object we already created: no registration table, no APNs, no
device protocol. Apple's is a whole subsystem — four authenticated endpoints, a device/registration table
(a migration, GDPR-relevant device identifiers, a DSAR export obligation), an APNs credential whose topic is the
Pass Type ID, and a fan-out on every state change. So **"do Google only" is a real option, and it is the first
thing to revisit.** It is declined for v1 because a pass that self-corrects on Android and stays stale on iOS is
a worse product than one that is uniformly stale-but-correctly-rejected: support has to explain two behaviours,
and the platform with the *smaller* European nightlife share would be the one that works. Two constraints apply
if it is ever picked up: Google caps push-triggering updates at **3 per object per 24 hours**, and a change to a
**class** propagates immediately to every object referencing it (see §4).

### 2. The poster event ticket is impossible for imin, not merely unbuilt

Apple's iOS 26 poster event ticket was planned (plan Task 4, Step 5) and **cut**, for a reason no amount of
semantic-tag work reaches. From *Creating a poster event pass using semantic tags*, verbatim:

> "Poster event tickets aren't compatible with tickets that require a QR code or barcode for entry."

Every imin ticket is redeemed by scanning its QR at the door. The poster layout is an NFC-entry layout; this is
a product-level exclusion, not a missing field.

**It would not have rendered anyway.** Apple requires `eventName`, `venueName`, `venueRegionName` **and**
`venueRoom` — "if you omit any of these tags, your pass falls back to the legacy event pass style". `venueRoom`
has no column on `Event`. And **`venueRegionName` does not exist in jpasskit 0.5.8 at all**: `PKSemantics`
carries `venueName`, `venueLocation`, `venueEntrance`, `venuePhoneNumber` and `venueRoom`, with no
`@JsonAnySetter` or map escape hatch to inject the missing one. The plan's claim that it "maps cleanly to
`event.venueCity`" is true of the data and false of the library.

`preferredStyleSchemes` is therefore **deliberately absent** rather than harmlessly present — a declared intent
that provably cannot be honoured reads to the next reader like a shipped feature.
`WalletArtworkTest.thePassDoesNotClaimAPosterEventTicketItCannotRender` pins the absence.

No `artwork.png` ships either: Apple has never published a pixel spec for it, and it would have meant an R2
fetch and a re-encode on the door path, at an invented size, for a layout that cannot render. Google's
`heroImage` is unset for the mirror-image reason — its banner is ~3:1 and every imin poster is 4:5, and Google
fetches the URL at **class** insert, so a moved poster would fail the class insert permanently for every ticket
to that event.

### 3. The pkpass manifest is SHA-1, and that is correct

jpasskit hashes `manifest.json` with Guava `Hashing.sha1()` and signs it `SHA1withRSA` — identical in 0.4.1 and
0.5.8, verified by unzipping both source jars. **This is what Apple's pkpass format specifies.** The SHA-256 one
half-remembers belongs to Apple **Wallet Orders**' `order.json`, a different product. Do not conflate them.

This is recorded because it is a magnet for a well-meaning "modernisation", and the failure mode is invisible
locally: a SHA-256 manifest hashes, signs and zips perfectly, and only a real device rejects the archive.
`JpasskitCapabilityTest.theManifestIsStillSha1OverEachFile` recomputes the digest and fails such a change in CI
instead of in a buyer's hand.

### 4. One Google class per event, id `{issuerId}.evt_{eventId}`

Objects are `{issuerId}.tkt_{ticketToken}`. Both are created lazily through the `walletobjects` REST API on the
first save-link request, idempotently (read the class for its `reviewStatus`; insert the object tolerating `409`
as success), never on boot and never by hand. `reviewStatus` is `UNDER_REVIEW` on insert and never `DRAFT` —
a `DRAFT` class cannot be used to create any object and the transition off draft is one-way. The save JWT carries
only `{"eventTicketObjects":[{"id":"…"}]}`, because Google's save URL is capped at **1800 characters** and an
inline class blows through it.

A class per event is forced by the data — a class holds `eventName`, `venue` and `dateTime`, so one global class
would put a single event's name on every ticket imin ever sells. **The operational cost is the part worth
recording:**

- **A class change propagates immediately to every object referencing it.** Per event, a bad edit reaches one
  night's holders instead of everyone. That is the upside of the granularity, and it is the reason to keep it.
- **There is no template inheritance between classes.** A change wanted on *all* events is N patches.
- **Classes already created silently keep the shape they were created with.** Nothing patches anything
  (§1), so new events get the new shape and old ones diverge without a symptom.
- Therefore **a systematic class change is a backfill job over `events`** and must be written as one — not
  discovered as a bug report about one club's ticket looking different.

### 5. Five closed gate states, one buyer-facing boolean

The two wallets are independent config gates that fail **closed and quietly**. Between them they have five
closed states:

| Wallet | Closed state | Named by |
|---|---|---|
| Google | `GOOGLE_WALLET_ISSUER_ID` blank | `GoogleWalletProperties.gateReason()` |
| Google | `GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64` blank | `gateReason()` |
| Google | credentials **complete**, `GOOGLE_WALLET_ENABLED` still false | `gateReason()`, explicitly as the demo-mode hold |
| Apple | `imin.apple-wallet` incomplete or `APPLE_WALLET_ENABLED=false` | `AppleWalletProperties.fullyConfigured()` |
| Apple | config complete, credentials do not load (bad base64, wrong password, expired WWDR) | `WalletCredentialCheck`, at boot |

**The third Google state is the expected state for the whole of development**, and it is why
`GOOGLE_WALLET_ENABLED` defaults `false` while `APPLE_WALLET_ENABLED` defaults `true`: a new issuer account
sits in demo mode — passes reach only issuer admins and named testers and carry a `[TEST ONLY]` prefix — until
publishing access is granted, and that request **cannot be made until a class already exists in production**.
Enabling by default would light the buyer CTA during exactly that window. `gateReason()` exists so an operator
reading a log can tell "nobody set this up" from "we are holding for Google's review", which want opposite
responses.

**The wire deliberately collapses all five to `false`.** `PublicTicketResponse.wallet` is
`{apple:{available,url}, google:{available,url}}`, where `available` means "tap this and it works" — this
deployment can sign for that wallet **and** this ticket is one we will mint for — and `url` is non-null iff
`available`. A buyer can take exactly one action for all five closed states, so a field distinguishing them
would only be acted on wrongly, as a "coming soon" promise about a Google review we do not control; and it would
put deployment state on an unauthenticated endpoint whose credential is a token every buyer holds.
`theResponseNeverNamesAnEnvVarOrAGateReason` pins that. The one distinction a client genuinely needs — "your
ticket was refunded" versus "the wallet is off" — is already on the response as `state`.

`walletAvailable` keeps its name, type and Apple-only meaning permanently; it is deprecated and equals
`wallet.apple.available`. Repurposing it to mean "either wallet" would light the Apple CTA on Android, because
the client gate is `walletAvailable && isApplePlatform()`.

## What is actually proven, and what is not

Nothing in this feature has touched a real Apple certificate or a real Google issuer. Three tiers, kept apart on
purpose:

**Proven by the suite (2559 tests green).** The archive is well-formed: `pass.json`, `manifest.json`,
`signature` and six PNGs, every image read back out of a *generated, signed* archive and compared byte-for-byte
against the committed file with its manifest digest checked. The manifest is SHA-1. `relevantDates` survives
serialisation as a complete interval. The Google JWT is RS256 with header `typ: JWT`, `aud` a **bare string**,
`iat` in unix **seconds**, and a 256-byte signature that a matching public key verifies and a different key
rejects. All three transports — pkpass barcode, `/qr.png` decoded back out of its pixels with zxing, and the
Google object body — carry the identical `QrPayloadSigner.sign(token)` string. Error mapping: unknown token 404
before everything but the rate limiter, refunded/revoked 409 before the config gate, every non-2xx from Google
(401/403/400/5xx) and every timeout 503 and never 500. Provisioning refuses to open a socket inside a
transaction. Production YAML binding of all four Google keys through the real Boot `Binder`.

**Tested only against synthetic credentials.** Apple signing runs over `WalletTestCerts` — a WWDR-shaped
self-signed root and a leaf minted at runtime with BouncyCastle. Google signing runs over a runtime-generated
2048-bit RSA key from `GoogleTestKeys`. **These prove the archive is correctly signed by the key we gave it and
nothing more.** Apple verifies a chain it issued; Google verifies against the public half of a key *it* issued.
`WalletTestCerts`'s own Javadoc says the synthetic chain would be rejected by Apple.

**Entirely unexercised — first contact is production.** That `pay.google.com` accepts the JWT at all. That
Apple Wallet adds the archive. That the `walletobjects` class and object inserts succeed against a real issuer,
including whether a real venue-less event trips Google's require-both-`name`-and-`address` rule. Whether
`UNDER_REVIEW` is auto-promoted as documented. Whether `relevantDates` actually surfaces the pass on a lock
screen at door time, and whether the door time renders in the venue's zone on a device. That the 38pt icon and
transparent logo look right on a near-black pass. The OAuth token exchange against the real
`https://oauth2.googleapis.com/token`. Which WWDR generation the issued certificate chains to (Apple's table
says G4; the certificate itself is the authority, and `WalletCredentialCheck` will fail loudly at boot if the
supplied intermediate does not match).

## Consequences

### Positive
- No device-registration table, no APNs credential, no migration, no new GDPR-relevant identifiers, no DSAR
  export obligation. Flyway is untouched by this entire feature.
- The refusal set is exactly the door's "do not admit" set, in one place (`WalletEligibility`), for both
  wallets and all three surfaces (ticket response, order response, issuance email) via `WalletOffers`.
- Both gates fail closed. A blank value never produces an unsigned pass, a partial pass or a broken CTA — it
  produces an absent CTA and a 503 on a URL nothing links to. Neither gate can break checkout, issuance, email
  or the door.
- Google's per-request cost is bounded: 5s connect + 5s read, ~15s worst case across three calls, on one
  button; steady state after the first buyer for an event is one call.

### Negative
- **A buyer refunded after adding a pass keeps a pass that looks valid** until the event expires it, and is
  correctly refused at the door. This is the accepted cost of §1.
- `imin-public` caches `/tickets/*` in a service worker so the door works without signal, so a payload cached
  while the ticket was live keeps `available: true` after a refund and the button stays lit on that phone. The
  endpoints' 409 is not redundancy behind the field — it is the only thing true at the moment of the tap.
- Google class drift: old events keep old class shapes forever (§4).
- `notifyPreference` is never set, so no Google-side push ever fires. Correct while this ADR stands; recorded
  because the field exists and is a one-line temptation.
- jpasskit 0.5.8 pulls `pushy` (an APNs client) transitively. It is **unused** and is not a signal that the
  pass-update service is half-built.
- Pass generation is not cached — each request re-signs. Deliberate: a cache would need invalidating on refund
  and revoke to keep `WalletEligibility` honest, and a stale cached pass for a refunded ticket is exactly the
  failure this avoids. The `wallet-pass` rate-limit bucket (30 per 5 minutes per IP) bounds the cost instead.
- `associatedStoreIdentifiers` is unset, so the pass does not deep-link to the iOS app; the numeric App Store id
  does not exist until the app is submitted (ADR-0003).
- Locations are usually absent: `IMIN_GEOCODING_ENABLED` defaults false, so `venue_latitude/longitude` are NULL
  on most rows and the location-based lock-screen trigger never fires. The date trigger still does.

## Alternatives considered

- **Apple pass-update web service + APNs.** Rejected — a whole subsystem for a display nicety, against Apple's
  own advice to check at redemption. See §1.
- **Google-only object patching.** Genuinely cheap, and **declined for platform symmetry, not cost.** First
  thing to revisit. See §1.
- **Inline class-and-object JWT** (no REST provisioning). Rejected — the save URL caps at 1800 characters and
  Google's own FAQ answer for exceeding it is to pre-create via REST and put only `{"id": …}` in the JWT.
- **One global Google class.** Rejected — a class carries `eventName`, `venue` and `dateTime`.
- **Boot-time provisioning of all classes.** Rejected — network I/O in the startup path and a thundering herd on
  every Railway restart, creating classes for events nobody is buying.
- **Repurposing `walletAvailable` to mean "either wallet".** Rejected — it would light the Apple CTA on Android.
- **Keying the wallet block by device platform (`ios`/`android`) instead of vendor.** Rejected — a Google save
  is an *account* action that completes in a desktop browser and lands on the account's phone, while a `.pkpass`
  is a *file* that opens on iOS **and** macOS. The device test belongs on the client.
- **Exposing the gate reason on the wire.** Rejected — see §5.
- **Setting `voided: true` at mint time.** Moot — we cannot update a pass to set it later, and refusing to mint
  (409) is strictly better at the only moment we control.

## Triggers to revisit

1. **Refund volume on live events becomes material.** The cost of a stale-looking pass scales with it.
2. **Ticket transfer or name change ships.** It does not exist today (`grep -rn 'TicketTransfer\|transferTicket'
   src/main/java` returns nothing). It would put a *different person's* name on a live pass, which is a
   genuinely different problem from a refund.
3. **A decision to accept the platform asymmetry** in §1 — i.e. ship Google-only patching and live with iOS
   staying stale.

If any trigger fires: a new Flyway migration would start at **V94**, and `pushy` is already on the classpath as
a convenience, not a head start.
