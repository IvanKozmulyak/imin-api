package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client for the imin-public Satori text-compositor route (POST {base-url}/api/poster).
 *
 * <p>Closes the hybrid loop: given the generated background art bytes + the selected vibe + real
 * event text, it returns the final poster with a real-font text layer composited on top (replacing
 * the Java2D QR+address overlay). Only the five CURATED vibes have layout templates in imin-public,
 * and only requests with real event text are worth compositing — {@link #supports} gates both.
 * Disabled by default ({@code poster.compositor.enabled=false}); the orchestrator falls back to the
 * Java2D overlay when this is off, unsupported, or the call fails.
 */
@Component
public class PosterTextCompositorClient {

    private static final Logger log = LoggerFactory.getLogger(PosterTextCompositorClient.class);

    /** Must match the LayoutDocument templates in imin-public (lib/poster-compositor/templates.ts). */
    static final Set<String> SUPPORTED_VIBES = Set.of(
            "brutalist_techno", "berlin_minimal", "acid_rave_y2k", "psytrance_goa", "industrial_hard_groove");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final RestClient posterCompositorRestClient;
    private final boolean enabled;
    private final int width;

    public PosterTextCompositorClient(
            RestClient posterCompositorRestClient,
            @Value("${poster.compositor.enabled:false}") boolean enabled,
            @Value("${poster.compositor.width:1080}") int width) {
        this.posterCompositorRestClient = posterCompositorRestClient;
        this.enabled = enabled;
        this.width = width;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True only when enabled and the vibe has a layout template in imin-public. */
    public boolean supports(String vibeId) {
        return enabled && vibeId != null && SUPPORTED_VIBES.contains(vibeId);
    }

    /**
     * Composite the text layer over the background art via the imin-public route.
     * Returns the final poster PNG bytes. Throws on transport/HTTP error (caller falls back).
     */
    public byte[] composite(byte[] backgroundPng, String vibeId, EventText event) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("title", event.title());
        ev.put("date", event.date());
        ev.put("venue", event.venue());
        ev.put("lineup", event.lineup() == null ? List.of() : event.lineup());
        ev.put("price", event.price());
        ev.put("address", event.address());
        ev.put("qrData", event.qrData());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vibeId", vibeId);
        body.put("backgroundPngBase64", Base64.getEncoder().encodeToString(backgroundPng));
        body.put("event", ev);
        body.put("width", width);

        byte[] png = posterCompositorRestClient.post()
                .uri("/api/poster")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(byte[].class);
        if (png == null || png.length == 0) {
            throw new IllegalStateException("Poster compositor returned an empty body");
        }
        log.info("Text compositor produced {} bytes for vibe {}", png.length, vibeId);
        return png;
    }

    /** Event text for the overlay, derived from the poster request. */
    public record EventText(
            String title, String date, String venue, List<String> lineup,
            String price, String address, String qrData) {

        public static EventText from(EventCreatorRequest r) {
            List<String> lineup = (r.djName() == null || r.djName().isBlank())
                    ? List.of()
                    : Arrays.stream(r.djName().split("[,·/&]"))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
            String date = r.date() == null ? null : r.date().format(DATE_FMT);
            // EventCreatorRequest has no price field today → price slot is skipped.
            return new EventText(r.title(), date, r.location(), lineup, null, r.address(), r.rsvpUrl());
        }
    }
}
