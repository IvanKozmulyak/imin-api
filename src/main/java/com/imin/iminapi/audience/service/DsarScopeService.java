package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.dto.DsarRecords;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.marketing.model.MetaCapiEvent;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.referral.ReferralPerkUnlock;
import com.imin.iminapi.referral.ReferralPerkUnlockRepository;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.util.OrderNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The half of a DSAR that lives outside the audience projection.
 *
 * <p>{@link DsarService} owns the consumer/membership/consent graph. Everything
 * a data subject actually did — the orders, the tickets, the browsing beacons
 * joined through {@code orders.anon_id}, the hashed address sent to Meta, the
 * "tell me when it drops" registrations — lives in tables that service never
 * touched, so an Art.15 export answered with the projection alone and an Art.17
 * erasure left all of it standing.
 *
 * <h2>What is erased and what is kept</h2>
 *
 * <p>Orders and tickets are <b>retained</b>: they are the invoice and the proof
 * of the ticket contract, and the accounting-retention exemption (GDPR Art.17(3)(b)
 * / (e), French Code de commerce L123-22 ten years) obliges us to keep them. The
 * retained set is written down in {@code docs/privacy/retained-after-erasure.md}
 * so the answer given to a data subject matches the code.
 *
 * <p>Everything else here has no such basis and goes: funnel beacons are deleted
 * outright, Meta CAPI rows keep the send record but lose every identifier, and
 * audit rows keep the action but lose the actor's address.
 */
@Service
public class DsarScopeService {

    private static final Logger log = LoggerFactory.getLogger(DsarScopeService.class);

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final FunnelEventRepository funnelEvents;
    private final MetaCapiEventRepository metaCapiEvents;
    private final NotifySubscriptionRepository notifySubscriptions;
    private final AuditLogRepository auditLogs;
    private final ReferralPerkUnlockRepository perkUnlocks;
    private final CheckoutReminderRepository checkoutReminders;

    public DsarScopeService(OrderRepository orders,
                            TicketRepository tickets,
                            EventRepository events,
                            FunnelEventRepository funnelEvents,
                            MetaCapiEventRepository metaCapiEvents,
                            NotifySubscriptionRepository notifySubscriptions,
                            AuditLogRepository auditLogs,
                            ReferralPerkUnlockRepository perkUnlocks,
                            CheckoutReminderRepository checkoutReminders) {
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
        this.funnelEvents = funnelEvents;
        this.metaCapiEvents = metaCapiEvents;
        this.notifySubscriptions = notifySubscriptions;
        this.auditLogs = auditLogs;
        this.perkUnlocks = perkUnlocks;
        this.checkoutReminders = checkoutReminders;
    }

    /** Art.15: every record this org holds for {@code normalizedEmail}. */
    @Transactional(readOnly = true)
    public DsarRecords collect(UUID orgId, String normalizedEmail) {
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return empty();
        }
        List<Order> buyerOrders = orders.findByOrgIdAndNormalizedEmail(orgId, normalizedEmail);
        List<UUID> orderIds = buyerOrders.stream().map(Order::getId).toList();

        Map<UUID, String> eventNames = new HashMap<>();
        for (UUID eventId : buyerOrders.stream().map(Order::getEventId).distinct().toList()) {
            events.findById(eventId).map(Event::getName).ifPresent(n -> eventNames.put(eventId, n));
        }

        Map<UUID, ReferralPerkUnlock> unlocks = orderIds.isEmpty() ? Map.of()
                : perkUnlocks.findAllById(orderIds).stream()
                        .collect(java.util.stream.Collectors.toMap(ReferralPerkUnlock::getOrderId, u -> u));
        List<DsarRecords.OrderRecord> orderRecords = buyerOrders.stream()
                .map(o -> {
                    ReferralPerkUnlock u = unlocks.get(o.getId());
                    return new DsarRecords.OrderRecord(
                            o.getId(), o.getEventId(), eventNames.get(o.getEventId()), o.getEmail(),
                            o.getTotalMinor(), o.getApplicationFeeMinor(), o.getCurrency(),
                            o.getPaymentMethod(), o.getCreatedAt(), o.getReferralCode(),
                            o.getReferredByOrderId() == null ? null : OrderNumber.code(o.getReferredByOrderId()),
                            u == null ? null : u.getUnlockedAt(), u == null ? null : u.getGivenAt());
                })
                .toList();

        List<DsarRecords.TicketRecord> ticketRecords = orderIds.isEmpty() ? List.of()
                : tickets.findByOrderIdInOrderByOrderIdAscCreatedAtAsc(orderIds).stream()
                        .map(t -> new DsarRecords.TicketRecord(
                                t.getId(), t.getOrderId(), sha256(t.getToken()), t.getTierName(),
                                t.getState(), t.getRedeemedAt(), t.getCreatedAt()))
                        .toList();

        Set<String> anonIds = anonIdsOf(buyerOrders);
        List<DsarRecords.FunnelRecord> funnelRecords = anonIds.isEmpty() ? List.of()
                : funnelEvents.findByOrgAndAnonIds(orgId, anonIds).stream()
                        .map(fe -> new DsarRecords.FunnelRecord(
                                fe.getEventId(), fe.getStage(), fe.getAnonId(),
                                fe.getUtmSource(), fe.getClient(), fe.getCreatedAt()))
                        .toList();

