package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Test-only fixture seeder for {@link com.imin.iminapi.marketing.service.MomentumEvaluator} tests.
 * Placed under {@code src/test}, so it is a bean only in the test context.
 *
 * <p>Seeds a real {@link Organization} (with its 7 prebuilt segments, so the evaluator's
 * {@code defaultTargetSegmentId("Repeat")} resolves against the un-mocked {@code SegmentService}),
 * a {@code LIVE} on-sale {@link Event}, one {@link TicketTier} whose {@code quantity} = capacity and
 * {@code sold} = sold (the evaluator reads TICKETS-sold via {@code TicketTierRepository.sumSoldByEventId},
 * NOT order count), and a small fixed number of paid {@link Order} rows purely to drive the
 * 7-day order-arrival velocity ({@code findCreatedAtAndTotalSince}) — decoupled from ticket count.
 */
@Component
public class MomentumTestSupport {

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final OrderRepository orders;
    private final SegmentService segmentService;

    public MomentumTestSupport(OrganizationRepository orgs, UserRepository users,
                               EventRepository events, TicketTierRepository tiers,
                               OrderRepository orders, SegmentService segmentService) {
        this.orgs = orgs;
        this.users = users;
        this.events = events;
        this.tiers = tiers;
        this.orders = orders;
        this.segmentService = segmentService;
    }

    /**
     * Seed a LIVE, on-sale, future event with tiers summing to {@code sold}/{@code capacity}
     * and a few velocity orders. Returns the event id.
     */
    public UUID seedLiveEvent(int sold, int capacity, Instant onSaleAt, Instant startsAt) {
        Organization org = new Organization();
        org.setName("Momentum Test Org");
        org.setSlug("momentum-" + UUID.randomUUID());
        org.setContactEmail("owner+" + UUID.randomUUID() + "@example.com");
        org.setCountry("IE");
        org.setTimezone("UTC");
        Organization savedOrg = orgs.save(org);
        UUID orgId = savedOrg.getId();

        // Provision prebuilt segments (idempotent) so defaultTargetSegmentId("Repeat") resolves.
        segmentService.ensurePrebuiltSegments(orgId);

        // Owner user — events.created_by FKs users(id), so a real row is required.
        User owner = new User();
        owner.setOrgId(orgId);
        String email = "owner+" + UUID.randomUUID() + "@example.com";
        owner.setEmail(email);
        owner.setEmailLower(email.toLowerCase());
        owner.setRole(UserRole.OWNER);
        User savedOwner = users.save(owner);

        Event event = new Event();
        event.setOrgId(orgId);
        event.setName("Momentum Test Event");
        event.setSlug("event-" + UUID.randomUUID());
        event.setStatus(EventStatus.LIVE);
        event.setDeletedAt(null);
        event.setOnSaleAt(onSaleAt);
        event.setStartsAt(startsAt);
        event.setCreatedBy(savedOwner.getId());
        Event savedEvent = events.save(event);
        UUID eventId = savedEvent.getId();

        // One tier: quantity == capacity, sold == sold. Evaluator reads SUM(tier.sold).
        TicketTier tier = new TicketTier();
        tier.setEventId(eventId);
        tier.setName("General Admission");
        tier.setPriceMinor(2500);
        tier.setQuantity(capacity);
        tier.setSold(sold);
        tier.setReserved(0);
        tier.setEnabled(true);
        tier.setSortOrder(0);
        tiers.save(tier);

        // A handful of paid orders inside the last 7 days — velocity only, NOT the sold figure.
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            Order o = new Order();
            o.setToken("tok-" + UUID.randomUUID());
            o.setEventId(eventId);
            o.setOrgId(orgId);
            o.setEmail("buyer" + i + "@example.com");
            o.setTotalMinor(2500);
            o.setCurrency("EUR");
            o.setPaymentMethod("free");
            o.setCreatedAt(now.minusSeconds((long) i * 3600));
            orders.save(o);
        }

        return eventId;
    }

    /** Org id backing a seeded event. */
    public UUID orgIdOf(UUID eventId) {
        return events.findById(eventId).orElseThrow().getOrgId();
    }

    /**
     * Build a USER {@link AuthPrincipal} scoped to {@code orgId} (OWNER role), mirroring
     * {@code @WithStubOrganizer}. Used by MomentumServiceTest for list/approve/dismiss calls.
     */
    public AuthPrincipal principalFor(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    /** Move the event's start into the past so the expiry sweep treats live suggestions as stale. */
    public void markStarted(UUID eventId) {
        Event e = events.findById(eventId).orElseThrow();
        e.setStartsAt(Instant.now().minusSeconds(3600));
        events.save(e);
    }
}
