package com.imin.iminapi.predictor.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One materialized daily sales point for a tier of an event (spec §6.2): the SOLD ticket
 * count for the day plus the running cumulative total for that tier. Aggregated from the
 * orders/tickets source by {@code SalesTrajectoryJob} — never written on the checkout path.
 * Unique per {@code (event_id, tier_id, sales_date)}.
 */
@Entity
@Table(name = "event_sales_daily")
@Getter
@Setter
public class EventSalesDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "tier_id", nullable = false)
    private UUID tierId;

    /** Calendar day in the event's timezone. */
    @Column(name = "sales_date", nullable = false)
    private LocalDate salesDate;

    @Column(name = "daily_sold", nullable = false)
    private int dailySold;

    @Column(name = "cumulative_sold", nullable = false)
    private int cumulativeSold;
}
