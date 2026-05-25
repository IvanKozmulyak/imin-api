package com.imin.iminapi.refund;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Join row pairing a Refund with the Tickets it covers. The UNIQUE index on
 * {@code ticket_id} (in V28 migration) enforces "a ticket can be refunded at
 * most once" at the DB layer — concurrent refund attempts on overlapping
 * ticket sets are rejected as duplicates.
 */
@Entity
@Table(name = "refund_tickets")
@Getter @Setter @NoArgsConstructor
@IdClass(RefundTicketId.class)
public class RefundTicket {
    @Id
    @Column(name = "refund_id", nullable = false)
    private UUID refundId;

    @Id
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    public RefundTicket(UUID refundId, UUID ticketId) {
        this.refundId = refundId;
        this.ticketId = ticketId;
    }
}
