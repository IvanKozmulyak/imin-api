package com.imin.iminapi.service.poster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.HeroType;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterTextSpec;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.StyleReferencePart;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.model.PosterGeneration;
import com.imin.iminapi.model.PosterGenerationStatus;
import com.imin.iminapi.model.PosterVariantEntity;
import com.imin.iminapi.model.PosterVariantStatus;
import com.imin.iminapi.repository.PosterGenerationRepository;
import com.imin.iminapi.service.ai.CreativeDirection;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders the art director's 3 concept variants via the native Ideogram V3 API, validates each
 * with the vision gates, and persists the result.
 *
 * <p>Per variant: generate once; run the HARD text gate; on failure feed the failing image back to
 * Ideogram's remix endpoint with a correction prompt built from the gate's missing/extra text, up to
 * {@code poster.validation.max-regenerations} times, then accept best-effort. The SOFT style gate is
 * advisory — a style-only failure is accepted best-effort without another render. Text is fully baked
 * by the model; there is no QR/address/Satori overlay.
 */
@Service
public class PosterOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PosterOrchestrator.class);

    private static final int VARIANT_POOL_SIZE = 3;
    private static final long MAX_REF_TOTAL_BYTES = 10L * 1024 * 1024; // Ideogram cap
    private static final String VERDICT_ACCEPTED = "ACCEPTED";
    private static final String VERDICT_BEST_EFFORT = "BEST_EFFORT";
    private static final AtomicInteger POOL_THREAD_SEQ = new AtomicInteger();

    private final IdeogramV3Client ideogramClient;
    private final VibeLibrary vibeLibrary;
    private final StyleCardLibrary styleCardLibrary;
    private final ReferenceImageLibrary referenceLibrary;
    private final PosterTextSpecFactory textSpecFactory;
    private final PosterTextValidationService textValidation;
    private final PosterStyleValidationService styleValidation;
    private final PosterImageStorage storage;
    private final BrandLogoCompositor logoCompositor;
    private final PosterGenerationRepository generationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxRegenerations;
    private final int remixImageWeight;
    private final int maxReferences;
    private final Semaphore renderCap;
    private final ExecutorService variantPool;

    public PosterOrchestrator(
            IdeogramV3Client ideogramClient,
            VibeLibrary vibeLibrary,
            StyleCardLibrary styleCardLibrary,
            ReferenceImageLibrary referenceLibrary,
            PosterTextSpecFactory textSpecFactory,
            PosterTextValidationService textValidation,
            PosterStyleValidationService styleValidation,
            PosterImageStorage storage,
            BrandLogoCompositor logoCompositor,
            PosterGenerationRepository generationRepository,
            @Value("${poster.validation.max-regenerations:2}") int maxRegenerations,
            @Value("${ideogram.remix.image-weight:70}") int remixImageWeight,
            @Value("${ideogram.max-references:3}") int maxReferences,
            @Value("${poster.render.max-concurrent:${replicate.max-concurrent:6}}") int maxConcurrent) {
        this.ideogramClient = ideogramClient;
        this.vibeLibrary = vibeLibrary;
        this.styleCardLibrary = styleCardLibrary;
        this.referenceLibrary = referenceLibrary;
        this.textSpecFactory = textSpecFactory;
        this.textValidation = textValidation;
        this.styleValidation = styleValidation;
        this.storage = storage;
        this.logoCompositor = logoCompositor;
        this.generationRepository = generationRepository;
        this.maxRegenerations = Math.max(0, maxRegenerations);
        this.remixImageWeight = remixImageWeight;
        this.maxReferences = Math.max(0, maxReferences);
        this.renderCap = new Semaphore(maxConcurrent, true);
        this.variantPool = Executors.newFixedThreadPool(
                Math.min(VARIANT_POOL_SIZE, Math.max(1, maxConcurrent)),
                r -> {
                    Thread t = new Thread(r, "poster-variant-" + POOL_THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    public record OrchestrationResult(UUID generationId, String subStyleTag, List<GeneratedPoster> posters) {}

    /** Style control resolved once per generation: curated parts (win) + the preset fallback. */
    private record StyleControl(List<StyleReferencePart> parts, String preset) {
        boolean hasRefs() { return parts != null && !parts.isEmpty(); }
        List<String> ids() { return hasRefs() ? parts.stream().map(StyleReferencePart::filename).toList() : List.of(); }
    }

    private record RenderContext(Vibe vibe, StyleCard card, StyleControl style) {}

    public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept) {
        return run(generatedEventId, request, concept, deriveSeed(generatedEventId), List.of(), null);
    }

    public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept,
                                   long creativeSeed, List<CreativeDirection> directions) {
        return run(generatedEventId, request, concept, creativeSeed, directions, null);
    }

    public OrchestrationResult run(UUID generatedEventId, EventCreatorRequest request, PosterConcept concept,
                                   long creativeSeed, List<CreativeDirection> directions, BrandSnapshot brand) {
        PosterGeneration generation = new PosterGeneration();
        generation.setGeneratedEventId(generatedEventId);
        generation.setStatus(PosterGenerationStatus.PENDING);
        generation.setSubStyleTag(concept.subStyleTag());
        generation.setCreativeSeed(creativeSeed);
        if (brand != null) {
            generation.setBrandSnapshot(brand.toJson());
        }
        generation = generationRepository.save(generation);

        String tag = concept.subStyleTag();
        List<StyleReferencePart> parts = referenceLibrary.topReferenceParts(tag, maxReferences, MAX_REF_TOTAL_BYTES);
        String preset = vibeLibrary.ideogramStylePreset(tag);
        if (parts.isEmpty() && (preset == null || preset.isBlank())) {
            log.warn("Vibe '{}' has no reference images and no ideogram_style_preset — rendering with no style control", tag);
        }
        RenderContext ctx = new RenderContext(
                vibeLibrary.byId(tag).orElse(null),
                styleCardLibrary.get(tag).orElse(null),
                new StyleControl(parts, preset));

        List<PosterVariant> variants = concept.variants();
        final PosterGeneration gen = generation;
        final BrandSnapshot brandFinal = brand;
        List<Future<GeneratedPoster>> futures = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            final PosterVariant v = variants.get(i);
            final CreativeDirection dir = i < directions.size() ? directions.get(i) : null;
            final long seed = deriveSeed(creativeSeed, i);
            futures.add(variantPool.submit(() -> generateOne(gen, v, dir, seed, request, ctx, brandFinal)));
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

        boolean allFailed = results.stream().allMatch(p -> "FAILED".equals(p.status()));
        generation.setRawReadyAt(LocalDateTime.now());
        generation.setCompletedAt(LocalDateTime.now());
        generation.setStatus(allFailed ? PosterGenerationStatus.FAILED : PosterGenerationStatus.COMPLETE);
        generationRepository.save(generation);

        if (allFailed) {
            throw new IllegalStateException("All 3 poster variants failed — check upstream logs. "
                    + "First failure: " + results.get(0).failureReason());
        }
        return new OrchestrationResult(generation.getId(), tag, results);
    }

    private GeneratedPoster generateOne(
            PosterGeneration generation, PosterVariant variant, CreativeDirection direction,
            long seed, EventCreatorRequest request, RenderContext ctx, BrandSnapshot brand) {
        PosterVariantEntity entity = new PosterVariantEntity();
        entity.setPosterGeneration(generation);
        entity.setVariantStyle(variant.heroType());
        entity.setIdeogramPrompt(variant.ideogramPrompt());
        entity.setReferenceImagesUsed(String.join(",", ctx.style().ids()));
        entity.setSeed(seed);
        entity.setCreativeDirectionJson(serialize(direction));
        entity.setStatus(PosterVariantStatus.PENDING);
        synchronized (generation) {
            generation.getVariants().add(entity);
        }

        try {
            renderCap.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason("interrupted while waiting for render slot");
            return toDto(entity);
        }
        try {
            return renderWithValidation(entity, variant, seed, request, ctx, brand);
        } catch (RuntimeException e) {
            log.error("Variant generation failed: hero_type={}, seed={}", variant.heroType(), seed, e);
            entity.setStatus(PosterVariantStatus.FAILED);
            entity.setFailureReason(e.getMessage());
            return toDto(entity);
        } finally {
            renderCap.release();
        }
    }

    /** Generate → text gate (hard, corrective remix) → style gate (soft). */
    private GeneratedPoster renderWithValidation(
            PosterVariantEntity entity, PosterVariant variant, long baseSeed,
            EventCreatorRequest request, RenderContext ctx, BrandSnapshot brand) {
        PosterTextSpec spec = textSpecFactory.from(request);
        HeroType heroType = HeroType.fromWire(variant.heroType());
        StyleControl style = ctx.style();
        List<Map<String, Object>> attempts = new ArrayList<>();

        long seed = baseSeed;
        byte[] image = null;
        String url = null;
        String correction = null;

        for (int attempt = 0; attempt <= maxRegenerations; attempt++) {
            boolean last = attempt == maxRegenerations;
            entity.setSeed(seed);

            IdeogramV3Client.IdeogramResult render = (attempt == 0)
                    ? ideogramClient.generate(variant.ideogramPrompt(), seed, style.parts(), style.preset())
                    : ideogramClient.remix(image, correction, remixImageWeight, seed, style.parts(), style.preset());
            image = render.imageBytes();
            url = storage.writePng(image);
            entity.setRawUrl(url);
            entity.setStatus(PosterVariantStatus.RAW_READY);

            PosterTextValidationService.ValidationDecision text = textValidation.validateOrExplain(image, spec);
            if (!text.accepted()) {
                attempts.add(attemptJson(attempt, seed, attempt == 0 ? "generate" : "remix", text, null));
                if (last) {
                    log.warn("Text gate still failing after {} regenerations; accepting best-effort: {}",
                            maxRegenerations, text.reason());
                    return accept(entity, url, VERDICT_BEST_EFFORT, attempts, brand);
                }
                correction = buildCorrectionPrompt(variant.ideogramPrompt(), text);
                seed = nextSeed(seed);
                continue;
            }

            PosterStyleValidationService.ValidationDecision styleDecision =
                    styleValidation.validateOrExplain(image, ctx.card(), heroType);
            attempts.add(attemptJson(attempt, seed, attempt == 0 ? "generate" : "remix", text, styleDecision));
            if (styleDecision.accepted()) {
                return accept(entity, url, VERDICT_ACCEPTED, attempts, brand);
            }
            // Text is correct; style is soft — accept best-effort without spending more renders.
            log.warn("Style gate soft-failed (text OK); accepting best-effort: {}", styleDecision.reason());
            return accept(entity, url, VERDICT_BEST_EFFORT, attempts, brand);
        }
        throw new IllegalStateException("render-with-validation loop exhausted");
    }

    /**
     * The single funnel that sets final_url for every acceptance path. Composites the brand logo
     * when the snapshot says to, as a SECOND storage write (final_url = composited URL; raw_url keeps
     * the un-composited render). Failure isolation is absolute: any composite error → final_url =
     * raw_url + Sentry warning + status FAILED. Generation never fails over the logo.
     */
    private GeneratedPoster accept(PosterVariantEntity entity, String rawUrl, String verdict,
                                   List<Map<String, Object>> attempts, BrandSnapshot brand) {
        entity.setValidationVerdict(verdict);
        entity.setValidationAttemptsJson(serialize(attempts));
        entity.setStatus(PosterVariantStatus.COMPLETE);

        String finalUrl = rawUrl;
        String compositeStatus;
        if (brand == null || !brand.logoOn() || brand.logoUrl() == null || brand.logoUrl().isBlank()) {
            compositeStatus = "SKIPPED";
        } else {
            try {
                byte[] rawBytes = storage.download(rawUrl);
                byte[] composited = logoCompositor.composite(rawBytes, brand.logoUrl());
                finalUrl = storage.writePng(composited); // SECOND write → distinct object/URL
                compositeStatus = "APPLIED";
            } catch (RuntimeException e) {
                log.warn("Logo composite failed; shipping un-composited poster: {}", e.getMessage());
                Sentry.withScope(scope -> {
                    scope.setLevel(SentryLevel.WARNING);
                    scope.setTag("subsystem", "brand-logo-composite");
                    Sentry.captureException(e);
                });
                finalUrl = rawUrl;
                compositeStatus = "FAILED";
            }
        }
        entity.setFinalUrl(finalUrl);
        entity.setLogoCompositeStatus(compositeStatus);
        return toDto(entity);
    }

    static String buildCorrectionPrompt(String originalPrompt,
                                        PosterTextValidationService.ValidationDecision text) {
        StringBuilder c = new StringBuilder(originalPrompt);
        c.append("\n\nCORRECTION — the previous render had text errors. ");
        if (text.missingRequired() != null && !text.missingRequired().isEmpty()) {
            c.append("Render these exact strings, correctly spelled and clearly legible: ")
             .append(quoteJoin(text.missingRequired())).append(". ");
        }
        if (text.extraText() != null && !text.extraText().isEmpty()) {
            c.append("Remove these invented or garbled words: ")
             .append(quoteJoin(text.extraText())).append(". ");
        }
        c.append("Keep the composition, hero, colors, and layout identical — only fix the text.");
        return c.toString();
    }

    private static String quoteJoin(List<String> items) {
        return String.join(", ", items.stream().map(s -> "\"" + s + "\"").toList());
    }

    private Map<String, Object> attemptJson(int attempt, long seed, String mode,
                                            PosterTextValidationService.ValidationDecision text,
                                            PosterStyleValidationService.ValidationDecision style) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempt", attempt);
        m.put("seed", seed);
        m.put("mode", mode);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("accepted", text.accepted());
        t.put("reason", text.reason() == null ? "" : text.reason());
        m.put("text", t);
        Map<String, Object> s = new LinkedHashMap<>();
        if (style == null) {
            s.put("skipped", true);
        } else {
            s.put("accepted", style.accepted());
            s.put("reason", style.reason() == null ? "" : style.reason());
        }
        m.put("style", s);
        return m;
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialize {}: {}", value.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static long deriveSeed(long creativeSeed, int index) {
        long s = creativeSeed * 1_000_003L + index;
        return Math.floorMod(s, 1_000_000_000L) + 1L;
    }

    private static long deriveSeed(UUID id) {
        return id == null ? 1L : Math.abs(id.getMostSignificantBits() ^ id.getLeastSignificantBits());
    }

    private static long nextSeed(long seed) {
        long s = seed * 6364136223846793005L + 1442695040888963407L;
        return Math.floorMod(s, 1_000_000_000L) + 1L;
    }

    private GeneratedPoster toDto(PosterVariantEntity e) {
        Map<String, Object> overlays = new HashMap<>();
        overlays.put("qr_code", false);
        overlays.put("address", false);
        List<String> refs = e.getReferenceImagesUsed() == null || e.getReferenceImagesUsed().isBlank()
                ? List.of() : List.of(e.getReferenceImagesUsed().split(","));
        return new GeneratedPoster(
                e.getId(), e.getVariantStyle(), e.getRawUrl(), e.getFinalUrl(),
                e.getSeed() != null ? e.getSeed() : 0L, e.getIdeogramPrompt(), refs, overlays,
                e.getStatus().name(), e.getFailureReason());
    }

    private GeneratedPoster failedPoster(UUID id, String style, String reason) {
        return new GeneratedPoster(id, style, null, null, 0L, "", List.of(), Map.of(), "FAILED", reason);
    }
}
