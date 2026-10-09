# Fix bearer-token surfaces (wallet passes, Meta CAPI)

Repo: `imin-api` (base `origin/master` 5ce1fd39). Branch `fix-token-surfaces`.

`orders.token` opens the whole order on the buyer site; `tickets.token` opens one ticket
(GET `/api/v1/public/tickets/{token}`, `PublicOrderController.java:112-145`, returns the
door-redeemable signed QR payload). Neither belongs on a surface that is forwarded or sent
to a third party.

## Findings (base, file:line)

- `AppleWalletPassService.java:311-312` — raw order token printed on the pass back as "Order".
- `AppleWalletPassService.java:339` — `altText(t.getToken())` prints the raw ticket token under the QR.
  The raw token alone grants: the ticket page incl. the signed QR (`PublicOrderController.java:114,120`)
  and the order token (`:145`), the QR PNG (`PublicTicketAssetController.java:64-65`), the pkpass
  (`:82,115`) and the Google save link (`:160,171`). Door redeem needs the HMAC
  (`TicketRedeemService.java:68-69`, `QrPayloadSigner.java:47-62`), but the ticket page hands that out.
- `AppleWalletPassService.java:324` — `serialNumber(t.getToken())`. No web service / update endpoint
  exists (class doc :60-69, ADR-0004), so nothing keys on it server-side; but iOS uses
  passTypeId+serial to replace a re-downloaded pass, so a new serial would duplicate passes already
  on devices. The barcode message carries the same token in plain text (`QrPayloadSigner.java:44`),
  so changing the serial removes no exposure. **Left unchanged.**
- `AppleWalletPassService.java:238,247,249` — ticket token in exception messages.
- `GoogleWalletModels.java:335` — `alternateText` = raw ticket token (same as Apple altText).
- `GoogleWalletModels.java:239,332` — object id `tkt_<ticketToken>`; insert tolerates 409 and nothing
  ever updates objects (`GoogleWalletProvisioner.java:148-151`), so a new id would mint a duplicate
  object for every already-saved ticket. **Left unchanged, reported.**
- `GoogleWalletPassService.java:126-127,140-141` — messages carry no token already.
- `MetaCapiPoller.java:135-138` — raw order token sent to Meta as `event_id`. imin-public `origin/main`
  has no `fbq`/Purchase pixel call (`git grep -i "fbq\|eventID"` returns only `eventId` props), so no
  browser event depends on the raw value.

## Owner decision (2026-10-09)

No order or ticket reference on either wallet pass: the Apple "Order" back field is removed, Apple
`altText` and Google `alternateText` are omitted. The serial number and Google object id stay unchanged.

## Ordered steps

1. Tests first, run red on base:
   - `AppleWalletPassServiceTest`: the order token and `altText` appear nowhere in pass.json and the
     back fields are only `address`/`manage`; parameterized: ticket/order/event-missing exception
     messages do not contain the ticket token.
   - `GoogleWalletModelsTest`, `GoogleWalletProvisionerTest`, `GoogleWalletEndpointTest`: barcode has no
     `alternateText` (each of these pinned the raw token before).
   - `MetaCapiPollerTest`: captured Graph payload `event_id` = lowercase hex sha256(orderToken) and
     the raw token appears nowhere in the payload.
2. `AppleWalletPassService`: remove the "Order" back field and `altText`; drop the token from the
   three exception messages (use the ticket id for order/event missing).
3. `GoogleWalletModels.eventTicketObject`: `alternateText` null (NON_NULL omits it).
4. `MetaCapiPoller.buildEventMap`: `event_id` = sha256 hex of the stored order token, computed at send
   time so existing outbox rows are covered. Update the comment there and in `MetaCapiOutboxWriter:83`.

## Off-platform order/ticket token sends (survey)

By design, to the buyer (via Resend as mail carrier): `TicketIssuanceEmailer.java:111,173-174,209`,
`EventReminderSender.java:252`, `OrderRecoveryService.java:108`; free-checkout redirect
`FreeCheckoutService.java:225`. Pass manage links `AppleWalletPassService.java:514`,
`GoogleWalletProvisioner.java:247` go to the holder. Stripe metadata/success URL carry no token
(`StripeCheckoutService.java:466-467,688-731`). Third-party in this class: Meta CAPI (fixed), Google
Wallet object id/manage URI/barcode (barcode alt removed; id and URI reported).

## Affected files

- `src/main/java/com/imin/iminapi/service/ticket/AppleWalletPassService.java`
- `src/main/java/com/imin/iminapi/service/ticket/google/GoogleWalletModels.java`
- `src/main/java/com/imin/iminapi/marketing/service/MetaCapiPoller.java`
- `src/main/java/com/imin/iminapi/marketing/service/MetaCapiOutboxWriter.java` (comment only)
- `src/test/java/com/imin/iminapi/service/ticket/AppleWalletPassServiceTest.java`
- `src/test/java/com/imin/iminapi/service/ticket/google/GoogleWalletModelsTest.java`
- `src/test/java/com/imin/iminapi/service/ticket/google/GoogleWalletProvisionerTest.java`
- `src/test/java/com/imin/iminapi/controller/publicapi/GoogleWalletEndpointTest.java`
- `src/test/java/com/imin/iminapi/marketing/MetaCapiPollerTest.java`

## Verification commands

- `docker info`
- `./mvnw test -Dtest='*Wallet*Test,MetaCapi*Test,SpringContextGuardTest'`
- Full `./mvnw test` runs at ship.
