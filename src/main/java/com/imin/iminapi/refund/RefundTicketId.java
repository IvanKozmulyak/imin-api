package com.imin.iminapi.refund;

import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/** Composite-PK class for {@link RefundTicket}. */
@Embeddable
@Getter @Setter @NoArgsConstructor @EqualsAndHashCode
public class RefundTicketId implements Serializable {
    private UUID refundId;
    private UUID ticketId;

    public RefundTicketId(UUID refundId, UUID ticketId) {
        this.refundId = refundId;
        this.ticketId = ticketId;
    }
}
