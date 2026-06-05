package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterTextSpec;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.ReferenceImageSet;
import com.imin.iminapi.dto.Rgb;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.StyleMode;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.model.ImageProvider;
import com.imin.iminapi.model.PosterGeneration;
import com.imin.iminapi.model.PosterGenerationStatus;
import com.imin.iminapi.model.PosterVariantEntity;
import com.imin.iminapi.model.PosterVariantStatus;
import com.imin.iminapi.repository.PosterGenerationRepository;
import com.imin.iminapi.service.ai.CreativeDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

@Service
public class PosterOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PosterOrchestrator.class);

    private final IdeogramClient ideogramClient;
    private final OpenAiImageClient openAiImageClient;
    private final RecraftClient recraftClient;
    private final VibeStyleTrainingService vibeStyleTrainingService;
    private final VibeLibrary vibeLibrary;
    private final StyleCardLibrary styleCardLibrary;
    private final ReferenceImageLibrary referenceLibrary;
    private final OverlayCompositor overlayCompositor;
    private final PosterTextCompositorClient textCompositor;
    private final PosterTextSpecFactory textSpecFactory;
    private final PosterTextValidationService textValidation;
    private final PosterStyleValidationService styleValidation;
    private final PosterImageStorage storage;
    private final PosterGenerationRepository generationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxRegenerations;
    private final boolean skipCompositorWhenTextBaked;
    private final Semaphore replicateCap;
    private final ExecutorService variantPool;

    public PosterOrchestrator(
            IdeogramClient ideogramClient,
            OpenAiImageClient openAiImageClient,
            RecraftClient recraftClient,
            VibeStyleTrainingService vibeStyleTrainingService,
            VibeLibrary vibeLibrary,
            StyleCardLibrary styleCardLibrary,
            ReferenceImageLibrary referenceLibrary,
            OverlayCompositor overlayCompositor,
            PosterTextCompositorClient textCompositor,
            PosterTextSpecFactory textSpecFactory,
            PosterTextValidationService textValidation,
            PosterStyleValidationService styleValidation,
            PosterImageStorage storage,
            PosterGenerationRepository generationRepository,
            // Unified regeneration budget covering legibility + style checks combined (cost/latency control).
            // poster.text-validation.max-regenerations is kept as a deprecated alias.
            @Value("${poster.validation.max-regenerations:${poster.text-validation.max-regenerations:2}}") int maxRegenerations,
            // Text is now always Recraft-baked, so the Satori real-font compositor is skipped by default.
            @Value("${poster.compositor.skip-when-text-baked:true}") boolean skipCompositorWhenTextBaked,
            @Value("${replicate.max-concurrent:6}") int maxConcurrent) {
        this.ideogramClient = ideogramClient;
        this.openAiImageClient = openAiImageClient;
        this.recraftClient = recraftClient;
        this.vibeStyleTrainingService = vibeStyleTrainingService;
        this.vibeLibrary = vibeLibrary;
        this.styleCardLibrary = styleCardLibrary;
        this.referenceLibrary = referenceLibrary;
        this.overlayCompositor = overlayCompositor;
        this.textCompositor = textCompositor;
        this.textSpecFactory = textSpecFactory;
        this.textValidation = textValidation;
        this.styleValidation = styleValidation;
        this.storage = storage;
        this.generationRepository = generationRepository;
        this.maxRegenerations = Math.max(0, maxRegenerations);
        this.skipCompositorWhenTextBaked = skipCompositorWhenTextBaked;
        this.replicateCap = new Semaphore(maxConcurrent, true);
        this.variantPool = Executors.newFixedThreadPool(
                Math.min(VARIANT_POOL_SIZE, Math.max(1, maxConcurrent)),
                r -> {
                    Thread t = new Thread(r, "poster-variant-" + POOL_THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    private static final int VARIANT_POOL_SIZE = 3;
    private static final java.util.concurrent.atomic.AtomicInteger POOL_THREAD_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    public record OrchestrationResult(UUID generationId, String subStyleTag, List<GeneratedPoster> posters) {}

    /** The render config resolved once per generation from the vibe + its style card. */
    private record RenderContext(Vibe vibe, StyleCard card) {}

    public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept) {
        return run(generatedEventId, request, concept, deriveSeed(generatedEventId), List.of());
    }

    public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept,
                                   long creativeSeed, List<CreativeDirection> directions) {
        PosterGeneration generation = new PosterGeneration();
        generation.setGeneratedEventId(generatedEventId);
        generation.setStatus(PosterGenerationStatus.PENDING);
        generation.setSubStyleTag(concept.subStyleTag());
        generation.setCreativeSeed(creativeSeed);
        generation = generationRepository.save(generation);

        ReferenceImageSet refs = referenceLibrary.forTag(concept.subStyleTag());
        if (refs.referenceUrls().isEmpty()) {
            log.warn("No reference images seeded for tag '{}' — provider will run without style_reference_images",
                    concept.subStyleTag());
        }
        RenderContext ctx = new RenderContext(
                vibeLibrary.byId(concept.subStyleTag()).orElse(null),
                styleCardLibrary.get(concept.subStyleTag()).orElse(null));

        List<PosterVariant> variants = concept.variants();
        final PosterGeneration gen = generation;
        // Dispatch the variants concurrently; the Semaphore inside generateOne is the real upstream
        // concurrency cap. Submit/collect/join in order so results preserve variant order.
        List<Future<GeneratedPoster>> futures = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            final PosterVariant v = variants.get(i);
            final CreativeDirection dir = i < directions.size() ? directions.get(i) : null;
            final long seed = deriveSeed(creativeSeed, i);
            futures.add(variantPool.submit(() -> generateOne(gen, v, dir, seed, refs, request, ctx)));
        }

        List<GeneratedPoster> results = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            PosterVariant v = variants.get(i);
            try {
                results.add(futures.get(i).get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("Variant task threw unexpectedly", cause);
                results.add(failedPoster(null, v.heroType(), cause.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while awaiting variant task", e);
                results.add(failedPoster(null, v.heroType(), "interrupted while awaiting variant"));
            }
        }

        boolean allFailed  = results.stream().allMatch(p -> "FAILED".equals(p.status()));
        boolean anyOk      = results.stream().anyMatch(p -> !"FAILED".equals(p.status()));

        generation.setRawReadyAt(LocalDateTime.now());
        generation.setCompletedAt(LocalDateTime.now());
        generation.setStatus(allFailed ? PosterGenerationStatus.FAILED
                : anyOk ? PosterGenerationStatus.COMPLETE
                : PosterGenerationStatus.FAILED);
        generationRepository.save(generation);

        if (allFailed) {
            throw new IllegalStateException("All 3 poster variants failed — check upstream logs. "
                    + "First failure: " + results.get(0).failureReason());
        }

        return new OrchestrationResult(generation.getId(), concept.subStyleTag(), results);
    }

    private GeneratedPoster generateOne(
            PosterGeneration generation,
            PosterVariant variant,
            CreativeDirection direction,
            long seed,
            ReferenceImageSet refs,
            EventCreatorRequest request,
            RenderContext ctx) {
        PosterVariantEntity entity = new PosterVariantEntity();
        entity.setPosterGeneration(generation);
        entity.setVariantStyle(variant.heroType());
        entity.setIdeogramPrompt(variant.ideogramPrompt());
        entity.setReferenceImagesUsed(String.join(",", refs.referenceIds()));
        entity.setSeed(seed);
        entity.setCreativeDirectionJson(serializeDirection(direction));
        entity.setStatus(PosterVariantStatus.PENDING);
        // Shared mutable collection across the variant pool threads — synchronize the add.
        synchronized (generation) {
            generation.getVariants().add(entity);
        }

        ImageProvider provider = request.effectiveImageProvider();
        try {
            replicateCap.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason("interrupted while waiting for replicate slot");
            return toDto(entity);
        }
        try {
            if (provider == ImageProvider.RECRAFT) {
                return generateRecraftWithValidation(entity, variant, refs, seed, request, ctx);
            }
            byte[] rawBytes = renderVariant(provider, variant, refs, seed);
            String rawUrl = storage.writePng(rawBytes);
            entity.setRawUrl(rawUrl);
            entity.setStatus(PosterVariantStatus.RAW_READY);

            byte[] finalBytes = applyTextLayer(rawBytes, refs, request);
            String finalUrl = finalBytes == rawBytes ? rawUrl : storage.writePng(finalBytes);
            entity.setFinalUrl(finalUrl);
            entity.setStatus(PosterVariantStatus.COMPLETE);
            return toDto(entity);
        } catch (RuntimeException e) {
            log.error("Variant generation failed: provider={}, hero_type={}, seed={}",
                    provider, variant.heroType(), seed, e);
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason(e.getMessage());
            return toDto(entity);
        } finally {
            replicateCap.release();
        }
    }

    /**
     * Recraft path with a unified validation budget. Each render is checked against two gates, in
     * order, and a failure of either consumes one regeneration (a fresh seed) from the shared budget:
     * <ol>
     *   <li>legibility (the existing text gate) — a HARD gate: exhausting the budget here fails the
     *       variant, because a poster missing the required event text is unusable;</li>
     *   <li>style adherence (hero present, medium/palette right) — a SOFT gate: once the budget is
     *       exhausted the last render is accepted best-effort with a warning, since style is subjective
     *       and we already paid the cost cap.</li>
     * </ol>
     */
    private GeneratedPoster generateRecraftWithValidation(
            PosterVariantEntity entity,
            PosterVariant variant,
            ReferenceImageSet refs,
            long baseSeed,
            EventCreatorRequest request,
            RenderContext ctx) {
        PosterTextSpec spec = textSpecFactory.from(request);
        HeroType heroType = HeroType.fromWire(variant.heroType());
        long seed = baseSeed;

        for (int attempt = 0; attempt <= maxRegenerations; attempt++) {
            boolean last = attempt == maxRegenerations;
            entity.setSeed(seed);
            byte[] rawBytes = renderRecraft(variant, refs, ctx);
            String rawUrl = storage.writePng(rawBytes);
            entity.setRawUrl(rawUrl);
            entity.setStatus(PosterVariantStatus.RAW_READY);

            PosterTextValidationService.ValidationDecision textDecision =
                    textValidation.validateOrExplain(rawBytes, spec);
            if (!textDecision.accepted()) {
                if (!last) {
                    log.warn("Recraft text validation failed (attempt {}/{}); regenerating with a new seed: {}",
                            attempt, maxRegenerations, textDecision.reason());
                    seed = nextSeed(seed);
                    continue;
                }
                // Budget exhausted: a draft poster with imperfect text beats a 502 to the organizer. The
                // text was requested in the prompt and checked here; the image model just couldn't render it.
                log.warn("Recraft text validation still failing after {} regenerations; accepting best-effort: {}",
                        maxRegenerations, textDecision.reason());
                return acceptRecraftVariant(entity, rawBytes, rawUrl, request);
            }

            PosterStyleValidationService.ValidationDecision styleDecision =
                    styleValidation.validateOrExplain(rawBytes, ctx.card(), heroType);
            if (!styleDecision.accepted() && !last) {
                log.warn("Recraft style validation failed (attempt {}/{}); regenerating with a new seed: {}",
                        attempt, maxRegenerations, styleDecision.reason());
                seed = nextSeed(seed);
                continue;
            }
            if (!styleDecision.accepted()) {
                log.warn("Recraft style validation still failing after {} regenerations; accepting best-effort: {}",
                        maxRegenerations, styleDecision.reason());
            }
            return acceptRecraftVariant(entity, rawBytes, rawUrl, request);
        }
        throw new IllegalStateException("Recraft validation loop exhausted");
    }

    /** Apply the QR + address overlay (text is Recraft-baked; no Satori compositor) and mark COMPLETE. */
    private GeneratedPoster acceptRecraftVariant(
            PosterVariantEntity entity, byte[] rawBytes, String rawUrl, EventCreatorRequest request) {
        byte[] finalBytes = overlayCompositor.applyOverlays(
                new OverlayCompositor.Input(rawBytes, request.rsvpUrl(), request.address()));
        String finalUrl = finalBytes == rawBytes ? rawUrl : storage.writePng(finalBytes);
        entity.setFinalUrl(finalUrl);
        entity.setStatus(PosterVariantStatus.COMPLETE);
        return toDto(entity);
    }

    /**
     * Composite the text layer for the non-Recraft providers. Since text is now always baked into the
     * generated image, the Satori real-font compositor is skipped by default
     * ({@code poster.compositor.skip-when-text-baked=true}) and only the QR + address overlay is applied.
     * Set the flag false to restore the Satori compositor for curated vibes with real event text.
     */
    private byte[] applyTextLayer(byte[] rawBytes, ReferenceImageSet refs, EventCreatorRequest request) {
        String vibeId = refs.subStyleTag();
        if (!skipCompositorWhenTextBaked && textCompositor.supports(vibeId) && hasEventText(request)) {
            try {
                return textCompositor.composite(
                        rawBytes, vibeId, PosterTextCompositorClient.EventText.from(request));
            } catch (RuntimeException e) {
                log.error("Text compositor failed for vibe {} — failing variant to avoid poster without event text: {}",
                        vibeId, e.getMessage());
                throw e;
            }
        }
        return overlayCompositor.applyOverlays(new OverlayCompositor.Input(
                rawBytes, request.rsvpUrl(), request.address()));
    }

    /** The text layer is only meaningful when there is a real event title to render. */
    private static boolean hasEventText(EventCreatorRequest request) {
        return request.title() != null && !request.title().isBlank();
    }

    /** Recraft render under the vibe's {@link StyleMode}: trained style_id vs curated substyle + palette. */
    private byte[] renderRecraft(PosterVariant variant, ReferenceImageSet refs, RenderContext ctx) {
        Vibe vibe = ctx.vibe();
        StyleMode mode = (vibe != null && vibe.styleMode() != null) ? vibe.styleMode() : StyleMode.TRAINED_STYLE_ID;
        List<byte[]> refBytes = referenceLibrary.loadAllBytes(refs.subStyleTag());

        RecraftClient.RenderSpec renderSpec;
        if (mode == StyleMode.CURATED_SUBSTYLE) {
            List<Rgb> palette = ctx.card() != null ? ctx.card().palette() : List.of();
            renderSpec = RecraftClient.RenderSpec.curated(vibe == null ? null : vibe.substyle(), palette);
        } else {
            String styleId = vibeStyleTrainingService.resolveStyleId(refs.subStyleTag(), ImageProvider.RECRAFT);
            renderSpec = RecraftClient.RenderSpec.trained(styleId);
        }
        return recraftClient.generate(
                variant.ideogramPrompt(), variant.aspectRatio(), renderSpec, refBytes).imageBytes();
    }

    private byte[] renderVariant(
            ImageProvider provider,
            PosterVariant variant,
            ReferenceImageSet refs,
            long seed) {
        if (provider == ImageProvider.OPENAI) {
            List<byte[]> refBytes = referenceLibrary.loadAllBytes(refs.subStyleTag());
            return openAiImageClient.generate(
                    variant.ideogramPrompt(),
                    variant.aspectRatio(),
                    refBytes,
                    seed).imageBytes();
        }
        IdeogramClient.IdeogramResult ideogram = ideogramClient.generate(
                variant.ideogramPrompt(),
                variant.aspectRatio(),
                refs.referenceUrls(),
                seed,
                variant.styleType());
        return storage.download(ideogram.imageUrl());
    }

    private String serializeDirection(CreativeDirection direction) {
        if (direction == null) return null;
        try {
            return objectMapper.writeValueAsString(direction);
        } catch (Exception e) {
            log.warn("Could not serialize creative direction: {}", e.getMessage());
            return null;
        }
    }

    /** Deterministic per-variant image seed derived from the generation's creative seed + index. */
    private static long deriveSeed(long creativeSeed, int index) {
        long s = creativeSeed * 1_000_003L + index;
        return Math.floorMod(s, 1_000_000_000L) + 1L;
    }

    /** Deterministic creative seed from the generation/event UUID (when none is supplied). */
    private static long deriveSeed(UUID id) {
        return id == null ? 1L : Math.abs(id.getMostSignificantBits() ^ id.getLeastSignificantBits());
    }

    /** A fresh seed for a regeneration attempt (deterministic step so runs stay reproducible). */
    private static long nextSeed(long seed) {
        long s = seed * 6364136223846793005L + 1442695040888963407L;
        return Math.floorMod(s, 1_000_000_000L) + 1L;
    }

    private GeneratedPoster toDto(PosterVariantEntity e) {
        Map<String, Object> overlays = new HashMap<>();
        overlays.put("qr_code", e.getStatus() == PosterVariantStatus.COMPLETE);
        overlays.put("address", e.getStatus() == PosterVariantStatus.COMPLETE);
        List<String> refs = e.getReferenceImagesUsed() == null || e.getReferenceImagesUsed().isBlank()
                ? List.of()
                : List.of(e.getReferenceImagesUsed().split(","));
        return new GeneratedPoster(
                e.getId(),
                e.getVariantStyle(),
                e.getRawUrl(),
                e.getFinalUrl(),
                e.getSeed() != null ? e.getSeed() : 0L,
                e.getIdeogramPrompt(),
                refs,
                overlays,
                e.getStatus().name(),
                e.getFailureReason());
    }

    private GeneratedPoster failedPoster(UUID id, String style, String reason) {
        return new GeneratedPoster(id, style, null, null, 0L, "", List.of(), Map.of(), "FAILED", reason);
    }
}