        List<DsarRecords.MetaCapiRecord> metaRecords = orderIds.isEmpty() ? List.of()
                : metaCapiEvents.findByOrgAndOrderIds(orgId, orderIds).stream()
                        .map(e -> new DsarRecords.MetaCapiRecord(
                                e.getOrderId(), e.getEventName(), e.getEmailSha256(),
                                sha256(e.getOrderToken()), e.getStatus(), e.getSentAt(), e.getCreatedAt()))
                        .toList();

        List<DsarRecords.NotifySubscriptionRecord> notifyRecords =
                notifySubscriptions.findByEmailIn(List.of(normalizedEmail)).stream()
                        .filter(s -> events.findById(s.getEventId())
                                .map(e -> orgId.equals(e.getOrgId())).orElse(false))
                        .map(s -> new DsarRecords.NotifySubscriptionRecord(
                                s.getId(), s.getEventId(), s.getEmail(), s.getCreatedAt()))
                        .toList();

        List<DsarRecords.CheckoutReminderRecord> reminderRecords =
                checkoutReminders.findForOrgAndEmail(orgId, normalizedEmail).stream()
                        .map(r -> new DsarRecords.CheckoutReminderRecord(r.getId(), r.getEventId(), r.getTierId(),
                                r.getQty(), r.getEmailNormalized(), r.getConsentProof(), r.getConsentTextVersion(),
                                r.getBuyerLocale(), r.getStartedAt(), r.getStatus().name(),
                                r.getReason() == null ? null : r.getReason().name(), r.getSentAt(),
                                r.getUnsubscribedAt(), r.getRecoveredOrderId()))
                        .toList();

        return new DsarRecords(orderRecords, ticketRecords, funnelRecords, metaRecords, notifyRecords, null,
                new ArrayList<>(), null, new ArrayList<>(), reminderRecords);
    }

    /**
     * Art.17: remove or anonymise everything outside the accounting exemption.
     *
     * <p>Called from inside {@code DsarService.executeErase}'s transaction, and
     * <b>before</b> the membership row goes, because the address it keys off is
     * resolved from the Consumer that step may delete.
     *
     * @return a one-line summary for the log
     */
    @Transactional
    public String eraseOutOfScopeRecords(UUID orgId, String normalizedEmail) {
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return "no address — nothing to erase";
        }
        List<Order> buyerOrders = orders.findByOrgIdAndNormalizedEmail(orgId, normalizedEmail);
        List<UUID> orderIds = buyerOrders.stream().map(Order::getId).toList();

        // Funnel beacons: deleted outright. Pure behavioural analytics, no
        // retention basis of their own, and joinable to the person through the
        // very anon_id being matched here.
        Set<String> anonIds = anonIdsOf(buyerOrders);
        int funnelDeleted = anonIds.isEmpty() ? 0
                : funnelEvents.deleteByOrgAndAnonIds(orgId, anonIds);

        // Meta CAPI outbox: keep the send record, lose the identifiers.
        int metaRedacted = orderIds.isEmpty() ? 0
                : metaCapiEvents.redactIdentifiersByOrgAndOrderIds(orgId, orderIds);

        // Audit trail: keep the row (it is the proof the erasure happened), lose
        // the actor address.
        int auditRedacted = auditLogs.redactActorEmail(orgId, normalizedEmail);

        // Checkout reminders: keyed by address alone, so a buyer with no order or membership is reached too.
        int remindersWiped = checkoutReminders.wipeForOrg(orgId, normalizedEmail, java.time.Instant.now());

        String summary = "funnel=" + funnelDeleted + " metaCapiRedacted=" + metaRedacted
                + " auditActorRedacted=" + auditRedacted + " checkoutRemindersWiped=" + remindersWiped
                + " ordersRetained=" + buyerOrders.size();
        log.info("[dsar] out-of-scope erase org={} {}", orgId, summary);
        return summary;
    }

    /**
     * The session ids this buyer's orders carry. {@code orders.anon_id} is
     * nullable — it exists only from V62 and only for web checkouts — and a null
     * in an {@code IN} list would match nothing while making the parameter
     * nullable, so nulls are dropped rather than passed through.
     */
    private static Set<String> anonIdsOf(List<Order> buyerOrders) {
        Set<String> ids = new LinkedHashSet<>();
        for (Order o : buyerOrders) {
            String anonId = o.getAnonId();
            if (anonId != null && !anonId.isBlank()) ids.add(anonId);
        }
        return ids;
    }

    /**
     * A ticket token is a bearer credential — it loads the ticket and mints a
     * wallet pass — so the export carries its SHA-256 instead. The subject can
     * still match a line against a ticket they hold; a forwarded export cannot
     * be used to claim one.
     */
    static String sha256(String value) {
        if (value == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE; if it is missing, returning the
            // raw token would leak a credential, so return nothing at all.
            return null;
        }
    }

    private static DsarRecords empty() {
        return new DsarRecords(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), null, new ArrayList<>(), null, new ArrayList<>(),
                new ArrayList<>());
    }
}
