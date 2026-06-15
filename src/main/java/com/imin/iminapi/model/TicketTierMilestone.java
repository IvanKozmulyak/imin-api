package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Append-only record that a ticket tier has crossed a sell-through milestone
 * ({@code threshold} = 50 / 80 / 100). Written under the tier row lock in
 * {@link com.imin.iminapi.service.event.InventoryService#confirmSold(UUID)} so
 * each milestone notification fires exactly once. See V42 migration.
 */
@Entity
@Table(name = "ticket_tier_milestones")
@Getter
@Setter
public class TicketTierMilestone {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tier_id", nullable = false)
    private UUID tierId;

    @Column(nullable = false)
    private int threshold;

    @Column(name = "reached_at", nullable = false)
    private Instant reachedAt = Times.nowMicros();

    @PrePersist
    void onPersist() {
        reachedAt = reachedAt == null ? Times.nowMicros() : reachedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
