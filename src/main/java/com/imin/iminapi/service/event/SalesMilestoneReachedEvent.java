package com.imin.iminapi.service.event;

import java.util.UUID;

/**
 * Published (inside the confirmSold transaction) when a ticket tier crosses a
 * sell-through milestone for the first time. {@code threshold} is 50, 80, or 100.
 * Consumed after commit by {@link SalesMilestoneNotifier}.
 */
public record SalesMilestoneReachedEvent(UUID tierId, int threshold) {}
