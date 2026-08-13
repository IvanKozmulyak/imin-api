package com.imin.iminapi.buyer.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One drop alert on the account page (spec §4.5).
 *
 * <p>Carries enough of the event to render a card without a second call —
 * the same shape decision {@code BuyerOrdersResponse.Event} makes.
 *
 * <p>{@code notifiedAt} null means still watching; non-null means the release
 * mail already went out. The UI splits "Active" from "Completed" on exactly
 * that field, which is why it is exposed rather than folded into a boolean:
 * the completed row shows <i>when</i>.
 */
public record BuyerNotifySubscriptionResponse(
        UUID eventId,
        String eventName,
        String slug,
        Instant startsAt,
        String posterUrl,
        Instant createdAt,
        Instant notifiedAt) {}
