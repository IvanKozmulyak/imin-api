package com.imin.iminapi.referral;

import com.imin.iminapi.dto.publicapi.PublicOrderResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketState;
import com.imin.iminapi.referral.dto.PublicReferralLookupResponse;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.event.TierAvailability;
import com.imin.iminapi.stripe.StripeProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only crew-link answers for the buyer site. Writes nothing; a missing unlock row is computed live. */
@Service
public class ReferralService {

    private final OrderRepository orders;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final ModerationHoldRepository holds;
    private final ReferralPerkService perks;
    private final StripeProperties stripeProps;
    private final Clock clock;

    public ReferralService(OrderRepository orders, EventRepository events, TicketTierRepository tiers,
                           ModerationHoldRepository holds, ReferralPerkService perks, StripeProperties stripeProps,
                           Clock clock) {
        this.orders = orders;
        this.events = events;
        this.tiers = tiers;
        this.holds = holds;
        this.perks = perks;
        this.stripeProps = stripeProps;
        this.clock = clock;
    }

    /**
     * The order page's crew block; every null branch is listed on {@link PublicOrderResponse}. An exception thrown here
     * leaves the caller's transaction committable, so the order page can drop the block and still answer.
     */
    @Transactional(readOnly = true, noRollbackFor = RuntimeException.class)
    public PublicOrderResponse.Referral forOrder(Order order, Event event, List<Ticket> ticketRows) {
        Instant now = clock.instant();
        if (order.isTestMode()) return null;
        if (!holdsValidTicket(ticketRows)) return null;
        if (!eligible(event, now)) return null;

        long tickets = orders.countValidReferredTickets(order.getId());
        Optional<EventReferralPerk> perk = perks.activePerk(event.getId());
        PublicOrderResponse.Perk perkDto = perk
                .map(p -> new PublicOrderResponse.Perk(p.getLabel(), p.getThreshold()))
                .orElse(null);

        PublicOrderResponse.Unlock unlock = null;
        if (perks.perksEnabled()) {
            unlock = perks.unlockOf(event.getId(), order.getId())
                    .map(u -> new PublicOrderResponse.Unlock(u.getLabel(), u.getUnlockedAt(), u.getGivenAt()))
                    .orElse(null);
            if (unlock == null && perkDto != null && tickets >= perkDto.threshold()) {
                unlock = new PublicOrderResponse.Unlock(perkDto.label(), null, null);
            }
        }
        return new PublicOrderResponse.Referral(order.getReferralCode(), shareable(event, now), tickets, perkDto,
                unlock);
    }

    /**
     * The friend's banner. Empty (a leak-safe 404) for a malformed or unknown code, a code of another event, a code
     * the current Stripe mode would not credit, and an event that is not public, cancelled or over.
     */
    @Transactional(readOnly = true)
    public Optional<PublicReferralLookupResponse> lookup(UUID eventId, String code) {
        if (!ReferralCode.isWellFormed(code)) return Optional.empty();
        Event event = events.findPublic(eventId).orElse(null);
        Instant now = clock.instant();
        if (event == null || !eligible(event, now)) return Optional.empty();
        Order referrer = orders.findByReferralCode(code).orElse(null);
        if (referrer == null || !referrer.getEventId().equals(eventId)) return Optional.empty();
        if (referrer.isTestMode() == stripeProps.isLiveKey()) return Optional.empty();
        PublicReferralLookupResponse.Perk perk = perks.activePerk(eventId)
                .map(p -> new PublicReferralLookupResponse.Perk(p.getLabel(), p.getThreshold()))
                .orElse(null);
        return Optional.of(new PublicReferralLookupResponse(perk));
    }

    private static boolean holdsValidTicket(List<Ticket> ticketRows) {
        return ticketRows.stream().map(t -> TicketState.fromWire(t.getState()))
                .anyMatch(s -> s == TicketState.ISSUED || s == TicketState.REDEEMED);
    }

    /** Not cancelled, not deleted, not over. */
    private static boolean eligible(Event event, Instant now) {
        if (event.getDeletedAt() != null) return false;
        if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.PAST) return false;
        Instant end = event.getEndsAt() != null ? event.getEndsAt() : event.getStartsAt();
        return end == null || now.isBefore(end);
    }

    /** Something can be bought right now: the same rules as the event page's Buy button. */
    private boolean shareable(Event event, Instant now) {
        if (events.findPublic(event.getId()).isEmpty()) return false;
        if (holds.salesSuspended(event.getOrgId(), event.getId())) return false;
        return tiers.findByEventIdAndEnabledTrueOrderBySortOrderAsc(event.getId()).stream()
                .anyMatch(t -> TierAvailability.isPurchasable(event, t, now));
    }
}
