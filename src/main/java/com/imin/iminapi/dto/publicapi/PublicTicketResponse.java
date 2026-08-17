package com.imin.iminapi.dto.publicapi;

import java.time.Instant;
import java.util.UUID;

/**
 * Public read-side of a single ticket, surfaced by
 * {@code GET /api/v1/public/tickets/{token}}. Drives the web-ticket UI
 * (state badge, event headline, tier label, QR code, wallet button).
 *
 * <p>{@code qrPayload} is the signed {@code imin1.<token>.<hmac>} string —
 * useful for hand-rolled scanner integrations. {@code qrUrl} is the
 * server-rendered PNG that the buyer's web ticket page can simply
 * {@code <img src>} onto. {@code wallet} says which wallet passes this server
 * can mint for this ticket, and where.
 */
public record PublicTicketResponse(
        String token,
        String state,
        String tierName,
        String qrPayload,
        String qrUrl,
        boolean walletAvailable,
        TicketWallets wallet,
        Event event,
        Order order) {

    /**
     * Apple only, and permanent.
     *
     * <p>Exactly equal to {@code wallet.apple.available}, forever — the
     * controller reads it <i>off</i> that block rather than computing it
     * alongside, so the two cannot drift. New clients read {@link #wallet()};
     * this stays because {@code imin-public} reads it in production today
     * ({@code lib/api/types.ts:243}) and because a shipped mobile binary cannot
     * be force-updated. A wire field with an installed reader is a one-way door.
     *
     * <p>It is <b>not</b> repurposed to mean "either wallet". The client gate on
     * the other side is {@code walletAvailable && isApplePlatform()}, so a true
     * value earned by Google alone would light the Apple CTA on Android and hand
     * that buyer a {@code .pkpass} their device cannot open.
     *
     * <p>Its meaning did narrow in one direction, deliberately: it now folds in
     * ticket eligibility, so a refunded ticket reports {@code false} where it
     * used to report the Apple config alone. That can only ever remove a button
     * whose endpoint answers {@code 409}; it can never light one.
     *
     * @deprecated read {@code wallet.apple.available} instead.
     */
    @Deprecated
    @Override
    public boolean walletAvailable() {
        return walletAvailable;
    }

    /**
     * No {@code metaPixelId} here — the Purchase pixel is order-page-only; a web
     * ticket is opened repeatedly at the door and must not re-fire a conversion.
     */
    public record Event(UUID eventId, String name, String slug, Instant startsAt, Instant endsAt,
                        String timezone,
                        String venueName, String venueStreet, String venueCity,
                        String venuePostalCode, String venueCountry,
                        String posterUrl) {}

    /** Sibling info so the ticket page can link "back to order" without a second call. */
    public record Order(String token, String email) {}
}
