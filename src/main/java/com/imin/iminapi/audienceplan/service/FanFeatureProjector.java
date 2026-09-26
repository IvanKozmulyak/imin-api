package com.imin.iminapi.audienceplan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentChanged;
import com.imin.iminapi.audience.service.MembershipProjected;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.audienceplan.engine.FanFeatureCalculator;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureTarget;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.service.ticket.TicketRedeemedEvent;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes {@code fan_features} from {@link FanFeatureCalculator} on the order, door-scan and consent paths and nightly.
 * Listeners run after commit and hand off to a bounded pool, coalescing repeats; they never throw into the source commit.
 */
@Service
public class FanFeatureProjector {

    private static final Logger log = LoggerFactory.getLogger(FanFeatureProjector.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Largest IN list sent in one query; Postgres caps bind parameters at 32,767. */
    static final int IN_CHUNK = 1000;
    private static final String ERASE_PENDING = "erase_pending";

    private final FanFeatureRepository features;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final ConsentRecordRepository consents;
    private final OrganizationRepository organizations;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final AudiencePlanAccess access;
    private final FanFeatureCalculator calculator;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Executor executor;
    /** Keys queued but not yet started; a repeat while queued is dropped, since the queued run reads the latest state. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public FanFeatureProjector(FanFeatureRepository features,
                               OrderRepository orders,
                               TicketRepository tickets,
                               EventRepository events,
                               ConsentRecordRepository consents,
                               OrganizationRepository organizations,
                               ConsumerRepository consumers,
                               MembershipRepository memberships,
                               AudiencePlanAccess access,
                               AudiencePlanLogic logic,
                               Clock clock,
                               PlatformTransactionManager txManager,
                               @Qualifier(FanFeatureExecutors.LIVE) Executor executor) {
        this.features = features;
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
        this.consents = consents;
        this.organizations = organizations;
        this.consumers = consumers;
        this.memberships = memberships;
        this.access = access;
        this.calculator = new FanFeatureCalculator(logic);
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.executor = executor;
    }

    // ---- live paths ----

    /** A purchase: follows the membership upsert, which runs on its own async commit. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMembershipProjected(MembershipProjected event) {
        if (event.orgId() == null || event.normalizedEmail() == null) return;
        dispatch("email:" + event.orgId() + "|" + event.normalizedEmail(),
                () -> recomputeByEmail(event.orgId(), event.normalizedEmail()));
    }

    /** A door scan changes the no-show count. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketRedeemed(TicketRedeemedEvent event) {
        dispatch("order:" + event.orderId(), () -> {
            Order order = orders.findById(event.orderId()).orElse(null);
            if (order == null || order.getEmailNormalized() == null) return;
            recomputeByEmail(order.getOrgId(), order.getEmailNormalized());
        });
    }

    /** Consent, unsubscribe or objection: an objection must clear taste at once, not at night. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConsentChanged(ConsentChanged event) {
        if (event.deferrable()) return; // bulk import or global toggle: the nightly recompute covers it
        dispatch("membership:" + event.membershipId(), () -> recompute(event.orgId(), event.membershipId()));
    }

    /** Never throws: the caller is an after-commit hook, and a throw there would fail a write that already succeeded. */
    private void dispatch(String key, Runnable work) {
        if (!pending.add(key)) return;
        try {
            executor.execute(() -> {
                // Released before the work starts, so a change landing mid-run queues one more run.
                pending.remove(key);
                try {
                    work.run();
                } catch (Exception e) {
                    // No throwable: bound emails can ride in SQL exception text, and errors reach Sentry.
                    log.error("FanFeatureProjector: live recompute failed ({}): {} {}", key.substring(0, key.indexOf(':')),
                            e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
                }
            });
        } catch (RejectedExecutionException e) {
            pending.remove(key);
            log.warn("FanFeatureProjector: live queue full, dropping one recompute; the nightly pass rewrites it");
        }
    }

    /** @return true when a row was written; false for an unknown, erase-pending or switched-off membership. */
    public boolean recompute(UUID orgId, UUID membershipId) {
        return features.findTarget(orgId, membershipId)
                .map(t -> recomputeBatch(List.of(t)) == 1)
                .orElse(false);
    }

    private void recomputeByEmail(UUID orgId, String normalizedEmail) {
        if (orgId == null || normalizedEmail == null) return;
        consumers.findByNormalizedEmail(normalizedEmail)
                .flatMap(c -> memberships.findByOrgIdAndConsumerId(orgId, c.getConsumerId()))
                .ifPresent(m -> recompute(orgId, m.getMembershipId()));
    }

    // ---- batch path ----

    /** Loads a page with a fixed number of queries per org; each row commits alone, so one failure skips only it. Returns rows written. */
    public int recomputeBatch(List<FanFeatureTarget> targets) {
        Map<UUID, List<FanFeatureTarget>> byOrg = targets.stream()
                .collect(Collectors.groupingBy(FanFeatureTarget::orgId, LinkedHashMap::new, Collectors.toList()));
        int written = 0;
        for (Map.Entry<UUID, List<FanFeatureTarget>> entry : byOrg.entrySet()) {
            UUID orgId = entry.getKey();
            if (!access.isEnabled(orgId)) continue;
            written += recomputeOrg(orgId, entry.getValue());
        }
        return written;
    }

    private int recomputeOrg(UUID orgId, List<FanFeatureTarget> targets) {
        ZoneId zone = zoneOf(orgId);

        Set<String> emails = new LinkedHashSet<>();
        for (FanFeatureTarget t : targets) emails.add(t.normalizedEmail());
        List<Order> orgOrders = chunked(emails, part -> orders.findByOrgIdAndNormalizedEmailIn(orgId, part));
        Map<String, List<Order>> ordersByEmail = orgOrders.stream()
                .filter(o -> o.getEmailNormalized() != null)
                .collect(Collectors.groupingBy(Order::getEmailNormalized));

        List<UUID> orderIds = orgOrders.stream().map(Order::getId).toList();
        Map<UUID, List<Ticket>> ticketsByOrder = chunked(orderIds,
                part -> tickets.findByOrderIdInOrderByOrderIdAscCreatedAtAsc(part)).stream()
                .collect(Collectors.groupingBy(Ticket::getOrderId));

        Set<UUID> eventIds = orgOrders.stream().map(Order::getEventId).collect(Collectors.toSet());
        Map<UUID, Event> eventsById = chunked(eventIds, events::findAllById).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity(), (a, b) -> a));

        List<UUID> membershipIds = targets.stream().map(FanFeatureTarget::membershipId).toList();
        Map<UUID, List<ConsentRecord>> consentsByMembership = chunked(membershipIds, consents::findByMembershipIdIn)
                .stream().collect(Collectors.groupingBy(ConsentRecord::getMembershipId));

        int written = 0;
        for (FanFeatureTarget t : targets) {
            try {
                List<Order> own = ordersByEmail.getOrDefault(t.normalizedEmail(), List.of());
                List<Ticket> ownTickets = new ArrayList<>();
                Map<UUID, Event> ownEvents = new LinkedHashMap<>();
                for (Order o : own) {
                    ownTickets.addAll(ticketsByOrder.getOrDefault(o.getId(), List.of()));
                    Event e = eventsById.get(o.getEventId());
                    if (e != null) ownEvents.putIfAbsent(e.getId(), e);
                }
                List<ConsentRecord> ownConsents = consentsByMembership.getOrDefault(t.membershipId(), List.of());
                Boolean done = tx.execute(s -> write(t, objected -> calculator.calculate(new FanFeatureCalculator.Input(
                        orgId, zone, objected, own, ownTickets, ownEvents.values(), ownConsents, List.of()), clock)));
                if (Boolean.TRUE.equals(done)) written++;
            } catch (Exception e) {
                log.warn("FanFeatureProjector: skipped membership {}: {} {}", t.membershipId(),
                        e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
            }
        }
        return written;
    }

    /**
     * Locks the membership, then decides from its current state, not the page's snapshot: an objection or an
     * erasure that committed after the page was read wins, and a concurrent writer waits instead of double-inserting.
     */
    private boolean write(FanFeatureTarget t, Function<Boolean, FanFeatureCalculator.Result> calculate) {
        Membership m = memberships.lockByIdAndOrgId(t.membershipId(), t.orgId()).orElse(null);
        if (m == null) return false;
        if (ERASE_PENDING.equals(m.getStatus())) {
            features.deleteByMembershipId(t.membershipId());
            return false;
        }
        FanFeatureCalculator.Result r = calculate.apply(m.isObjectedProfiling());
        FanFeature f = features.findById(t.membershipId()).orElseGet(() -> {
            FanFeature fresh = new FanFeature();
            fresh.setMembershipId(t.membershipId());
            return fresh;
        });
        // Always from the membership row: nothing in the schema ties the two org ids together.
        f.setOrgId(m.getOrgId());
        f.setPaidOrders(r.paidOrders());
        f.setFirstPaidPurchaseAt(r.firstPaidPurchaseAt());
        f.setLastPaidPurchaseAt(r.lastPaidPurchaseAt());
        f.setFanClass(r.fanClass());
        f.setTaste(json(new TreeMap<>(r.taste())));
        f.setCities(json(r.cities()));
        f.setFormats(json(r.formats()));
        f.setNoShowN(r.noShowN());
        f.setAvgGroupSize(r.avgGroupSize());
        f.setLastContactFromPersonAt(r.lastContactFromPersonAt());
        f.setLogicVersion(r.logicVersion());
        // Set every pass so an unchanged row is still written; the stale check reads this column.
        f.setUpdatedAt(clock.instant());
        features.save(f);
        return true;
    }

    /** The org's timezone for day counts; UTC when missing, blank or unreadable. */
    ZoneId zoneOf(UUID orgId) {
        String tz = organizations.findById(orgId).map(Organization::getTimezone).orElse(null);
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            log.warn("FanFeatureProjector: org {} has an unreadable timezone '{}', using UTC", orgId, LogSafe.redact(tz));
            return ZoneOffset.UTC;
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("fan feature JSON", e);
        }
    }

    private static <K, V> List<V> chunked(Collection<K> keys, Function<List<K>, ? extends Iterable<V>> query) {
        List<V> out = new ArrayList<>();
        List<K> all = new ArrayList<>(keys);
        for (int i = 0; i < all.size(); i += IN_CHUNK) {
            query.apply(all.subList(i, Math.min(all.size(), i + IN_CHUNK))).forEach(out::add);
        }
        return out;
    }
}
