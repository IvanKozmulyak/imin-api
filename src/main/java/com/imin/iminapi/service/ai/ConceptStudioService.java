package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.dto.ai.*;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.ImageProvider;
import com.imin.iminapi.model.PosterGeneration;
import com.imin.iminapi.model.PosterVariantEntity;
import org.springframework.beans.factory.annotation.Value;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PosterGenerationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.AiEventDescriptionService;
import com.imin.iminapi.service.PricingService;
import com.imin.iminapi.service.poster.BrandSnapshot;
import com.imin.iminapi.service.poster.DjPhotoSnapshot;
import com.imin.iminapi.service.poster.PosterImageStorage;
import com.imin.iminapi.service.poster.PosterOrchestrator;
import com.imin.iminapi.service.poster.PosterOrchestrator.OrchestrationResult;
import com.imin.iminapi.service.poster.VibeLibrary;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ConceptStudioService {

    private static final Logger log = LoggerFactory.getLogger(ConceptStudioService.class);
    private static final List<String> DEFAULT_PLATFORMS = List.of("instagram");
    private static final String DEFAULT_TONE = "energetic";
    // Wire values of ConceptRegenerateRequest.lock (API_CONTRACT.md §"POST /ai/events/concept/regenerate").
    private static final String LOCK_NAME = "name";
    private static final String LOCK_DESCRIPTION = "description";
    private static final String LOCK_POSTER = "poster";

    private final AiEventDescriptionService descService;
    private final PosterOrchestrator orchestrator;
    private final PricingService pricing;
    private final ConceptOverviewLlm overviewLlm;
    private final GeneratedEventRepository repo;
    private final OrganizationRepository orgs;
    private final VibeLibrary vibeLibrary;
    private final EventRepository eventRepo;
    private final PosterImageStorage posterStorage;
    private final PosterGenerationRepository generationRepo;

    // When true, the resolved vibe's model_route (vibes.yaml) selects the image provider.
    // Default false keeps the reference-first path on Recraft for every vibe.
    @Value("${poster.provider-routing.enabled:false}")
    private boolean providerRoutingEnabled;

    public ConceptStudioService(AiEventDescriptionService descService,
                                PosterOrchestrator orchestrator,
                                PricingService pricing,
                                ConceptOverviewLlm overviewLlm,
                                GeneratedEventRepository repo,
                                OrganizationRepository orgs,
                                VibeLibrary vibeLibrary,
                                EventRepository eventRepo,
                                PosterImageStorage posterStorage,
                                PosterGenerationRepository generationRepo) {
        this.descService = descService;
        this.orchestrator = orchestrator;
        this.pricing = pricing;
        this.overviewLlm = overviewLlm;
        this.repo = repo;
        this.orgs = orgs;
        this.vibeLibrary = vibeLibrary;
        this.eventRepo = eventRepo;
        this.posterStorage = posterStorage;
        this.generationRepo = generationRepo;
    }

    /**
     * Deliberately NOT {@code @Transactional} — see {@link #run}. Each repository write below opens
     * its own short transaction.
     */
    public ConceptResponse create(AuthPrincipal p, ConceptRequest req) {
        return run(p, req, resolveDjPhotoFromEvent(p, req.eventId()));
    }

    /**
     * Deliberately NOT {@code @Transactional} — see {@link #run}.
     *
     * @param lock the fields the organizer pinned ({@code name} | {@code description} |
     *             {@code poster}). A locked poster skips the render entirely and re-serves the prior
     *             generation's images; a locked name/description carries the stored value through.
     */
    public ConceptResponse regenerate(AuthPrincipal p, UUID conceptId, List<String> lock) {
        GeneratedEvent prior = repo.findByIdAndOrgId(conceptId, p.orgId())
                .orElseThrow(() -> ApiException.notFound("Concept"));
        Set<String> locks = normalizeLocks(lock);
        // Snapshot read-back: use the DJ photo URL from the original generation row, not the live event.
        // This means a photo swap between create and regenerate doesn't silently change the mode.
        DjPhotoSnapshot djPhoto = generationRepo.findTopByGeneratedEventIdOrderByCreatedAtDesc(conceptId)
                .map(PosterGeneration::getDjPhotoUrl)
                .map(this::resolveDjPhotoFromUrl)
                .orElse(null);
        // Rebuild from the snapshot stored at create time (V108), not from nulls: without the event
        // text the regenerated posters carried neither title nor venue, and without the pinned
        // vibeId a genre default silently replaced the aesthetic the organizer chose.
        ConceptRequest req = new ConceptRequest(
                prior.getVibe() == null ? "rerun" : prior.getVibe(),
                prior.getGenre(), prior.getCity(),
                prior.getRequestCapacity(), prior.getRequestVibeId(),
                prior.getRequestTitle(), prior.getRequestEventDate(), prior.getRequestVenue(),
                splitLineup(prior.getRequestLineup()), prior.getRequestAddress(),
                prior.getRequestRsvpUrl(),
                prior.getRequestLogoOnPosters(),
                prior.getRequestEventId());
        List<PosterVariantEntity> lockedPosters =
                locks.contains(LOCK_POSTER) ? priorVariants(conceptId) : List.of();
        return run(p, req, djPhoto, prior, locks, lockedPosters);
    }

    /** Inverse of {@link #joinLineup}: null/blank → null, so an absent lineup stays absent. */
    private static List<String> splitLineup(String joined) {
        if (joined == null || joined.isBlank()) return null;
        return java.util.Arrays.stream(joined.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .toList();
    }

    /** Comma-joined, the same convention as {@code platforms} and {@code palette_hexes}. */
    private static String joinLineup(List<String> lineup) {
        if (lineup == null || lineup.isEmpty()) return null;
        return String.join(",", lineup.stream().filter(Objects::nonNull).map(String::trim)
                .filter(v -> !v.isEmpty()).toList());
    }

    /** Wire lock values, trimmed and lower-cased; unknown entries are simply never matched. */
    private static Set<String> normalizeLocks(List<String> lock) {
        if (lock == null || lock.isEmpty()) return Set.of();
        return lock.stream()
                .filter(Objects::nonNull)
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Variants of the newest generation that actually produced an image. Empty when there is
     * nothing to reuse (no prior generation, or every variant failed) — the caller then renders
     * afresh rather than answering with zero posters.
     */
    private List<PosterVariantEntity> priorVariants(UUID conceptId) {
        for (PosterGeneration g : generationRepo.findWithVariantsByGeneratedEventId(conceptId)) {
            List<PosterVariantEntity> usable = g.getVariants().stream()
                    .filter(v -> posterUrl(v) != null)
                    .toList();
            if (!usable.isEmpty()) return usable;
        }
        return List.of();
    }

    /**
     * Resolve the DJ photo for a generation bound to an owned event. Null when: no eventId, the
     * event has no photo, or the R2 download fails (the photo must never break generation — the
     * response then reports djPhotoUsed=false). Cross-org access is NOT_FOUND, never FORBIDDEN.
     */
    DjPhotoSnapshot resolveDjPhotoFromEvent(AuthPrincipal p, UUID eventId) {
        if (eventId == null) return null;
        Event e = eventRepo.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return resolveDjPhotoFromUrl(e.getDjPhotoUrl());
    }

    private DjPhotoSnapshot resolveDjPhotoFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            byte[] bytes = posterStorage.download(url);
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("DJ photo download was empty: " + url);
            }
            String mime = url.toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
            return new DjPhotoSnapshot(url, bytes, mime);
        } catch (Exception ex) {
            log.warn("DJ photo unavailable; generating without character reference: {}", ex.getMessage());
            Sentry.withScope(scope -> {
                scope.setLevel(SentryLevel.WARNING);
                scope.setTag("subsystem", "dj-photo");
                Sentry.captureException(ex);
            });
            return null;
        }
    }

    /**
     * Runs the pipeline OUTSIDE any transaction, on purpose. The Ideogram renders, the vision gates
     * and the R2 puts take minutes; holding a pooled JDBC connection across them starved the pool,
     * and — worse — the {@code status = FAILED} write in the catch block below was rolled back by
     * the very {@link ApiException} it precedes, so a failed generation left no forensic trace.
     * The three writes here are independent (staging row → poster rows → overview), not one atomic
     * unit; each {@code repo.save} opens its own short transaction.
     */
    private ConceptResponse run(AuthPrincipal p, ConceptRequest req, DjPhotoSnapshot djPhoto) {
        return run(p, req, djPhoto, null, Set.of(), List.of());
    }

    private ConceptResponse run(AuthPrincipal p, ConceptRequest req, DjPhotoSnapshot djPhoto,
                                GeneratedEvent prior, Set<String> locks,
                                List<PosterVariantEntity> lockedPosters) {
        if (req.vibeId() != null && !req.vibeId().isBlank() && !vibeLibrary.hasVibe(req.vibeId())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    com.imin.iminapi.security.ErrorCode.FIELD_INVALID,
                    "Unknown vibeId: " + req.vibeId());
        }
        GeneratedEvent staging = newStagingRow(p, req);
        repo.save(staging);

        // Creative seed for this run, derived from the generation UUID: reproducible per generation,
        // varies across runs (a regenerate creates a fresh row → fresh seed → fresh creative directions).
        long creativeSeed = seedFrom(staging.getId());

        BrandSnapshot brand = resolveBrand(p, req.logoOnPosters());
        EventCreatorRequest legacy = toLegacyRequest(req, brand);
        PosterConcept poster;
        OrchestrationResult render;
        ConceptOverview overview;
        try {
            AiEventDescriptionService.GeneratedConcept generated = descService.generateConcept(legacy, creativeSeed, djPhoto != null);
            // Pin the resolved vibe (legacy.subStyleTag is the selected vibe, or one auto-suggested
            // from genre) as the concept's style tag, so the orchestrator resolves that vibe's curated
            // reference flyers (forTag) regardless of what the LLM echoed.
            poster = new PosterConcept(legacy.subStyleTag(),
                    generated.concept().colorPaletteDescription(), generated.concept().variants());
            // A locked poster must not spend three Ideogram renders to hand back different art.
            render = lockedPosters.isEmpty()
                    ? orchestrator.run(staging.getId(), legacy, poster, creativeSeed, generated.directions(), brand, djPhoto)
                    : null;
            overview = overviewLlm.generate(req, poster);
        } catch (Exception e) {
            staging.setStatus(GeneratedEventStatus.FAILED);
            try { repo.save(staging); } catch (Exception ignored) {}
            log.error("Poster pipeline failed", e);
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    com.imin.iminapi.security.ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Poster generation service unavailable");
        }

        PricingRecommendation prices = pricing.recommend(
                req.genre() == null ? "techno" : req.genre(),
                req.city() == null ? "" : req.city(),
                LocalDate.now().plusMonths(2));

        List<SuggestedTierDto> tiers = buildTiers(prices, overview.suggestedCapacity());
        List<PosterDto> posterDtos = render != null
                ? mapPosters(render.posters(), overview.paletteHexes())
                : mapVariants(lockedPosters, overview.paletteHexes());

        String name = lockedValue(prior, locks, LOCK_NAME, GeneratedEvent::getName, overview.name());
        String description = lockedValue(prior, locks, LOCK_DESCRIPTION,
                GeneratedEvent::getDescription, overview.description());

        // Persist V1 fields onto the staging row
        staging.setName(name);
        staging.setDescription(description);
        staging.setPaletteHexes(String.join(",", overview.paletteHexes() == null ? List.of() : overview.paletteHexes()));
        staging.setConfidencePct(overview.confidencePct());
        staging.setStatus(GeneratedEventStatus.COMPLETE);
        repo.save(staging);

        return new ConceptResponse(
                staging.getId(),
                name,
                description,
                posterDtos,
                overview.paletteHexes(),
                tiers,
                overview.suggestedCapacity(),
                overview.confidencePct(),
                djPhoto != null);
    }

    /** Deterministic creative seed from the generation UUID (reproducible per run, varies across runs). */
    private static long seedFrom(UUID id) {
        return id == null ? 1L : Math.abs(id.getMostSignificantBits() ^ id.getLeastSignificantBits());
    }

    private GeneratedEvent newStagingRow(AuthPrincipal p, ConceptRequest req) {
        GeneratedEvent g = new GeneratedEvent();
        g.setOrgId(p.orgId());
        g.setVibe(req.vibe());
        g.setTone(DEFAULT_TONE);
        g.setGenre(req.genre());
        g.setCity(req.city());
        g.setEventDate(LocalDate.now().plusMonths(2));
        g.setPlatforms(String.join(",", DEFAULT_PLATFORMS));
        g.setStatus(GeneratedEventStatus.DRAFT);
        // Snapshot the request (V108) so a later regenerate reproduces the same event text and the
        // same pinned vibe. eventDate above stays the pricing horizon; the asked-for date is its own
        // column so nothing that already reads event_date changes meaning.
        g.setRequestTitle(req.title());
        g.setRequestVenue(req.venue());
        g.setRequestLineup(joinLineup(req.lineup()));
        g.setRequestAddress(req.address());
        g.setRequestRsvpUrl(req.rsvpUrl());
        g.setRequestVibeId(req.vibeId());
        g.setRequestEventId(req.eventId());
        g.setRequestEventDate(req.eventDate());
        g.setRequestCapacity(req.capacity());
        g.setRequestLogoOnPosters(req.logoOnPosters());
        return g;
    }

    /** The selected vibe, or one auto-suggested from genre (so a vibe always drives generation). */
    private Vibe resolveVibe(ConceptRequest req) {
        return vibeLibrary.byId(req.vibeId())
                .orElseGet(() -> vibeLibrary.suggestForGenre(req.genre()));
    }

    /** Map the vibe's declared model_route to a provider when routing is enabled; else Recraft. */
    ImageProvider providerFor(Vibe vibe) {
        if (!providerRoutingEnabled || vibe == null || vibe.modelRoute() == null) {
            return ImageProvider.RECRAFT;
        }
        return switch (vibe.modelRoute().toLowerCase()) {
            case "recraft" -> ImageProvider.RECRAFT;
            case "gpt-image", "openai" -> ImageProvider.OPENAI;
            case "replicate", "ideogram" -> ImageProvider.REPLICATE;
            default -> ImageProvider.RECRAFT; // sdxl or unknown: keep the reference-first default.
        };
    }

    private EventCreatorRequest toLegacyRequest(ConceptRequest req, BrandSnapshot brand) {
        Vibe vibe = resolveVibe(req);
        String djName = (req.lineup() == null || req.lineup().isEmpty())
                ? null : String.join(", ", req.lineup());
        String accentColor = brand == null ? null : brand.packedAccentColor();
        return new EventCreatorRequest(
                req.vibe(),
                DEFAULT_TONE,
                req.genre() == null ? "Techno" : req.genre(),
                req.city(),
                req.eventDate() != null ? req.eventDate() : LocalDate.now().plusMonths(2),
                DEFAULT_PLATFORMS,
                /* djName  */ djName,
                /* location*/ req.venue(),
                /* title   */ req.title(),
                /* accentColor */ accentColor,
                /* address */ req.address(),
                /* rsvpUrl */ req.rsvpUrl(),
                /* subStyleTag: the selected vibe, or one auto-suggested from genre (always set) */
                vibe == null ? null : vibe.id(),
                providerFor(vibe));
    }

    /**
     * Resolve the org brand for this generation, failure-isolated: a malformed brand_accent_colors
     * JSON, a missing org, or any DB hiccup logs and returns a brandless snapshot. Brand integration
     * must never break the existing generation path.
     *
     * @param requestLogoOnPosters the per-call override (may be null)
     */
    private BrandSnapshot resolveBrand(AuthPrincipal p, Boolean requestLogoOnPosters) {
        try {
            Organization o = orgs.findById(p.orgId()).orElse(null);
            if (o == null) return null;
            List<String> colors = o.getBrandAccentColors() == null ? List.of() : o.getBrandAccentColors();
            boolean logoOn = requestLogoOnPosters != null ? requestLogoOnPosters : o.isBrandLogoOnPosters();
            boolean brandless = colors.isEmpty() && (o.getBrandLogoUrl() == null || o.getBrandLogoUrl().isBlank());
            if (brandless) return null; // empty brand book → fully backward-compatible (accentColor stays null)
            return new BrandSnapshot(colors, o.getBrandLogoUrl(), logoOn);
        } catch (Exception e) {
            log.warn("Brand lookup failed; generating brandless: {}", e.getMessage());
            return null;
        }
    }

    /** The stored value when the organizer locked this field and one exists; otherwise the fresh one. */
    private static String lockedValue(GeneratedEvent prior, Set<String> locks, String lockKey,
                                      java.util.function.Function<GeneratedEvent, String> read,
                                      String fresh) {
        if (prior == null || !locks.contains(lockKey)) return fresh;
        String kept = read.apply(prior);
        return kept == null || kept.isBlank() ? fresh : kept;
    }

    /** Re-serve a prior generation's images (poster lock) in the same shape as a fresh render. */
    private static List<PosterDto> mapVariants(List<PosterVariantEntity> variants, List<String> palette) {
        return variants.stream()
                .map(v -> new PosterDto(posterUrl(v), v.getVariantStyle(), gradientFor(palette)))
                .toList();
    }

    /** The composited image when there is one, else the raw render, else null (nothing shippable). */
    private static String posterUrl(PosterVariantEntity v) {
        if (v.getFinalUrl() != null && !v.getFinalUrl().isBlank()) return v.getFinalUrl();
        return v.getRawUrl() == null || v.getRawUrl().isBlank() ? null : v.getRawUrl();
    }

    private static List<PosterDto> mapPosters(List<GeneratedPoster> posters, List<String> palette) {
        return posters.stream().map(p -> new PosterDto(
                p.finalUrl() != null ? p.finalUrl() : p.rawUrl(),
                p.variantStyle(),
                gradientFor(palette))).toList();
    }

    private static String gradientFor(List<String> palette) {
        if (palette == null || palette.size() < 2) return "linear-gradient(135deg,#1a1a18,#2d5cff)";
        return "linear-gradient(135deg," + palette.get(0) + "," + palette.get(1) + ")";
    }

    private static List<SuggestedTierDto> buildTiers(PricingRecommendation prices, Integer capacity) {
        BigDecimal min = prices.suggestedMinPrice() == null ? new BigDecimal("12") : prices.suggestedMinPrice();
        BigDecimal max = prices.suggestedMaxPrice() == null ? new BigDecimal("24") : prices.suggestedMaxPrice();
        BigDecimal mid = min.add(max).divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
        int cap = capacity == null ? 250 : capacity;
        return List.of(
                new SuggestedTierDto("Early Bird", min.movePointRight(2).intValueExact(), Math.max(1, cap / 5)),
                new SuggestedTierDto("Standard",   mid.movePointRight(2).intValueExact(), Math.max(1, cap * 3 / 5)),
                new SuggestedTierDto("Door",       max.movePointRight(2).intValueExact(), Math.max(1, cap / 5))
        );
    }
}
