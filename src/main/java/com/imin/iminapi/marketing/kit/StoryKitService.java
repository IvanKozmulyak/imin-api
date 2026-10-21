package com.imin.iminapi.marketing.kit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.event.EventSalesFigures;
import com.imin.iminapi.marketing.dto.StoryKitDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.analytics.NetOrderRevenue;
import com.imin.iminapi.service.event.EventSalesTotals;
import com.imin.iminapi.service.poster.PosterImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** An event's Instagram kit: images rendered and uploaded outside any transaction, then a short write. */
@Service
public class StoryKitService {

    private static final Logger log = LoggerFactory.getLogger(StoryKitService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String REASON_NO_POSTER = "NO_POSTER";
    public static final String REASON_POSTER_NOT_SUPPORTED = "POSTER_NOT_SUPPORTED";
    public static final String REASON_NOT_PUBLIC = "NOT_PUBLIC";
    public static final String REASON_ENDED = "ENDED";
    public static final String REASON_KIT_CHANGED = "KIT_CHANGED";

    // ponytail: per-JVM cap on decoded posters in memory; each replica allows this many, a busy one answers 503.
    static final int MAX_CONCURRENT_RENDERS = 2;
    final Semaphore renders = new Semaphore(MAX_CONCURRENT_RENDERS);

    /** One stored image; {@code storageKey} never leaves the server. */
    record StoredAsset(String key, int width, int height, String storageKey, String url, String postOn) {}

    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final StoryKitRepository kits;
    private final ShareLinkService links;
    private final ShareLinkRepository linkRows;
    private final KitImageStorage storage;
    private final StoryKitRenderer renderer;
    private final StoryKitFonts fonts;
    private final FunnelEventRepository funnel;
    private final OrderRepository orders;
    private final EventSalesTotals salesTotals;
    private final Clock clock;
    private final boolean enabled;
    private final int visitorsWindowDays;

    public StoryKitService(EventRepository events, OrganizationRepository orgs, StoryKitRepository kits,
                           ShareLinkService links, ShareLinkRepository linkRows, KitImageStorage storage,
                           StoryKitRenderer renderer, StoryKitFonts fonts, FunnelEventRepository funnel,
                           OrderRepository orders, EventSalesTotals salesTotals, Clock clock,
                           @Value("${imin.story-kit.enabled:true}") boolean enabled,
                           @Value("${imin.analytics.funnel-retention-days:90}") int visitorsWindowDays) {
        this.events = events;
        this.orgs = orgs;
        this.kits = kits;
        this.links = links;
        this.linkRows = linkRows;
        this.storage = storage;
        this.renderer = renderer;
        this.fonts = fonts;
        this.funnel = funnel;
        this.orders = orders;
        this.salesTotals = salesTotals;
        this.clock = clock;
        this.enabled = enabled;
        this.visitorsWindowDays = visitorsWindowDays;
    }

    static ApiException kitChanged() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                "The Instagram kit changed; reload and try again", Map.of("reason", REASON_KIT_CHANGED));
    }

    static ApiException blocked(String reason) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                "An Instagram kit cannot be made for this event", Map.of("reason", reason));
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                "Instagram kits are not available right now");
    }

    // ---- read ----

    public StoryKitDto.State state(AuthPrincipal p, UUID eventId) {
        Event event = loadOwned(p, eventId);
        return stateOf(event);
    }

    private StoryKitDto.State stateOf(Event event) {
        Organization org = orgs.findById(event.getOrgId()).orElse(null);
        EventSalesFigures figures = salesTotals.forEvent(event.getId());
        boolean soldOut = figures.capacity() != null && figures.sold() >= figures.capacity();
        StoryKit kit = kits.findByEventId(event.getId()).orElse(null);
        return new StoryKitDto.State(isAvailable(), blockedReason(event),
                StoryKitText.defaultLocale(org == null ? null : org.getCountry()), soldOut, visitorsWindowDays,
                kit == null ? null : toDto(kit, event));
    }

    boolean isAvailable() {
        return enabled && fonts.available() && storage.storageAvailable();
    }

    /** Why no kit can be made now, or null. Order: poster, then page, then time. */
    String blockedReason(Event e) {
        if (e.getPosterUrl() == null || e.getPosterUrl().isBlank()) return REASON_NO_POSTER;
        if (!storage.isOwnPoster(e.getPosterUrl())) return REASON_POSTER_NOT_SUPPORTED;
        boolean isPublic = e.getVisibility() == EventVisibility.PUBLIC && e.getPublishedAt() != null
                && e.getStatus() != EventStatus.DRAFT && e.getStartsAt() != null;
        if (!isPublic) return REASON_NOT_PUBLIC;
        if (e.getStatus() == EventStatus.CANCELLED || e.getStatus() == EventStatus.PAST
                || !e.getStartsAt().isAfter(clock.instant())) {
            return REASON_ENDED;
        }
        return null;
    }

    // ---- make ----

    public StoryKitDto.State make(AuthPrincipal p, UUID eventId, String locale) {
        Event event = loadOwned(p, eventId);
        if (!StoryKitText.supported(locale)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Unsupported language",
                    Map.of("locale", "must be one of en, fr, es, uk"));
        }
        if (!isAvailable()) throw unavailable();
        String reason = blockedReason(event);
        if (reason != null) throw blocked(reason);
        if (!renders.tryAcquire()) throw unavailable();
        StoryKit existing;
        try {
            existing = render(p, event, locale);
        } finally {
            renders.release();
        }
        if (existing != null) cleanup(storageKeys(existing));
        return stateOf(event);
    }

    /** True when the images derive from an AI render: flagged on the event, or one of our AI render files. */
    static boolean aiDerived(Event e) {
        return Boolean.TRUE.equals(e.getPosterAiGenerated()) || PosterImageStorage.isAiGeneratedPosterUrl(e.getPosterUrl());
    }

    /** Renders, uploads and saves the kit; returns the kit it replaced, whose files the caller deletes. */
    private StoryKit render(AuthPrincipal p, Event event, String locale) {
        UUID eventId = event.getId();
        StoryKit existing = kits.findByEventId(eventId).orElse(null);
        BufferedImage poster = loadPoster(event);
        ZoneId zone = StoryKitText.zone(event.getTimezone());
        boolean aiDerived = aiDerived(event);
        StoryKitText.Plan plan = StoryKitText.frames(event.getStartsAt(), zone, clock.instant());

        List<String> uploadedKeys = new ArrayList<>();
        List<StoredAsset> assets = new ArrayList<>();
        try {
            assets.add(upload(event, uploadedKeys, "story", StoryKitRenderer.Kind.STORY, poster, aiDerived, null,
                    overlay(null, event, zone, locale, false)));
            assets.add(upload(event, uploadedKeys, "post", StoryKitRenderer.Kind.POST, poster, aiDerived, null,
                    new StoryKitRenderer.Overlay(null, null, null)));
            for (StoryKitText.Frame f : plan.made()) {
                String headline = StoryKitText.headline(f.key(), event.getStartsAt(), zone, locale);
                assets.add(upload(event, uploadedKeys, f.key(), StoryKitRenderer.Kind.COUNTDOWN, poster, aiDerived,
                        f.postOn().toString(),
                        overlay(headline, event, zone, locale, StoryKitText.KEY_D0.equals(f.key()))));
            }
            ShareLink link = links.getOrCreateForStoryKit(event.getOrgId(), eventId, p.userId());
            save(existing, event, link, locale, aiDerived, assets, plan, p.userId());
        } catch (RuntimeException failure) {
            cleanup(uploadedKeys);
            throw failure;
        }
        return existing;
    }

    private BufferedImage loadPoster(Event event) {
        try {
            return storage.loadPoster(event.getPosterUrl());
        } catch (KitImageStorage.PosterUnreadableException unreadable) {
            throw blocked(REASON_POSTER_NOT_SUPPORTED);
        } catch (RuntimeException storageDown) {
            log.warn("Story kit: could not read the poster of {}: {}", event.getId(), storageDown.toString());
            throw unavailable();
        }
    }

    private StoredAsset upload(Event event, List<String> uploadedKeys, String key, StoryKitRenderer.Kind kind,
                               BufferedImage poster, boolean aiDerived, String postOn,
                               StoryKitRenderer.Overlay overlay) {
        byte[] jpeg = renderer.render(kind, poster, overlay);
        KitImageStorage.Uploaded up;
        try {
            up = storage.put(event.getId(), key, jpeg, aiDerived);
        } catch (RuntimeException storageDown) {
            log.warn("Story kit: storage write failed for {}: {}", event.getId(), storageDown.toString());
            throw unavailable();
        }
        uploadedKeys.add(up.storageKey());
        return new StoredAsset(key, StoryKitRenderer.WIDTH, StoryKitRenderer.heightOf(kind), up.storageKey(),
                up.url(), postOn);
    }

    private static StoryKitRenderer.Overlay overlay(String headline, Event e, ZoneId zone, String locale,
                                                    boolean timeOnly) {
        Locale l = Locale.forLanguageTag(locale);
        String when = timeOnly ? StoryKitText.timeLine(e.getStartsAt(), zone)
                : StoryKitText.whenLine(e.getStartsAt(), zone, locale).toUpperCase(l);
        return new StoryKitRenderer.Overlay(headline, when,
                StoryKitText.venueLine(e.getVenueName(), e.getVenueCity()));
    }

    private void save(StoryKit existing, Event event, ShareLink link, String locale, boolean aiDerived,
                      List<StoredAsset> assets, StoryKitText.Plan plan, UUID userId) {
        String assetsJson = write(assets);
        String skippedJson = write(plan.skipped().stream()
                .map(f -> new StoryKitDto.Skipped(f.key(), f.postOn().toString())).toList());
        try {
            if (existing == null) {
                StoryKit kit = new StoryKit();
                kit.setId(UUID.randomUUID());
                kit.setOrgId(event.getOrgId());
                kit.setEventId(event.getId());
                kit.setShareLinkCode(link.getCode());
                kit.setLocale(locale);
                kit.setAssets(assetsJson);
                kit.setSkipped(skippedJson);
                kit.setAiDerived(aiDerived);
                kit.setSourcePosterUrl(event.getPosterUrl());
                kit.setSourceStartsAt(event.getStartsAt());
                kit.setSourceTimezone(event.getTimezone());
                kit.setSourceVenueName(event.getVenueName());
                kit.setSourceVenueCity(event.getVenueCity());
                kit.setRevision(0);
                kit.setCreatedBy(userId);
                kits.saveAndFlush(kit);
            } else {
                int rows = kits.replace(existing.getId(), existing.getRevision(), locale, assetsJson, skippedJson,
                        aiDerived, event.getPosterUrl(), event.getStartsAt(), event.getTimezone(),
                        event.getVenueName(), event.getVenueCity(), userId, clock.instant().truncatedTo(ChronoUnit.MICROS));
                if (rows == 0) throw kitChanged();
            }
        } catch (DataIntegrityViolationException lostRace) {
            throw kitChanged();
        }
    }

    /** Deleting is a courtesy; a failure here must never replace the exception or result it runs beside. */
    private void cleanup(List<String> keys) {
        try {
            storage.deleteQuietly(keys);
        } catch (RuntimeException e) {
            log.warn("Story kit cleanup failed: {}", e.getMessage());
        }
    }

    // ---- download ----

    public byte[] assetBytes(AuthPrincipal p, UUID eventId, String key) {
        Event event = loadOwned(p, eventId);
        StoryKit kit = kits.findByEventId(event.getId()).orElseThrow(() -> ApiException.notFound("Story kit"));
        StoredAsset asset = readAssets(kit).stream().filter(a -> a.key().equals(key)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Image"));
        byte[] bytes = storage.readKit(asset.storageKey());
        if (bytes == null) throw ApiException.notFound("Image");
        return bytes;
    }

    /** The kit's images plus caption.txt (when there is a caption) and link.txt. */
    public Zip zip(AuthPrincipal p, UUID eventId) {
        Event event = loadOwned(p, eventId);
        StoryKit kit = kits.findByEventId(event.getId()).orElseThrow(() -> ApiException.notFound("Story kit"));
        ShareLink link = linkRows.findById(kit.getShareLinkCode()).orElseThrow(() -> ApiException.notFound("Link"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (StoredAsset a : readAssets(kit)) {
                byte[] bytes = storage.readKit(a.storageKey());
                if (bytes == null) continue;
                put(zip, fileName(a.key()), bytes);
            }
            if (kit.getCaption() != null) put(zip, "caption.txt", kit.getCaption().getBytes(StandardCharsets.UTF_8));
            put(zip, "link.txt", (links.urlFor(link.getCode()) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build the kit zip", e);
        }
        return new Zip(out.toByteArray(), "instagram-kit-" + event.getSlug() + ".zip");
    }

    public record Zip(byte[] bytes, String fileName) {}

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String fileName(String key) {
        return switch (key) {
            case StoryKitText.KEY_STORY -> "story.jpg";
            case StoryKitText.KEY_POST -> "feed-post.jpg";
            case StoryKitText.KEY_D7 -> "countdown-1-week.jpg";
            case StoryKitText.KEY_D2 -> "countdown-2-days.jpg";
            default -> "countdown-day-of.jpg";
        };
    }

    // ---- mapping ----

    private StoryKitDto.Kit toDto(StoryKit kit, Event event) {
        ShareLink link = linkRows.findById(kit.getShareLinkCode()).orElse(null);
        List<StoryKitDto.Asset> assets = readAssets(kit).stream()
                .map(a -> new StoryKitDto.Asset(a.key(), a.width(), a.height(), a.url(), a.postOn())).toList();
        List<StoryKitDto.Skipped> skipped = read(kit.getSkipped(), new TypeReference<List<StoryKitDto.Skipped>>() {});
        StoryKitDto.Caption caption = kit.getCaption() == null ? null
                : new StoryKitDto.Caption(kit.getCaption(), Boolean.TRUE.equals(kit.getCaptionAiGenerated()),
                        kit.getCaptionCreatedAt());
        return new StoryKitDto.Kit(kit.getId(), kit.getRevision(), kit.getCreatedAt(), kit.getLocale(),
                link == null ? null : new StoryKitDto.Link(link.getCode(), links.urlFor(link.getCode()), link.getCreatedAt()),
                assets, skipped, caption, stale(kit, event), stats(kit, event, link));
    }

    static List<String> stale(StoryKit kit, Event e) {
        List<String> out = new ArrayList<>();
        if (!Objects.equals(kit.getSourceStartsAt(), e.getStartsAt())
                || !Objects.equals(kit.getSourceTimezone(), e.getTimezone())) {
            out.add("date");
        }
        if (!same(kit.getSourceVenueName(), e.getVenueName()) || !same(kit.getSourceVenueCity(), e.getVenueCity())) {
            out.add("venue");
        }
        if (!Objects.equals(kit.getSourcePosterUrl(), e.getPosterUrl())) out.add("poster");
        return out;
    }

    private static boolean same(String a, String b) {
        return (a == null ? "" : a.trim()).equals(b == null ? "" : b.trim());
    }

    private StoryKitDto.Stats stats(StoryKit kit, Event event, ShareLink link) {
        if (link == null) return new StoryKitDto.Stats(0, 0, 0, event.getCurrency());
        String campaign = link.getUtmCampaign();
        long visitors = funnel.countDistinctAnonByEventAndCampaign(event.getId(), campaign);
        List<Object[]> rows = orders.revenueRowsByEventAndUtmCampaign(event.getOrgId(), event.getId(), campaign);
        long kept = NetOrderRevenue.keptOrdersByKey(rows).getOrDefault(campaign, 0L);
        long net = NetOrderRevenue.sumByKey(rows).getOrDefault(campaign, 0L);
        return new StoryKitDto.Stats(visitors, kept, net, event.getCurrency());
    }

    private List<StoredAsset> readAssets(StoryKit kit) {
        return read(kit.getAssets(), new TypeReference<List<StoredAsset>>() {});
    }

    private static List<String> storageKeys(StoryKit kit) {
        try {
            List<StoredAsset> assets = JSON.readValue(kit.getAssets(), new TypeReference<List<StoredAsset>>() {});
            return assets.stream().map(StoredAsset::storageKey).toList();
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static <T> List<T> read(String json, TypeReference<List<T>> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored kit JSON is unreadable", e);
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- shared ----

    private Event loadOwned(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }
}
