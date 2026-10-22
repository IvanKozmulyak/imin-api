package com.imin.iminapi.marketing.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The event's stock-alert card: switches, live reach, tiers and every alert; {@code enabled} is the platform switch. */
public record StockAlertsDto(boolean enabled, Settings settings, Reach reach, List<Tier> tiers,
                             List<StockAlertDto> alerts, String timezone, String currency) {

    /** {@code lastTicketsThreshold} null = auto; {@code mode} ask|auto; the auto fields are set only in auto mode. */
    public record Settings(boolean lastTicketsOn, Integer lastTicketsThreshold, boolean priceUpOn,
                           int priceUpLeadHours, String mode, Instant autoEnabledAt, UUID autoEnabledBy) {}

    /** canReceive plus every leftOut count equals the ConsentGate-mailable people; the floor hours explain recentlyEmailed. */
    public record Reach(int canReceive, LeftOut leftOut, int frequencyFloorHours) {}

    public record LeftOut(int haveTicket, int alreadyAlerted, int recentlyEmailed, int eventCap, int monthlyCap,
                          int noConsent, int experimentHoldout) {}

    /** A tier now with its last-tickets threshold; next and priceUpReadyAt (lead start) only when a price-up claim holds. */
    public record Tier(UUID tierId, String name, boolean enabled, boolean onSale, int remaining, int quantity,
                       int threshold, long priceAllInMinor, Instant closesAt, Next next, Instant priceUpReadyAt) {}

    public record Next(UUID tierId, String name, long priceAllInMinor) {}
}
