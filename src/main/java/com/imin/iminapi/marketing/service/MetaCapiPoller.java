package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.crypto.CapiTokenCipher;
import com.imin.iminapi.marketing.graph.MetaGraphClient;
import com.imin.iminapi.marketing.model.MetaCapiEvent;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import io.sentry.Sentry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains the CAPI outbox to Meta every 30s (spec §5). Backoff 1m -> 12h per row;
 * dead after 5 attempts (-> Sentry). One row per Graph call keeps per-row backoff
 * and the idempotent row-state model simple.
 */
@Component
public class MetaCapiPoller {

    private static final Logger log = LoggerFactory.getLogger(MetaCapiPoller.class);
    private static final Duration MIN_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(12);

    private final MetaCapiEventRepository capiEvents;
    private final MetaPixelConnectionRepository connections;
    private final MetaGraphClient graph;
    private final CapiTokenCipher cipher;
    private final int batchSize;
    private final int maxAttempts;

    public MetaCapiPoller(MetaCapiEventRepository capiEvents,
                          MetaPixelConnectionRepository connections,
                          MetaGraphClient graph,
                          CapiTokenCipher cipher,
                          @Value("${imin.meta.poll-batch-size:50}") int batchSize,
                          @Value("${imin.meta.max-attempts:5}") int maxAttempts) {
        this.capiEvents = capiEvents;
        this.connections = connections;
        this.graph = graph;
        this.cipher = cipher;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${imin.meta.poll-interval-ms:30000}")
    @SchedulerLock(name = "meta_capi_poller", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void scheduledDrain() {
        drain();
    }

    /**
     * Plain, non-scheduled drain body so tests drive one pass deterministically —
     * calling this directly bypasses the {@code @SchedulerLock} proxy (whose lock
     * transaction would otherwise wrap and roll back the per-row saves). Same split
     * as {@code CampaignDispatcher.run()} -> {@code runOnce()}.
     */
    public void drain() {
        List<MetaCapiEvent> due = capiEvents.findDue(Instant.now(), PageRequest.of(0, batchSize));
        if (due.isEmpty()) return;
        for (MetaCapiEvent e : due) {
            try {
                sendOne(e);
            } catch (Exception ex) {
                log.error("MetaCapiPoller unexpected error on event {}: {}", e.getId(), ex.getMessage(), ex);
            }
        }
    }

    // No @Transactional here: it is self-invoked from drain() so Spring's proxy would not
    // apply the advice anyway (proxied methods are only advised when called through the bean
    // reference, not via `this`). Each write below is its own repository-managed transaction,
    // which is exactly what the per-row backoff/dead-letter state model needs.
    void sendOne(MetaCapiEvent e) {
        MetaPixelConnection conn = resolveConnection(e.getOrgId(), e.getPixelId());
        if (conn == null) {
            fail(e, "No pixel connection for org " + e.getOrgId() + " / pixel " + e.getPixelId());
            return;
        }
        String token;
        try {
            token = cipher.decrypt(conn.getCapiAccessTokenEnc());
        } catch (Exception dec) {
            fail(e, "Token decrypt failed: " + dec.getMessage());
            return;
        }
        Map<String, Object> event = buildEventMap(e);
        try {
            graph.sendEvents(e.getPixelId(), token, null, List.of(event));
            e.setStatus(MetaCapiEvent.STATUS_SENT);
            e.setSentAt(Instant.now());
            e.setLastError(null);
            capiEvents.save(e);
        } catch (Exception up) {
            fail(e, up.getMessage());
        }
    }

    /**
     * Resolve the connection that actually owns this row's pixel. The row's pixel_id was
     * chosen at write time by MetaCapiOutboxWriter — which prefers an event-scoped override
     * over the org-wide default — so match on pixel_id first. Only if no pixel-matched row
     * exists do we fall back to the org-wide default. Matching by pixel_id (not just
     * event_id IS NULL) means a row written under an event-override connection that has NO
     * org-wide default still decrypts and sends instead of dead-lettering.
     */
    private MetaPixelConnection resolveConnection(UUID orgId, String pixelId) {
        return connections.findByOrgIdAndPixelId(orgId, pixelId)
                .or(() -> connections.findByOrgIdAndEventIdIsNull(orgId))
                .orElse(null);
    }

    private Map<String, Object> buildEventMap(MetaCapiEvent e) {
        Map<String, Object> userData = new java.util.HashMap<>();
        userData.put("em", List.of(e.getEmailSha256()));
        if (e.getFbp() != null) userData.put("fbp", e.getFbp());
        if (e.getFbc() != null) userData.put("fbc", e.getFbc());
        Map<String, Object> customData = Map.of(
                "value", e.getValueMinor() / 100.0,
                "currency", e.getCurrency().toUpperCase(java.util.Locale.ROOT));
        Map<String, Object> event = new java.util.HashMap<>();
        event.put("event_name", e.getEventName());
        event.put("event_time", e.getEventTime());
        // event_id = order TOKEN (the only order identifier the buyer site can echo;
        // PublicOrderResponse never exposes the order UUID). Browser pixel passes the
        // same order.token as eventID → Meta dedups the browser + server Purchase.
        event.put("event_id", e.getOrderToken());
        event.put("action_source", "website");
        event.put("user_data", userData);
        event.put("custom_data", customData);
        return event;
    }

    private void fail(MetaCapiEvent e, String error) {
        short attempts = (short) (e.getAttempts() + 1);
        e.setAttempts(attempts);
        e.setLastError(error);
        if (attempts >= maxAttempts) {
            e.setStatus(MetaCapiEvent.STATUS_DEAD);
            Sentry.captureException(new IllegalStateException(
                    "Meta CAPI event " + e.getId() + " dead after " + attempts + " attempts: " + error));
            log.error("Meta CAPI event {} DEAD after {} attempts: {}", e.getId(), attempts, error);
        } else {
            e.setNextAttemptAt(Instant.now().plus(backoff(attempts)));
        }
        capiEvents.save(e);
    }

    /** 1m, 2m, 4m, ... clamped to 12h. */
    private Duration backoff(int attempts) {
        long minutes = MIN_BACKOFF.toMinutes() * (1L << Math.min(attempts - 1, 20));
        Duration d = Duration.ofMinutes(minutes);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}
