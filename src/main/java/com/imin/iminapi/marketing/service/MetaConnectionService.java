package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.crypto.CapiTokenCipher;
import com.imin.iminapi.marketing.dto.MetaConnectionDto;
import com.imin.iminapi.marketing.dto.MetaFunnelDto;
import com.imin.iminapi.marketing.dto.MetaStatsDto;
import com.imin.iminapi.marketing.dto.MetaTestEventResult;
import com.imin.iminapi.marketing.dto.PutMetaConnectionRequest;
import com.imin.iminapi.marketing.graph.MetaGraphClient;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MetaConnectionService {

    /** Rolling window for the org-wide signal-health funnel (spec §8). */
    static final int FUNNEL_WINDOW_DAYS = 30;

    private final MetaPixelConnectionRepository connections;
    private final MetaCapiEventRepository capiEvents;
    private final CapiTokenCipher cipher;
    private final MetaGraphClient graph;
    private final FunnelEventRepository funnel;
    private final OrderRepository orders;

    public MetaConnectionService(MetaPixelConnectionRepository connections,
                                 MetaCapiEventRepository capiEvents,
                                 CapiTokenCipher cipher,
                                 MetaGraphClient graph,
                                 FunnelEventRepository funnel,
                                 OrderRepository orders) {
        this.connections = connections;
        this.capiEvents = capiEvents;
        this.cipher = cipher;
        this.graph = graph;
        this.funnel = funnel;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public MetaConnectionDto get(UUID orgId) {
        return connections.findByOrgIdAndEventIdIsNull(orgId)
                .map(MetaConnectionDto::from)
                .orElseGet(MetaConnectionDto::notConnected);
    }

    @Transactional
    public MetaConnectionDto upsert(UUID orgId, UUID createdBy, PutMetaConnectionRequest req) {
        MetaPixelConnection c = connections.findByOrgIdAndEventIdIsNull(orgId)
                .orElseGet(() -> {
                    MetaPixelConnection fresh = new MetaPixelConnection();
                    fresh.setId(UUID.randomUUID());
                    fresh.setOrgId(orgId);
                    fresh.setEventId(null);
                    fresh.setCreatedBy(createdBy);
                    return fresh;
                });
        c.setPixelId(req.pixelId());
        c.setTestEventCode(req.testEventCode());
        c.setStatus("active");
        if (req.capiAccessToken() != null && !req.capiAccessToken().isBlank()) {
            c.setCapiAccessTokenEnc(cipher.encrypt(req.capiAccessToken()));
        } else if (c.getCapiAccessTokenEnc() == null) {
            // A first-time connect with no token is not usable.
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "A CAPI access token is required to connect a pixel");
        }
        c.setUpdatedAt(Instant.now());
        connections.save(c);
        return MetaConnectionDto.from(c);
    }

    @Transactional
    public void delete(UUID orgId) {
        connections.findByOrgIdAndEventIdIsNull(orgId).ifPresent(connections::delete);
    }

    /** Fires a test event with the stored test_event_code, relaying Meta's ack. */
    @Transactional(readOnly = true)
    public MetaTestEventResult test(UUID orgId) {
        MetaPixelConnection c = connections.findByOrgIdAndEventIdIsNull(orgId)
                .orElseThrow(() -> ApiException.notFound("Meta connection"));
        String token = cipher.decrypt(c.getCapiAccessTokenEnc());
        Map<String, Object> event = Map.of(
                "event_name", "PageView",
                "event_time", Instant.now().getEpochSecond(),
                "event_id", "test-" + UUID.randomUUID(),
                "action_source", "website",
                "user_data", Map.of());
        return graph.sendEvents(c.getPixelId(), token, c.getTestEventCode(), List.of(event));
    }

    @Transactional(readOnly = true)
    public MetaStatsDto stats(UUID orgId) {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        long sent = capiEvents.countSentSince(orgId, since);
        long failed = capiEvents.countFailingSince(orgId, since);
        long dead = capiEvents.countDead(orgId);
        List<String> errs = capiEvents.recentErrors(orgId, PageRequest.of(0, 1));
        return new MetaStatsDto(sent, failed, dead, errs.isEmpty() ? null : errs.get(0));
    }

    /**
     * Org-wide 3-stage "signal health" funnel for the Channels tab's Meta card
     * (spec §8). Generalizes the per-event sales funnel to all of the org's active
     * events over a rolling {@link #FUNNEL_WINDOW_DAYS}-day window, and maps the
     * three real stages onto Meta's event vocabulary. See {@link MetaFunnelDto} for
     * the mapping, the omitted {@code ViewContent} stage, and which numbers are real.
     *
     * <p>The imin-side counts are always real. The Meta-side "received" count is
     * populated only for Purchase (our CAPI outbox emits Purchase events only) —
     * the count of Purchase events actually delivered to Meta for orders in the
     * window; a gap against the order count is the silent signal loss the card
     * surfaces. PageView and InitiateCheckout have no server-side Meta record, so
     * their Meta-side count is {@code null} — never fabricated.
     */
    @Transactional(readOnly = true)
    public MetaFunnelDto funnel(UUID orgId) {
        Instant since = Instant.now().minus(FUNNEL_WINDOW_DAYS, ChronoUnit.DAYS);

        Map<String, Long> byStage = new HashMap<>();
        for (Object[] row : funnel.countDistinctAnonByStageForOrg(orgId, since)) {
            byStage.put((String) row[0], ((Number) row[1]).longValue());
        }
        long pageViews = byStage.getOrDefault(FunnelEvent.STAGE_PAGE_VIEW, 0L);
        long checkoutStarts = byStage.getOrDefault(FunnelEvent.STAGE_CHECKOUT_START, 0L);
        long payments = orders.countByOrgIdSince(orgId, since);
        long metaPurchasesReceived = capiEvents.countSentByCreatedAtSince(orgId, since);

        List<MetaFunnelDto.Stage> stages = List.of(
                // Meta-side per-stage counts are only sourceable for Purchase (CAPI is
                // Purchase-only); the upper two stages fire in the browser Pixel and are
                // not recorded server-side, so their Meta count is null (not zero).
                new MetaFunnelDto.Stage("PageView", FunnelEvent.STAGE_PAGE_VIEW, pageViews, null),
                new MetaFunnelDto.Stage("InitiateCheckout", FunnelEvent.STAGE_CHECKOUT_START, checkoutStarts, null),
                new MetaFunnelDto.Stage("Purchase", "PAYMENTS_COMPLETED", payments, metaPurchasesReceived));

        return new MetaFunnelDto(FUNNEL_WINDOW_DAYS, stages);
    }
}
