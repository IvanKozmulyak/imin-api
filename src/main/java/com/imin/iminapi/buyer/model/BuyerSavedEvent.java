package com.imin.iminapi.buyer.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One event a signed-in buyer has saved — the account-side half of the saved
 * list that {@code lib/saved-events.ts} keeps in localStorage for guests.
 *
 * <p>V85 created {@code buyer_saved_events} and deliberately shipped no entity
 * ("DDL only: no JPA entities, no services and no endpoints"). This is that
 * entity, arriving with the endpoints in Release 2.
 *
 * <p>Deliberately NOT {@code Persistable}: unlike
 * {@link com.imin.iminapi.audience.model.MarketingOptOut}, which needs the
 * duplicate-key violation raised so a re-objection cannot rewrite the original
 * record, {@code PUT /buyer/saved/{eventId}} is meant to be idempotent. Letting
 * a merge quietly no-op on an already-saved event is exactly the wanted
 * behaviour, and the select-before-insert it costs is one indexed PK lookup.
 */
@Entity
@Table(name = "buyer_saved_events")
@IdClass(BuyerSavedEventId.class)
@Getter @Setter @NoArgsConstructor
public class BuyerSavedEvent {

    @Id
    @Column(name = "buyer_account_id", nullable = false)
    private UUID buyerAccountId;

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Times.nowMicros();

    public BuyerSavedEvent(UUID buyerAccountId, UUID eventId) {
        this.buyerAccountId = buyerAccountId;
        this.eventId = eventId;
    }
}
