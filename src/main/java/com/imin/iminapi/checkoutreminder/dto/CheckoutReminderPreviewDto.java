package com.imin.iminapi.checkoutreminder.dto;

import java.util.UUID;

/**
 * The reminder as a buyer would get it, rendered from the org's soonest night with a tier on sale.
 * Every field but {@code locale} is null when no night is on sale.
 */
public record CheckoutReminderPreviewDto(
        String locale,
        String from,
        String subject,
        String preheader,
        String html,
        String text,
        BasedOn basedOn
) {
    /** The live facts the preview shows; {@code qty} is what the link would pick. */
    public record BasedOn(UUID eventId, String eventName, String tierName, int qty, int remaining) {}
}
