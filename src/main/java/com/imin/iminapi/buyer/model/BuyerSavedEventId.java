package com.imin.iminapi.buyer.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite-PK class for {@link BuyerSavedEvent} — {@code (buyer_account_id, event_id)}.
 *
 * <p>Top-level and bound with {@code @IdClass}, matching
 * {@link com.imin.iminapi.audience.model.MarketingOptOutId} and
 * {@code RefundTicketId}: the id columns live on the entity itself, so an
 * {@code @Embeddable} would read as an {@code @EmbeddedId} that isn't there.
 */
@Getter @Setter @NoArgsConstructor @EqualsAndHashCode
public class BuyerSavedEventId implements Serializable {

    private UUID buyerAccountId;
    private UUID eventId;

    public BuyerSavedEventId(UUID buyerAccountId, UUID eventId) {
        this.buyerAccountId = buyerAccountId;
        this.eventId = eventId;
    }
}
