package com.imin.iminapi.marketing.service;

import com.imin.iminapi.marketing.crypto.CapiTokenCipher;
import com.imin.iminapi.marketing.dto.MetaConnectionDto;
import com.imin.iminapi.marketing.dto.MetaStatsDto;
import com.imin.iminapi.marketing.dto.MetaTestEventResult;
import com.imin.iminapi.marketing.dto.PutMetaConnectionRequest;
import com.imin.iminapi.marketing.graph.MetaGraphClient;
import com.imin.iminapi.marketing.model.MetaPixelConnection;
import com.imin.iminapi.marketing.repository.MetaCapiEventRepository;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MetaConnectionService {

    private final MetaPixelConnectionRepository connections;
    private final MetaCapiEventRepository capiEvents;
    private final CapiTokenCipher cipher;
    private final MetaGraphClient graph;

    public MetaConnectionService(MetaPixelConnectionRepository connections,
                                 MetaCapiEventRepository capiEvents,
                                 CapiTokenCipher cipher,
                                 MetaGraphClient graph) {
        this.connections = connections;
        this.capiEvents = capiEvents;
        this.cipher = cipher;
        this.graph = graph;
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
}
