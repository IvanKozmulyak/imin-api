package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.marketing.model.MetaCapiEvent;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Writes a {@link MetaCapiEvent} outbox row for an order, best-effort, right after
 * issuance. {@code @Transactional(REQUIRED)} joins the caller's transaction when one
 * is open (the V1-webhook path, {@code handleV1Transactional}) and otherwise starts
 * its own — so this is NOT guaranteed same-TX-atomic with the Order on the V2/reconciler
 * paths. Correctness does not need atomicity: the writer re-reads the order by id, is
 * guarded, and is idempotent on {@code UNIQUE(order_id)}. Inserts iff
 * {@code orders.ads_consent} AND a pixel connection exists (event override first, else
 * org-wide). Currency is taken from {@code orders.currency} and REJECTED if null/blank —
 * never defaulted (spec §5).
 */
@Service
public class MetaCapiOutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(MetaCapiOutboxWriter.class);

    private final OrderRepository orders;
    private final MetaPixelConnectionRepository connections;
    private final MetaCapiEventRepository capiEvents;

    public MetaCapiOutboxWriter(OrderRepository orders,
                                MetaPixelConnectionRepository connections,
                                MetaCapiEventRepository capiEvents) {
        this.orders = orders;
        this.connections = connections;
        this.capiEvents = capiEvents;
    }

    /**
     * Best-effort, idempotent on {@code UNIQUE(order_id)}. {@code REQUIRED} joins the
     * caller's ambient transaction if there is one (V1-webhook path) — then the row
     * commits atomically with the Order — and otherwise runs in its own transaction
     * (V2 / reconciler paths). Either way a second call is a no-op via {@code existsByOrderId}.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void writeForOrder(UUID orderId) {
        Order order = orders.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("MetaCapiOutboxWriter: order {} not found — skipping", orderId);
            return;
        }
        if (!order.isAdsConsent()) {
            return; // no ads consent (spec §7) — no server event
        }
        if (capiEvents.existsByOrderId(orderId)) {
            return; // idempotent
        }
        MetaPixelConnection conn = resolveConnection(order.getOrgId(), order.getEventId());
        if (conn == null || !"active".equals(conn.getStatus())) {
            return; // no connected pixel — nothing to mirror
        }
        String currency = order.getCurrency();
        if (currency == null || currency.isBlank()) {
            // Guard: a null/blank currency would corrupt ROAS if defaulted. Skip loudly.
            log.warn("MetaCapiOutboxWriter: order {} has blank currency — skipping CAPI event", orderId);
            return;
        }

        MetaCapiEvent e = new MetaCapiEvent();
        e.setId(UUID.randomUUID());
        e.setOrgId(order.getOrgId());
        e.setOrderId(orderId);
        e.setOrderToken(order.getToken()); // shared browser<->CAPI dedup key (Meta event_id)
        e.setPixelId(conn.getPixelId());
        e.setEventName("Purchase");
        e.setEmailSha256(sha256Hex(EmailNormalizer.normalize(order.getEmail())));
        e.setValueMinor(order.getTotalMinor()); // primitive long on Order — no null-check
        e.setCurrency(currency);
        long createdSecs = (order.getCreatedAt() == null
                ? java.time.Instant.now() : order.getCreatedAt()).getEpochSecond();
        e.setEventTime(createdSecs);
        try {
            capiEvents.save(e);
        } catch (DataIntegrityViolationException dup) {
            // Race on UNIQUE(order_id) — another path inserted first; treat as done.
            log.info("MetaCapiOutboxWriter: order {} already has a CAPI event (race) — skipping", orderId);
        }
    }

    private MetaPixelConnection resolveConnection(UUID orgId, UUID eventId) {
        if (eventId != null) {
            var evt = connections.findByOrgIdAndEventId(orgId, eventId);
            if (evt.isPresent()) return evt.get();
        }
        return connections.findByOrgIdAndEventIdIsNull(orgId).orElse(null);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
