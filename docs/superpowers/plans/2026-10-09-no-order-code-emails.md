# No order codes in emails

Owner decision 2026-10-09: no order/ticket code or identifier shown as text in any email. Tokens inside links stay.

## Removed surfaces
- `refund-confirmed{,.es,.fr,.uk}.{html,txt}` line 17 / 5: "from order #{{orderShortCode}}" dropped (heading already names the event); `RefundConfirmationEmailer.java:100` no longer sets `orderShortCode`.
- `refund-request-received-buyer{,.es,.fr,.uk}.{html,txt}`: "Reference/Referencia/Référence/Номер запиту: {{requestId}}" line dropped; `RefundRequestEmailer.java:101` no longer sets `requestId`.
- `order-recovery` mail: link text was the raw `/order/<token>` URL; HTML text is now "Event name · purchase date" (event zone, buyer locale) and the href keeps the token; the text part prints the label then the URL on the next line (`OrderRecoveryService.java`, new `EventRepository` dependency).

## Left, with reason
- `ticket-issued` / reminders: "Ticket 1 of N" is an ordinal, tokens only in hrefs and QR image URLs.
- `refund-request-notify-imin` `Org: {{orgId}}`: internal ops inbox, an org id, not an order/ticket code.
- `refund-request-link`, `password-reset`: the pasted-fallback URL is the functional link.
- Dispute / payout / milestone / notify-release / reminder: no order or ticket id rendered.

## Tests
`NoOrderCodeInEmailsTest` (unit, real templates): refund confirmation and refund-request ack in 4 locales assert the id is absent; recovery asserts href keeps the token, label is event + date (22:30Z fixture lands on 9 Oct in Paris) and the URL is not the anchor text.
