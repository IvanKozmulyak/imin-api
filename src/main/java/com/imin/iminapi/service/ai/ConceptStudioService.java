package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.dto.ai.*;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.ImageProvider;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.AiEventDescriptionService;
import com.imin.iminapi.service.PricingService;
import com.imin.iminapi.service.poster.PosterOrchestrator;
import com.imin.iminapi.service.poster.PosterOrchestrator.OrchestrationResult;
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

    public ConceptStudioService(AiEventDescriptionService descService,
                                PosterOrchestrator orchestrator,
                                PricingService pricing,
                                ConceptOverviewLlm overviewLlm,
                                GeneratedEventRepository repo) {
        this.descService = descService;
        this.orchestrator = orchestrator;
        this.pricing = pricing;
        this.overviewLlm = overviewLlm;
        this.repo = repo;
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
                /* capacity hint */ null);
        return run(p, req);
    }

    private ConceptResponse run(AuthPrincipal p, ConceptRequest req) {
        GeneratedEvent staging = newStagingRow(p, req);
        repo.save(staging);

        EventCreatorRequest legacy = toLegacyRequest(req);
        PosterConcept poster;
        OrchestrationResult render;
        ConceptOverview overview;
        try {
            poster = descService.generateConcept(legacy);
            render = orchestrator.run(staging.getId(), legacy, poster);
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

    private EventCreatorRequest toLegacyRequest(ConceptRequest req) {
        return new EventCreatorRequest(
                req.vibe(),
                DEFAULT_TONE,
                req.genre() == null ? "Techno" : req.genre(),
                req.city() == null ? "Berlin" : req.city(),
                LocalDate.now().plusMonths(2),
                DEFAULT_PLATFORMS,
                /* djName */ null,
                /* location */ null,
                /* title */ null,
                /* accentColor */ null,
                /* address */ null,
                /* rsvpUrl */ null,
                /* subStyleTag (let LLM pick) */ null,
                ImageProvider.REPLICATE);
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
