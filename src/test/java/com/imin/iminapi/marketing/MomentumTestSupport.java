package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final SegmentService segmentService;

    public MomentumTestSupport(OrganizationRepository orgs, UserRepository users,
                               EventRepository events, TicketTierRepository tiers,
                               TicketRepository tickets, OrderRepository orders,
                               SegmentService segmentService) {
        this.orgs = orgs;
        this.users = users;
        this.events = events;
        this.tiers = tiers;
        this.tickets = tickets;
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

    /**
     * Seed real {@link Ticket} rows so the evaluator's sold-per-day spark series has genuine
     * data to bucket. {@code perDay[i]} tickets are stamped at midday UTC {@code perDay.length-1-i}
     * days before now — i.e. {@code perDay} reads oldest → newest, the same order as the spark.
     *
     * <p>Midday avoids a fixture landing either side of a UTC midnight and silently shifting a
     * bucket. Tickets are {@code issued} (inside the SOLD set), so they count.
     *
     * @return the tier id the tickets were issued against
     */
    public UUID seedDailyTickets(UUID eventId, int... perDay) {
        TicketTier tier = tiers.findByEventIdOrderBySortOrderAsc(eventId).get(0);
        UUID orgId = orgIdOf(eventId);

        // tickets.order_id FKs orders(id) (V24, ON DELETE CASCADE), so the tickets need a real
        // carrier order. It is dated 30 days back — OUTSIDE the evaluator's 7-day order window
        // (findCreatedAtAndTotalSince) — so seeding a spark cannot move velocity7d and silently
        // change which trigger fires. Tickets drive the spark, orders drive velocity: the same
        // decoupling seedLiveEvent already documents.
        Order carrier = new Order();
        carrier.setToken("tok-spark-" + UUID.randomUUID());
        carrier.setEventId(eventId);
        carrier.setOrgId(orgId);
        carrier.setEmail("spark+" + UUID.randomUUID() + "@example.com");
        carrier.setTotalMinor(0);
        carrier.setCurrency("EUR");
        carrier.setPaymentMethod("free");
        carrier.setCreatedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        UUID orderId = orders.save(carrier).getId();

        Instant middayToday = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS);
        for (int i = 0; i < perDay.length; i++) {
            long daysAgo = perDay.length - 1L - i;
            Instant at = middayToday.minus(daysAgo, ChronoUnit.DAYS);
            for (int n = 0; n < perDay[i]; n++) {
                Ticket t = new Ticket();
                t.setToken("tkt-" + UUID.randomUUID());
                t.setOrderId(orderId);
                t.setEventId(eventId);
                t.setTierId(tier.getId());
                t.setTierName(tier.getName());
                t.setPriceMinor(tier.getPriceMinor());
                t.setState(Ticket.STATE_ISSUED);
                t.setCreatedAt(at);
                tickets.save(t);
            }
        }
        return tier.getId();
    }

    /**
     * Flip {@code count} of an event's tickets to {@code refunded}, mirroring what
     * {@code RefundService} does. Used to prove the spark honours the SOLD set rather than
     * counting every row ever written.
     */
    public void refundTickets(UUID eventId, int count) {
        List<Ticket> all = tickets.findAll().stream()
                .filter(t -> eventId.equals(t.getEventId()))
                .filter(t -> Ticket.STATE_ISSUED.equals(t.getState()))
                .limit(count)
                .toList();
        for (Ticket t : all) {
            t.setState(Ticket.STATE_REFUNDED);
            tickets.save(t);
        }
    }

    /** SOLD ticket timestamps for an event — exactly what the evaluator feeds the spark. */
    public List<Instant> soldTicketCreatedAt(UUID eventId, Instant since) {
        return tickets.findSoldCreatedAtSince(eventId, since);
    }

    /** Rename a seeded event — proves {@code eventName} is resolved live, not snapshotted. */
    public void renameEvent(UUID eventId, String newName) {
        Event e = events.findById(eventId).orElseThrow();
        e.setName(newName);
        events.save(e);
    }

    /** Org id backing a seeded event. */
    public UUID orgIdOf(UUID eventId) {
        return events.findById(eventId).orElseThrow().getOrgId();
    }

    /** Display name of a seeded event (for Momentum activity-log text assertions). */
    public String eventNameOf(UUID eventId) {
        return events.findById(eventId).orElseThrow().getName();
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
