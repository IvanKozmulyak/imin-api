package com.imin.iminapi.marketing.stock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.imin.iminapi.dto.publicapi.PublicTierDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.event.TierAvailability;
import com.imin.iminapi.stripe.StripeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads an event's tiers into a {@link StockFacts.Snapshot} with {@link TierAvailability} and {@link PublicTierDto#from},
 * so the numbers equal the event page's; also the one JSON form of stored facts.
 */
@Component
public class StockFactsReader {

    private static final Logger log = LoggerFactory.getLogger(StockFactsReader.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final ModerationHoldRepository holds;
    private final StripeProperties stripe;

    public StockFactsReader(EventRepository events, TicketTierRepository tiers, ModerationHoldRepository holds,
                            StripeProperties stripe) {
        this.events = events;
        this.tiers = tiers;
        this.holds = holds;
        this.stripe = stripe;
    }

    /** The org's event as buyers can buy it at {@code now}; empty when it is deleted or not this org's. */
    public Optional<StockFacts.Snapshot> read(UUID orgId, UUID eventId, Instant now) {
        Event e = events.findActive(eventId).filter(ev -> orgId.equals(ev.getOrgId())).orElse(null);
        if (e == null) return Optional.empty();
        return Optional.of(snapshot(e, now));
    }

    /** {@link #read} for an event already loaded. */
    public StockFacts.Snapshot snapshot(Event e, Instant now) {
        int bps = stripe.getApplicationFeeBps();
        int fixed = stripe.getApplicationFeeFixedMinor();
        List<StockFacts.Tier> rows = tiers.findByEventIdOrderBySortOrderAsc(e.getId()).stream()
                .map(t -> tier(t, e, now, bps, fixed))
                .toList();
        return new StockFacts.Snapshot(onSale(e, now), e.getSaleClosesAt(), e.getTimezone(), e.getCurrency(), rows, now);
    }

    private static StockFacts.Tier tier(TicketTier t, Event e, Instant now, int bps, int fixed) {
        long allIn = PublicTierDto.from(t, e, now, bps, fixed).priceAllInMinor();
        return new StockFacts.Tier(t.getId(), t.getName(), t.isEnabled(), t.getQuantity(), TierAvailability.remaining(t),
                t.getSaleStartsAt(), t.getSaleClosesAt(), t.getSortOrder(), allIn);
    }

    // Live, public, not suspended, inside the open-sale window of EventRepository.findMomentumCandidates.
    private boolean onSale(Event e, Instant now) {
        if (e.getDeletedAt() != null || e.getStatus() != EventStatus.LIVE) return false;
        if (e.getVisibility() != EventVisibility.PUBLIC) return false;
        if (e.getStartsAt() == null || !e.getStartsAt().isAfter(now)) return false;
        if (e.getOnSaleAt() != null && e.getOnSaleAt().isAfter(now)) return false;
        if (e.getSaleClosesAt() != null && !e.getSaleClosesAt().isAfter(now)) return false;
        return !holds.salesSuspended(e.getOrgId(), e.getId());
    }

    /** The stored form of facts. */
    public static String json(StockFacts facts) {
        try {
            return JSON.writeValueAsString(facts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize stock facts", e);
        }
    }

    /** Stored facts, or empty for a missing or malformed value (logged; one row, never the request). */
    public static Optional<StockFacts> parse(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(JSON.readValue(json, StockFacts.class));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("[stock-alerts] unreadable stored facts: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
