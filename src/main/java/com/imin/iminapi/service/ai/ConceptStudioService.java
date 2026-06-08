package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.dto.ai.*;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.ImageProvider;
import org.springframework.beans.factory.annotation.Value;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.AiEventDescriptionService;
import com.imin.iminapi.service.PricingService;
import com.imin.iminapi.service.poster.PosterOrchestrator;
import com.imin.iminapi.service.poster.PosterOrchestrator.OrchestrationResult;
import com.imin.iminapi.service.poster.VibeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ConceptStudioService {

    private static final Logger log = LoggerFactory.getLogger(ConceptStudioService.class);
    private static final List<String> DEFAULT_PLATFORMS = List.of("instagram");
    private static final String DEFAULT_TONE = "energetic";

    private final AiEventDescriptionService descService;
    private final PosterOrchestrator orchestrator;
    private final PricingService pricing;
    private final ConceptOverviewLlm overviewLlm;
    private final GeneratedEventRepository repo;
    private final VibeLibrary vibeLibrary;

    // When true, the resolved vibe's model_route (vibes.yaml) selects the image provider.
    // Default false keeps the reference-first path on Recraft for every vibe.
    @Value("${poster.provider-routing.enabled:false}")
    private boolean providerRoutingEnabled;

    public ConceptStudioService(AiEventDescriptionService descService,
                                PosterOrchestrator orchestrator,
                                PricingService pricing,
                                ConceptOverviewLlm overviewLlm,
                                GeneratedEventRepository repo,
                                VibeLibrary vibeLibrary) {
        this.descService = descService;
        this.orchestrator = orchestrator;
        this.pricing = pricing;
        this.overviewLlm = overviewLlm;
        this.repo = repo;
        this.vibeLibrary = vibeLibrary;
    }

    @Transactional
    public ConceptResponse create(AuthPrincipal p, ConceptRequest req) {
        return run(p, req);
    }

    @Transactional
    public ConceptResponse regenerate(AuthPrincipal p, UUID conceptId, List<String> lock) {
        GeneratedEvent prior = repo.findByIdAndOrgId(conceptId, p.orgId())
                .orElseThrow(() -> ApiException.notFound("Concept"));
        ConceptRequest req = new ConceptRequest(
                prior.getVibe() == null ? "rerun" : prior.getVibe(),
                prior.getGenre(), prior.getCity(),
                /* capacity */ null, /* vibeId */ null,
                /* title */ null, /* eventDate */ null, /* venue */ null,
                /* lineup */ null, /* address */ null, /* rsvpUrl */ null);
        return run(p, req);
    }

    private ConceptResponse run(AuthPrincipal p, ConceptRequest req) {
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

        EventCreatorRequest legacy = toLegacyRequest(req);
        PosterConcept poster;
        OrchestrationResult render;
        ConceptOverview overview;
        try {
            AiEventDescriptionService.GeneratedConcept generated = descService.generateConcept(legacy, creativeSeed);
            // Pin the resolved vibe (legacy.subStyleTag is the selected vibe, or one auto-suggested
            // from genre) as the concept's style tag, so the orchestrator resolves that vibe's curated
            // reference flyers (forTag) regardless of what the LLM echoed.
            poster = new PosterConcept(legacy.subStyleTag(),
                    generated.concept().colorPaletteDescription(), generated.concept().variants());
            render = orchestrator.run(staging.getId(), legacy, poster, creativeSeed, generated.directions());
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
        List<PosterDto> posterDtos = mapPosters(render.posters(), overview.paletteHexes());

        // Persist V1 fields onto the staging row
        staging.setName(overview.name());
        staging.setDescription(overview.description());
        staging.setPaletteHexes(String.join(",", overview.paletteHexes() == null ? List.of() : overview.paletteHexes()));
        staging.setConfidencePct(overview.confidencePct());
        staging.setStatus(GeneratedEventStatus.COMPLETE);
        repo.save(staging);

        return new ConceptResponse(
                staging.getId(),
                overview.name(),
                overview.description(),
                posterDtos,
                overview.paletteHexes(),
                tiers,
                overview.suggestedCapacity(),
                overview.confidencePct());
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

    private EventCreatorRequest toLegacyRequest(ConceptRequest req) {
        Vibe vibe = resolveVibe(req);
        String djName = (req.lineup() == null || req.lineup().isEmpty())
                ? null : String.join(", ", req.lineup());
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
                /* accentColor */ null,
                /* address */ req.address(),
                /* rsvpUrl */ req.rsvpUrl(),
                /* subStyleTag: the selected vibe, or one auto-suggested from genre (always set) */
                vibe == null ? null : vibe.id(),
                providerFor(vibe));
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
