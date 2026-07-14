package com.imin.iminapi.marketing.graph;

import com.imin.iminapi.marketing.dto.MetaTestEventResult;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Posts server events to {@code POST /{version}/{pixelId}/events}. The Graph API
 * version is parameterized (default v25.0) so we never bake a forced migration
 * (spec §5). The access token is passed as the {@code access_token} form/query
 * field per the CAPI contract.
 */
@Component
public class MetaGraphClient {

    private static final Logger log = LoggerFactory.getLogger(MetaGraphClient.class);

    private final RestClient client;
    private final String version;

    public MetaGraphClient(@Qualifier("metaGraphRestClient") RestClient client,
                           @Value("${imin.meta.graph-api-version:v25.0}") String version) {
        this.client = client;
        this.version = version;
    }

    /**
     * Sends a batch of CAPI events. {@code data} is the list of Meta event maps.
     * {@code testEventCode} may be null for real traffic.
     *
     * @return Meta's parsed response.
     * @throws ApiException(META_UPSTREAM_ERROR) on non-2xx / transport failure.
     */
    @SuppressWarnings("unchecked")
    public MetaTestEventResult sendEvents(String pixelId, String accessToken,
                                          String testEventCode, List<Map<String, Object>> data) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("data", data);
        body.put("access_token", accessToken);
        if (testEventCode != null && !testEventCode.isBlank()) {
            body.put("test_event_code", testEventCode);
        }
        try {
            Map<String, Object> resp = client.post()
                    .uri("/{version}/{pixelId}/events", version, pixelId)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            Object received = resp == null ? null : resp.get("events_received");
            Object messages = resp == null ? null : resp.get("messages");
            Object fbtrace = resp == null ? null : resp.get("fbtrace_id");
            return new MetaTestEventResult(
                    true,
                    received instanceof Number n ? n.intValue() : null,
                    messages == null ? null : messages.toString(),
                    fbtrace == null ? null : fbtrace.toString());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            log.warn("Meta Graph {}/{} events rejected: {} {}", version, pixelId,
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.META_UPSTREAM_ERROR,
                    "Meta rejected the events: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Meta Graph call failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.META_UPSTREAM_ERROR,
                    "Meta Graph API unreachable: " + e.getMessage());
        }
    }
}
